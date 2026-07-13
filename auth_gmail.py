import os
import json
import base64
import threading
import wsgiref.simple_server
import wsgiref.util
from google.oauth2.credentials import Credentials
from google.auth.exceptions import RefreshError
from google_auth_oauthlib.flow import InstalledAppFlow
from google.auth.transport.requests import Request
from googleapiclient.discovery import build

SCOPES = ["https://www.googleapis.com/auth/gmail.readonly"]
TOKEN_PATH = os.path.join(os.path.dirname(__file__), "token.json")
CREDS_PATH = os.path.join(os.path.dirname(__file__), "credentials.json")

OAUTH_PORT = 8090
_FLOW_TIMEOUT_SEC = 300

_flow_lock = threading.Lock()
_flow_thread = None
_flow_auth_url = None


class OAuthRequiredError(Exception):
    """No valid Gmail token — the interactive OAuth flow must be completed."""


def _delete_token():
    """Best-effort removal of the cached token file."""
    try:
        os.remove(TOKEN_PATH)
    except FileNotFoundError:
        pass


def _save_token(creds) -> None:
    """Atomically persist credentials to TOKEN_PATH with owner-only permissions."""
    tmp_path = TOKEN_PATH + ".tmp"
    try:
        with open(tmp_path, "w") as f:
            f.write(creds.to_json())
        # 0600 — the file holds a Gmail refresh token; don't expose to other users.
        try:
            os.chmod(tmp_path, 0o600)
        except OSError:
            pass  # Non-POSIX filesystems (e.g. Android external storage) may reject chmod.
        os.replace(tmp_path, TOKEN_PATH)
    except Exception:
        try:
            os.remove(tmp_path)
        except FileNotFoundError:
            pass
        raise


class _RedirectCatcher:
    """Minimal WSGI app that captures the OAuth redirect request URI."""

    def __init__(self):
        self.last_request_uri = None

    def __call__(self, environ, start_response):
        start_response("200 OK", [("Content-Type", "text/plain; charset=utf-8")])
        self.last_request_uri = wsgiref.util.request_uri(environ)
        return [b"Gmail connected. You can close this tab and return to FinTrack."]


class _QuietHandler(wsgiref.simple_server.WSGIRequestHandler):
    def log_message(self, format, *args):
        pass


def start_auth_flow():
    """Start (or reuse) the interactive OAuth flow without blocking.

    Returns the Google consent URL for the frontend to open. A daemon
    thread listens on localhost:OAUTH_PORT for the redirect and writes
    token.json when the user finishes signing in. Calling again while a
    flow is pending returns the same URL instead of rebinding the port.
    """
    global _flow_thread, _flow_auth_url
    with _flow_lock:
        if _flow_thread and _flow_thread.is_alive():
            return _flow_auth_url

        if not os.path.exists(CREDS_PATH):
            raise FileNotFoundError(
                f"Missing {CREDS_PATH}. Download OAuth credentials from Google Cloud Console "
                f"and save as credentials.json in the fintrack directory."
            )

        flow = InstalledAppFlow.from_client_secrets_file(CREDS_PATH, SCOPES)
        catcher = _RedirectCatcher()
        server = wsgiref.simple_server.make_server(
            "localhost", OAUTH_PORT, catcher, handler_class=_QuietHandler
        )
        flow.redirect_uri = f"http://localhost:{OAUTH_PORT}/"
        auth_url, _ = flow.authorization_url(access_type="offline", prompt="consent")

        def _wait_for_redirect():
            try:
                server.timeout = _FLOW_TIMEOUT_SEC
                server.handle_request()  # blocks until redirect or timeout
                if catcher.last_request_uri:
                    # oauthlib insists on https for the response URL comparison.
                    flow.fetch_token(
                        authorization_response=catcher.last_request_uri.replace("http:", "https:", 1)
                    )
                    _save_token(flow.credentials)
            except Exception:
                pass  # abandoned/denied flow — the next attempt starts fresh
            finally:
                server.server_close()

        _flow_thread = threading.Thread(target=_wait_for_redirect, daemon=True, name="gmail-oauth")
        _flow_auth_url = auth_url
        _flow_thread.start()
        return auth_url


def get_gmail_service():
    """Get authenticated Gmail API service.

    Non-interactive: uses the cached token.json (with silent refresh).
    Raises OAuthRequiredError when the user must complete the OAuth flow —
    callers surface this to the frontend, which opens start_auth_flow()'s URL.
    """
    creds = None

    if os.path.exists(TOKEN_PATH):
        try:
            creds = Credentials.from_authorized_user_file(TOKEN_PATH, SCOPES)
        except (ValueError, json.JSONDecodeError):
            # Corrupted token file — drop it and force re-auth.
            _delete_token()
            creds = None

    if not creds or not creds.valid:
        refreshed = False
        if creds and creds.expired and creds.refresh_token:
            try:
                creds.refresh(Request())
                refreshed = True
            except RefreshError:
                # Refresh token revoked or expired — drop stale token and re-auth.
                _delete_token()
                creds = None

        if not refreshed:
            raise OAuthRequiredError(
                "Gmail not connected. Complete the Google sign-in to sync."
            )

        _save_token(creds)

    return build("gmail", "v1", credentials=creds)


def get_account_email():
    """Return the Gmail address for the cached token, or None.

    Non-interactive: only uses an existing token.json and silent refresh.
    Never triggers the OAuth browser flow (safe to call from a GET endpoint).
    """
    if not os.path.exists(TOKEN_PATH):
        return None
    try:
        creds = Credentials.from_authorized_user_file(TOKEN_PATH, SCOPES)
        if not creds.valid:
            if creds.expired and creds.refresh_token:
                creds.refresh(Request())
                _save_token(creds)
            else:
                return None
        service = build("gmail", "v1", credentials=creds)
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
