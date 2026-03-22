import os
import json
import base64
import hashlib
import secrets
from typing import Optional
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


# PKCE + state storage for in-flight OAuth — persisted to disk so it survives server restarts
def _get_oauth_state_path():
    return os.path.join(_get_config_dir(), ".oauth_state.json")


def _load_oauth_state():
    # type: () -> dict
    path = _get_oauth_state_path()
    if os.path.exists(path):
        try:
            with open(path) as f:
                return json.load(f)
        except Exception:
            return {}
    return {}


def _save_oauth_state(state_dict):
    # type: (dict) -> None
    path = _get_oauth_state_path()
    with open(path, "w") as f:
        json.dump(state_dict, f)


class OAuthRequiredError(Exception):
    """Raised when OAuth authorization is needed (no valid token on Android)."""
    pass


def _get_config_dir():
    """Resolve config dir lazily so env vars injected after import are picked up."""
    return os.environ.get('FINTRACK_CONFIG_DIR', os.path.dirname(__file__))


def _load_client_credentials():
    """Load client_id and client_secret from credentials.json."""
    config_dir = _get_config_dir()
    creds_path = os.path.join(config_dir, "credentials.json")
    if not os.path.exists(creds_path):
        raise FileNotFoundError(
            "Missing credentials.json. Download OAuth credentials from "
            "Google Cloud Console and place in the config directory."
        )
    with open(creds_path) as f:
        data = json.load(f)
    installed = data.get("installed", data.get("web", {}))
    return installed["client_id"], installed["client_secret"]


def build_oauth_url(redirect_uri):
    """Build the Google OAuth authorization URL with PKCE.

    Returns (auth_url, state) — caller must open auth_url in a browser.
    The /oauth/callback route will use the stored PKCE verifier.
    """
    client_id, _ = _load_client_credentials()

    # PKCE: generate code_verifier and code_challenge
    code_verifier = secrets.token_urlsafe(64)
    code_challenge = (
        base64.urlsafe_b64encode(hashlib.sha256(code_verifier.encode()).digest())
        .rstrip(b"=")
        .decode()
    )

    state = secrets.token_urlsafe(32)

    # Store for the callback (persisted to disk for server restart survival)
    all_state = _load_oauth_state()
    all_state[state] = {"code_verifier": code_verifier, "redirect_uri": redirect_uri}
    _save_oauth_state(all_state)

    params = {
        "client_id": client_id,
        "redirect_uri": redirect_uri,
        "response_type": "code",
        "scope": " ".join(SCOPES),
        "access_type": "offline",
        "prompt": "consent",
        "state": state,
        "code_challenge": code_challenge,
        "code_challenge_method": "S256",
    }

    auth_url = "https://accounts.google.com/o/oauth2/auth?" + urlencode(params)
    return auth_url, state


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


def save_pending_exchange(code, state):
    """Save the auth code + PKCE verifier for Kotlin-side token exchange.

    On Android 14+, outbound HTTPS from Chaquopy threads fails (DNS broken).
    Instead of exchanging here, we persist the data so the Kotlin layer
    can do the exchange via OkHttp.
    """
    all_state = _load_oauth_state()
    if state not in all_state:
        raise ValueError("Invalid or expired OAuth state")

    oauth_data = all_state.pop(state)
    _save_oauth_state(all_state)  # remove used state

    client_id, client_secret = _load_client_credentials()

    pending = {
        "code": code,
        "code_verifier": oauth_data["code_verifier"],
        "redirect_uri": oauth_data["redirect_uri"],
        "client_id": client_id,
        "client_secret": client_secret,
    }

    config_dir = _get_config_dir()
    pending_path = os.path.join(config_dir, ".oauth_pending.json")
    with open(pending_path, "w") as f:
        json.dump(pending, f)

    return True


def get_pending_exchange():
    """Return pending OAuth exchange data, or None."""
    config_dir = _get_config_dir()
    pending_path = os.path.join(config_dir, ".oauth_pending.json")
    if not os.path.exists(pending_path):
        return None
    try:
        with open(pending_path) as f:
            return json.load(f)
    except Exception:
        return None


def clear_pending_exchange():
    """Remove the pending OAuth exchange file."""
    config_dir = _get_config_dir()
    pending_path = os.path.join(config_dir, ".oauth_pending.json")
    if os.path.exists(pending_path):
        os.remove(pending_path)


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
