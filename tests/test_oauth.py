"""
Unit tests for Google OAuth service (auth_gmail.py).

Particular weight given to robustness of get_gmail_service() and search_emails()
across all token states: missing, valid, expired+refreshable, expired+no-refresh.
get_gmail_service() is strictly non-interactive — it must raise OAuthRequiredError
(never block on a browser flow) when interactive auth is needed; the interactive
flow itself lives in start_auth_flow(), which runs in a background thread.
All Google API calls are mocked — these tests verify our logic, not Google's SDKs.
"""
import base64
import json
import threading
import pytest
from unittest.mock import patch, MagicMock, mock_open, call
from google.auth.exceptions import RefreshError

import auth_gmail
from auth_gmail import (
    get_gmail_service, search_emails, extract_body,
    start_auth_flow, OAuthRequiredError,
)


# ── Helpers ───────────────────────────────────────────────────────────────────

def _make_valid_creds(expired=False, has_refresh_token=True):
    """Create a mock Credentials object."""
    creds = MagicMock()
    creds.valid = not expired
    creds.expired = expired
    creds.refresh_token = "refresh-token-xyz" if has_refresh_token else None
    return creds


def _b64(text: str) -> str:
    """URL-safe base64 encode a string."""
    return base64.urlsafe_b64encode(text.encode()).decode()


def _make_gmail_message(gmail_id: str, sender: str, subject: str, body_text: str) -> dict:
    """Build a mock full Gmail message dict (as returned by messages.get)."""
    return {
        "id": gmail_id,
        "snippet": body_text[:100],
        "payload": {
            "headers": [
                {"name": "From", "value": sender},
                {"name": "Subject", "value": subject},
                {"name": "Date", "value": "Thu, 01 Mar 2026 10:00:00 +0500"},
            ],
            "body": {"data": _b64(body_text)},
            "parts": [],
        },
    }


# ── get_gmail_service() ───────────────────────────────────────────────────────

class TestGetGmailService:
    def test_raises_oauth_required_when_no_token(self):
        """No token.json → OAuthRequiredError (never a blocking browser flow)."""
        with (
            patch("auth_gmail.os.path.exists", return_value=False),
            pytest.raises(OAuthRequiredError),
        ):
            get_gmail_service()

    def test_returns_service_when_token_is_valid(self):
        """Valid cached token → service built without any network calls."""
        creds = _make_valid_creds(expired=False)
        mock_service = MagicMock()

        with (
            patch("auth_gmail.os.path.exists", return_value=True),
            patch("auth_gmail.Credentials.from_authorized_user_file", return_value=creds),
            patch("auth_gmail.build", return_value=mock_service) as mock_build,
        ):
            service = get_gmail_service()

        assert service is mock_service
        mock_build.assert_called_once_with("gmail", "v1", credentials=creds)

    def test_refreshes_expired_token_with_refresh_token(self):
        """Expired token + refresh_token → calls creds.refresh(), saves new token."""
        creds = _make_valid_creds(expired=True, has_refresh_token=True)
        mock_service = MagicMock()

        with (
            patch("auth_gmail.os.path.exists", return_value=True),
            patch("auth_gmail.Credentials.from_authorized_user_file", return_value=creds),
            patch("auth_gmail.Request") as mock_request_cls,
            patch("auth_gmail.build", return_value=mock_service),
            patch("builtins.open", mock_open()),
            patch("auth_gmail.os.replace"),
            patch("auth_gmail.os.chmod"),
        ):
            service = get_gmail_service()

        creds.refresh.assert_called_once_with(mock_request_cls())

    def test_saves_token_after_refresh(self):
        """After refreshing, the new token is persisted to disk."""
        creds = _make_valid_creds(expired=True, has_refresh_token=True)
        creds.to_json.return_value = '{"token": "new"}'
        m = mock_open()

        with (
            patch("auth_gmail.os.path.exists", return_value=True),
            patch("auth_gmail.Credentials.from_authorized_user_file", return_value=creds),
            patch("auth_gmail.Request"),
            patch("auth_gmail.build", return_value=MagicMock()),
            patch("builtins.open", m),
            patch("auth_gmail.os.replace"),
            patch("auth_gmail.os.chmod"),
        ):
            get_gmail_service()

        m.assert_called()
        handle = m()
        handle.write.assert_called_once_with('{"token": "new"}')

    def test_raises_oauth_required_when_expired_token_has_no_refresh_token(self):
        """Expired token with no refresh_token → OAuthRequiredError, no browser flow."""
        creds = _make_valid_creds(expired=True, has_refresh_token=False)

        with (
            patch("auth_gmail.os.path.exists", return_value=True),
            patch("auth_gmail.Credentials.from_authorized_user_file", return_value=creds),
            patch("auth_gmail.InstalledAppFlow") as mock_flow_cls,
            pytest.raises(OAuthRequiredError),
        ):
            get_gmail_service()

        mock_flow_cls.from_client_secrets_file.assert_not_called()

    def test_build_called_with_gmail_v1_scope(self):
        """Service is always built with gmail v1."""
        creds = _make_valid_creds()

        with (
            patch("auth_gmail.os.path.exists", return_value=True),
            patch("auth_gmail.Credentials.from_authorized_user_file", return_value=creds),
            patch("auth_gmail.build", return_value=MagicMock()) as mock_build,
        ):
            get_gmail_service()

        args = mock_build.call_args
        assert args[0][0] == "gmail"
        assert args[0][1] == "v1"

    def test_refresh_error_deletes_stale_token_and_raises_oauth_required(self):
        """RefreshError (revoked grant) → stale token removed + OAuthRequiredError."""
        creds = _make_valid_creds(expired=True, has_refresh_token=True)
        creds.refresh.side_effect = RefreshError("Token has been revoked")

        with (
            patch("auth_gmail.os.path.exists", return_value=True),
            patch("auth_gmail.Credentials.from_authorized_user_file", return_value=creds),
            patch("auth_gmail.Request"),
            patch("auth_gmail.os.remove") as mock_remove,
            pytest.raises(OAuthRequiredError),
        ):
            get_gmail_service()

        mock_remove.assert_called_once()

    def test_corrupted_token_file_raises_oauth_required(self):
        """Malformed token.json (bad JSON) is treated as missing — re-auth needed."""
        with (
            patch("auth_gmail.os.path.exists", return_value=True),
            patch(
                "auth_gmail.Credentials.from_authorized_user_file",
                side_effect=ValueError("Invalid token file"),
            ),
            patch("auth_gmail.os.remove"),
            pytest.raises(OAuthRequiredError),
        ):
            get_gmail_service()


# ── start_auth_flow() ─────────────────────────────────────────────────────────

CONSENT_URL = "https://accounts.google.com/o/oauth2/auth?client_id=x&state=abc"


@pytest.fixture
def reset_flow_state():
    """Isolate start_auth_flow()'s module-level pending-flow state per test."""
    auth_gmail._flow_thread = None
    auth_gmail._flow_auth_url = None
    yield
    t = auth_gmail._flow_thread
    if t and t.is_alive():
        t.join(timeout=5)
    auth_gmail._flow_thread = None
    auth_gmail._flow_auth_url = None


def _make_mock_flow():
    flow = MagicMock()
    flow.authorization_url.return_value = (CONSENT_URL, "abc")
    return flow


def _make_mock_server(release_event):
    """A fake wsgiref server whose handle_request blocks until released."""
    server = MagicMock()
    server.handle_request.side_effect = lambda: release_event.wait(timeout=5)
    return server


class TestStartAuthFlow:
    def test_raises_file_not_found_when_credentials_missing(self, reset_flow_state):
        with (
            patch("auth_gmail.os.path.exists", return_value=False),
            pytest.raises(FileNotFoundError, match="credentials.json"),
        ):
            start_auth_flow()

    def test_returns_consent_url_without_blocking(self, reset_flow_state):
        """start_auth_flow returns Google's consent URL while the redirect
        listener waits in a background thread."""
        release = threading.Event()
        mock_flow = _make_mock_flow()

        with (
            patch("auth_gmail.os.path.exists", return_value=True),
            patch("auth_gmail.InstalledAppFlow.from_client_secrets_file", return_value=mock_flow),
            patch("auth_gmail.wsgiref.simple_server.make_server",
                  return_value=_make_mock_server(release)),
        ):
            url = start_auth_flow()

        assert url == CONSENT_URL
        assert auth_gmail._flow_thread.is_alive()  # listener still waiting
        release.set()

    def test_pending_flow_is_reused_not_restarted(self, reset_flow_state):
        """A second call while a flow is pending returns the same URL and does
        NOT rebind port 8090 (the original 'Address already in use' bug)."""
        release = threading.Event()
        mock_flow = _make_mock_flow()

        with (
            patch("auth_gmail.os.path.exists", return_value=True),
            patch("auth_gmail.InstalledAppFlow.from_client_secrets_file",
                  return_value=mock_flow) as mock_from_secrets,
            patch("auth_gmail.wsgiref.simple_server.make_server",
                  return_value=_make_mock_server(release)) as mock_make_server,
        ):
            first = start_auth_flow()
            second = start_auth_flow()

        assert first == second == CONSENT_URL
        mock_from_secrets.assert_called_once()
        mock_make_server.assert_called_once()
        release.set()

    def test_completed_redirect_exchanges_code_and_saves_token(self, reset_flow_state):
        """When Google redirects back, the code is exchanged and token.json saved."""
        mock_flow = _make_mock_flow()

        def fake_make_server(host, port, app, handler_class=None):
            server = MagicMock()

            def handle_one_request():
                # Simulate Google redirecting the browser to localhost:8090.
                environ = {
                    "wsgi.url_scheme": "http",
                    "HTTP_HOST": f"{host}:{port}",
                    "PATH_INFO": "/",
                    "QUERY_STRING": "state=abc&code=auth-code-123",
                }
                app(environ, lambda status, headers: None)

            server.handle_request.side_effect = handle_one_request
            return server

        with (
            patch("auth_gmail.os.path.exists", return_value=True),
            patch("auth_gmail.InstalledAppFlow.from_client_secrets_file", return_value=mock_flow),
            patch("auth_gmail.wsgiref.simple_server.make_server", side_effect=fake_make_server),
            patch("auth_gmail._save_token") as mock_save,
        ):
            start_auth_flow()
            auth_gmail._flow_thread.join(timeout=5)

        auth_response = mock_flow.fetch_token.call_args[1]["authorization_response"]
        assert auth_response.startswith("https://")
        assert "code=auth-code-123" in auth_response
        mock_save.assert_called_once_with(mock_flow.credentials)

    def test_timed_out_flow_never_exchanges_token(self, reset_flow_state):
        """If the user never completes sign-in, no token is written and the
        listener socket is closed so a fresh flow can start later."""
        mock_flow = _make_mock_flow()
        server = MagicMock()
        server.handle_request.side_effect = lambda: None  # timeout: no request came

        with (
            patch("auth_gmail.os.path.exists", return_value=True),
            patch("auth_gmail.InstalledAppFlow.from_client_secrets_file", return_value=mock_flow),
            patch("auth_gmail.wsgiref.simple_server.make_server", return_value=server),
            patch("auth_gmail._save_token") as mock_save,
        ):
            start_auth_flow()
            auth_gmail._flow_thread.join(timeout=5)

        mock_flow.fetch_token.assert_not_called()
        mock_save.assert_not_called()
        server.server_close.assert_called_once()


# ── search_emails() ───────────────────────────────────────────────────────────

class TestSearchEmails:
    def test_returns_empty_list_when_no_messages(self):
        service = MagicMock()
        service.users().messages().list().execute.return_value = {"messages": []}

        result = search_emails(service, "from:alerts.pk@sc.com")
        assert result == []

    def test_returns_empty_list_when_messages_key_absent(self):
        """API sometimes returns {} with no 'messages' key when there are no results."""
        service = MagicMock()
        service.users().messages().list().execute.return_value = {}

        result = search_emails(service, "from:alerts.pk@sc.com")
        assert result == []

    def test_returns_one_email_per_message(self):
        service = MagicMock()
        msg = _make_gmail_message("id1", "alerts.pk@sc.com", "Alert", "PKR 1,500 paid")
        service.users().messages().list().execute.return_value = {"messages": [{"id": "id1"}]}
        service.users().messages().get().execute.return_value = msg

        result = search_emails(service, "q", max_results=10)
        assert len(result) == 1

    def test_email_dict_has_required_keys(self):
        service = MagicMock()
        msg = _make_gmail_message("id2", "alerts.pk@sc.com", "Alert", "PKR 500 paid")
        service.users().messages().list().execute.return_value = {"messages": [{"id": "id2"}]}
        service.users().messages().get().execute.return_value = msg

        result = search_emails(service, "q")
        email = result[0]
        for key in ("gmail_id", "sender", "subject", "date", "body", "snippet"):
            assert key in email, f"Missing key: {key}"

    def test_gmail_id_matches_message_id(self):
        service = MagicMock()
        msg = _make_gmail_message("abc123", "alerts.pk@sc.com", "S", "body")
        service.users().messages().list().execute.return_value = {"messages": [{"id": "abc123"}]}
        service.users().messages().get().execute.return_value = msg

        result = search_emails(service, "q")
        assert result[0]["gmail_id"] == "abc123"

    def test_body_is_decoded_from_base64(self):
        service = MagicMock()
        original_text = "SCBPL: PKR 1,500 have been paid at KFC"
        msg = _make_gmail_message("id3", "alerts.pk@sc.com", "Alert", original_text)
        service.users().messages().list().execute.return_value = {"messages": [{"id": "id3"}]}
        service.users().messages().get().execute.return_value = msg

        result = search_emails(service, "q")
        assert original_text in result[0]["body"]

    def test_malformed_date_header_does_not_crash(self):
        """Bad date headers should be handled gracefully."""
        service = MagicMock()
        msg = _make_gmail_message("id4", "alerts.pk@sc.com", "S", "body")
        # Corrupt the date header
        for h in msg["payload"]["headers"]:
            if h["name"] == "Date":
                h["value"] = "not-a-date"
        service.users().messages().list().execute.return_value = {"messages": [{"id": "id4"}]}
        service.users().messages().get().execute.return_value = msg

        result = search_emails(service, "q")
        assert len(result) == 1
        assert result[0]["date"] == "not-a-date"  # falls back to raw string

    def test_max_results_forwarded_to_api(self):
        service = MagicMock()
        service.users().messages().list().execute.return_value = {}

        search_emails(service, "from:test@example.com", max_results=500)

        list_call_kwargs = service.users().messages().list.call_args[1]
        assert list_call_kwargs["maxResults"] == 500


# ── extract_body() ────────────────────────────────────────────────────────────

class TestExtractBody:
    def test_single_part_message_returns_decoded_text(self):
        payload = {"body": {"data": _b64("Hello from bank")}, "parts": []}
        assert extract_body(payload) == "Hello from bank"

    def test_multipart_returns_text_plain_part(self):
        payload = {
            "body": {},
            "parts": [
                {"mimeType": "text/plain", "body": {"data": _b64("plain text body")}, "parts": []},
                {"mimeType": "text/html", "body": {"data": _b64("<p>html body</p>")}, "parts": []},
            ],
        }
        assert extract_body(payload) == "plain text body"

    def test_multipart_falls_back_to_html_when_no_plain(self):
        payload = {
            "body": {},
            "parts": [
                {"mimeType": "text/html", "body": {"data": _b64("<p>html only</p>")}, "parts": []},
            ],
        }
        assert extract_body(payload) == "<p>html only</p>"

    def test_nested_parts_are_searched(self):
        """Emails with nested multipart/alternative within multipart/mixed."""
        payload = {
            "body": {},
            "parts": [
                {
                    "mimeType": "multipart/alternative",
                    "body": {},
                    "parts": [
                        {"mimeType": "text/plain", "body": {"data": _b64("nested plain")}, "parts": []},
                    ],
                }
            ],
        }
        assert extract_body(payload) == "nested plain"

    def test_returns_empty_string_when_no_data(self):
        payload = {"body": {}, "parts": []}
        assert extract_body(payload) == ""

    def test_returns_empty_string_for_empty_body_data(self):
        payload = {"body": {"data": ""}, "parts": []}
        # Empty base64 data — should return empty or not crash
        result = extract_body(payload)
        assert isinstance(result, str)

    def test_utf8_encoded_body_decoded_correctly(self):
        urdu_text = "آپ کا بیلنس"
        payload = {"body": {"data": _b64(urdu_text)}, "parts": []}
        assert extract_body(payload) == urdu_text

    def test_prefers_single_part_over_multipart(self):
        """If top-level body.data exists, it takes priority over parts."""
        payload = {
            "body": {"data": _b64("top level")},
            "parts": [
                {"mimeType": "text/plain", "body": {"data": _b64("part level")}, "parts": []},
            ],
        }
        assert extract_body(payload) == "top level"
