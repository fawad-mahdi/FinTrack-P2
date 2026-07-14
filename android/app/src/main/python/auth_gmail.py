import os
import json
import base64
from datetime import datetime, timedelta
from urllib.parse import urlencode
from google.oauth2.credentials import Credentials
from google.auth.transport.requests import Request  # noqa: F401 — kept for desktop compat
from googleapiclient.discovery import build

SCOPES = ["https://www.googleapis.com/auth/gmail.readonly"]


class _AndroidCredentials(Credentials):
    """Credentials subclass that refreshes tokens via Java HttpURLConnection.

    On Android (Chaquopy), Python's socket cannot resolve DNS.  The default
    Credentials.refresh() uses Python's ``requests`` library which fails.
    This subclass overrides refresh() to use Java networking via _java_post(),
    which properly uses Android's network stack.
    """

    def __init__(self, token_path, *args, **kwargs):
        # type: (str, ...) -> None
        self._token_path = token_path
        super(_AndroidCredentials, self).__init__(*args, **kwargs)

    def refresh(self, request):
        # type: (...) -> None
        """Refresh the access token using Java networking (Android-safe)."""
        tokens = _java_post("https://oauth2.googleapis.com/token", {
            "client_id": self.client_id,
            "client_secret": self.client_secret,
            "refresh_token": self.refresh_token,
            "grant_type": "refresh_token",
        })

        self.token = tokens["access_token"]
        if "refresh_token" in tokens:
            self._refresh_token = tokens["refresh_token"]
        if "expires_in" in tokens:
            self.expiry = datetime.utcnow() + timedelta(
                seconds=int(tokens["expires_in"])
            )

        # Persist so the Kotlin side and subsequent Python loads stay in sync
        token_json = {
            "token": self.token,
            "refresh_token": self.refresh_token,
            "token_uri": self.token_uri,
            "client_id": self.client_id,
            "client_secret": self.client_secret,
            "scopes": list(SCOPES),
        }
        if self.expiry:
            token_json["expiry"] = self.expiry.strftime("%Y-%m-%dT%H:%M:%SZ")
        with open(self._token_path, "w") as f:
            json.dump(token_json, f, indent=2)


class OAuthRequiredError(Exception):
    """Raised when OAuth authorization is needed (no valid token on Android)."""
    pass


def _get_config_dir():
    """Resolve config dir lazily so env vars injected after import are picked up."""
    return os.environ.get('FINTRACK_CONFIG_DIR', os.path.dirname(__file__))


def _java_post(url, form_data):
    """HTTP POST using Java's HttpURLConnection via the active Android network.

    On Android 14+ (S24 Ultra etc.), plain URL.openConnection() on a
    Chaquopy background thread cannot resolve DNS because the thread is
    not bound to a network.  Using Network.openConnection() routes through
    the device's active network and its DNS resolver.
    """
    from java.net import URL as JavaURL
    from java.io import BufferedReader, InputStreamReader, DataOutputStream

    body = urlencode(form_data).encode("utf-8")
    java_url = JavaURL(url)

    # On Android, open connection through the active network so DNS works
    # on background threads (required for Android 14+).
    try:
        from com.chaquo.python import Python as ChaquoPython
        context = ChaquoPython.getPlatform().getApplication()
        cm = context.getSystemService("connectivity")
        network = cm.getActiveNetwork()
        if network is not None:
            conn = network.openConnection(java_url)
        else:
            conn = java_url.openConnection()
    except Exception:
        # Fallback for non-Android (desktop) or if reflection fails
        conn = java_url.openConnection()
    conn.setRequestMethod("POST")
    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
    conn.setDoOutput(True)

    out = DataOutputStream(conn.getOutputStream())
    out.write(body)
    out.flush()
    out.close()

    code = conn.getResponseCode()
    stream = conn.getInputStream() if code < 400 else conn.getErrorStream()
    reader = BufferedReader(InputStreamReader(stream, "UTF-8"))
    lines = []
    while True:
        line = reader.readLine()
        if line is None:
            break
        lines.append(str(line))
    reader.close()
    conn.disconnect()

    resp_body = "\n".join(lines)
    if code >= 400:
        raise RuntimeError(f"Token endpoint returned {code}: {resp_body}")
    return json.loads(resp_body)


def get_gmail_service():
    """Get authenticated Gmail API service.

    On Android, the token.json is created by the native Kotlin OAuth flow
    (AppAuth + OAuthCallbackActivity). If no token exists, raises
    OAuthRequiredError so the server can tell the frontend to trigger
    the native flow.
    """
    config_dir = _get_config_dir()
    token_path = os.path.join(config_dir, "token.json")
    creds_path = os.path.join(config_dir, "credentials.json")

    creds = None

    if os.path.exists(token_path):
        # Load the raw token JSON and build an _AndroidCredentials instance
        # so that ANY refresh (explicit or auto-triggered by googleapiclient)
        # goes through Java networking instead of Python's broken DNS.
        with open(token_path) as f:
            info = json.load(f)

        expiry = None
        if info.get("expiry"):
            try:
                # Handle both "%Y-%m-%dT%H:%M:%SZ" and "%Y-%m-%dT%H:%M:%S.%fZ"
                exp_str = info["expiry"]
                for fmt in ("%Y-%m-%dT%H:%M:%SZ", "%Y-%m-%dT%H:%M:%S.%fZ", "%Y-%m-%dT%H:%M:%S"):
                    try:
                        expiry = datetime.strptime(exp_str, fmt)
                        break
                    except ValueError:
                        continue
            except Exception:
                pass  # treat as expired → will refresh

        creds = _AndroidCredentials(
            token_path=token_path,
            token=info.get("token"),
            refresh_token=info.get("refresh_token"),
            token_uri=info.get("token_uri", "https://oauth2.googleapis.com/token"),
            client_id=info.get("client_id", ""),
            client_secret=info.get("client_secret", ""),
            scopes=SCOPES,
            expiry=expiry,
        )

    if not creds or not creds.valid:
        if creds and creds.expired and creds.refresh_token:
            creds.refresh(None)  # uses Java networking via _AndroidCredentials
        else:
            # On Android, token.json is created by native OAuth flow.
            # Signal the caller so the frontend can trigger it.
            raise OAuthRequiredError(
                "Gmail not connected. Please authenticate via the app."
            )

    return build("gmail", "v1", credentials=creds)


def search_emails(service, query: str, max_results: int = 50) -> list:
    """Search Gmail and return list of message dicts with id, sender, snippet, body."""
    from email.utils import parsedate_to_datetime

    results = (
        service.users()
        .messages()
        .list(userId="me", q=query, maxResults=max_results)
        .execute()
    )

    messages = results.get("messages", [])
    emails = []

    for msg_meta in messages:
        msg = (
            service.users()
            .messages()
            .get(userId="me", id=msg_meta["id"], format="full")
            .execute()
        )

        headers = {h["name"].lower(): h["value"] for h in msg["payload"]["headers"]}
        sender = headers.get("from", "")
        subject = headers.get("subject", "")
        date_raw = headers.get("date", "")

        # Parse email date header into ISO format
        try:
            date_iso = parsedate_to_datetime(date_raw).isoformat()
        except Exception:
            date_iso = date_raw

        # Extract body text
        body = extract_body(msg["payload"])

        emails.append(
            {
                "gmail_id": msg_meta["id"],
                "sender": sender,
                "subject": subject,
                "date": date_iso,
                "body": body,
                "snippet": msg.get("snippet", ""),
            }
        )

    return emails


def extract_body(payload: dict) -> str:
    """Extract plain text body from Gmail message payload."""
    # Simple single-part message
    if payload.get("body", {}).get("data"):
        return base64.urlsafe_b64decode(payload["body"]["data"]).decode("utf-8", errors="replace")

    # Multipart — look for text/plain first, then text/html
    parts = payload.get("parts", [])
    for mime in ["text/plain", "text/html"]:
        for part in parts:
            if part.get("mimeType") == mime and part.get("body", {}).get("data"):
                return base64.urlsafe_b64decode(part["body"]["data"]).decode("utf-8", errors="replace")
            # Check nested parts
            for sub in part.get("parts", []):
                if sub.get("mimeType") == mime and sub.get("body", {}).get("data"):
                    return base64.urlsafe_b64decode(sub["body"]["data"]).decode("utf-8", errors="replace")

    return ""
