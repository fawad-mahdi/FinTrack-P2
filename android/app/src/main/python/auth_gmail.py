import base64
from google.oauth2.credentials import Credentials
from googleapiclient.discovery import build

SCOPES = ["https://www.googleapis.com/auth/gmail.readonly"]


class OAuthRequiredError(Exception):
    """Raised when OAuth authorization is needed (no valid token on Android)."""
    pass


def _get_broker_access_token():
    """Fetch a short-lived access token from the native GmailTokenBroker.

    All long-lived OAuth material (refresh token, client secret) lives in
    Android Keystore-backed encrypted storage on the Kotlin side; Python
    only ever receives an already-refreshed access token. The broker also
    performs any needed refresh through the device's active network, which
    works from Chaquopy threads on Android 14+.
    """
    from com.chaquo.python import Python as ChaquoPython
    from com.fintrack.pk.utils import GmailTokenBroker

    context = ChaquoPython.getPlatform().getApplication()
    token = GmailTokenBroker.getAccessToken(context)
    if token is None:
        return None
    return str(token)


def get_gmail_service():
    """Get authenticated Gmail API service.

    Tokens are created by the native Kotlin OAuth flow (AppAuth +
    OAuthCallbackActivity) and served through GmailTokenBroker. If no valid
    token is available, raises OAuthRequiredError so the server can tell
    the frontend to trigger the native flow.
    """
    try:
        access_token = _get_broker_access_token()
    except Exception as e:
        raise OAuthRequiredError(
            "Gmail not connected. Please authenticate via the app. ({})".format(e)
        )

    if not access_token:
        raise OAuthRequiredError(
            "Gmail not connected. Please authenticate via the app."
        )

    creds = Credentials(token=access_token, scopes=SCOPES)
    return build("gmail", "v1", credentials=creds)


def get_account_email():
    """Return the Gmail address for the cached token, or None.

    Non-interactive: on Android get_gmail_service() never opens a browser —
    it raises OAuthRequiredError when no valid token exists, which we
    swallow here (safe to call from a GET endpoint).
    """
    try:
        service = get_gmail_service()
        return service.users().getProfile(userId="me").execute().get("emailAddress")
    except Exception:
        return None


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
