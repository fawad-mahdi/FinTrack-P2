"""
Tests for Gmail sync endpoint: POST /api/sync and GET /api/sync/status.

Gmail API calls are mocked — we test the sync orchestration logic, date-range
clamping, duplicate prevention, and error handling without hitting Google.
"""
import pytest
from unittest.mock import patch, MagicMock
from datetime import date, timedelta

from auth_gmail import OAuthRequiredError


def _make_gmail_email(gmail_id: str, sender: str, body: str, subject: str = "Alert") -> dict:
    """Helper: build a mock email dict as returned by search_emails."""
    return {
        "gmail_id": gmail_id,
        "sender": sender,
        "subject": subject,
        "date": "2026-03-01T10:00:00",
        "body": body,
        "snippet": body[:100],
    }


SCB_DEBIT_BODY = (
    "SCBPL: PKR 1,500.00 have been paid at KFC DHA using Debit Card on 01-03-26"
)
MEEZAN_DEBIT_BODY = (
    "PKR 2,000 sent to John Doe on account with the following details"
)


# ── GET /api/sync/status ──────────────────────────────────────────────────────

class TestSyncStatus:
    async def test_returns_no_sync_sentinel_initially(self, authed_client):
        resp = await authed_client.get("/api/sync/status")
        assert resp.status_code == 200
        assert resp.json().get("message") == "No sync yet"

    async def test_records_last_sync_after_successful_sync(self, authed_client):
        mock_service = MagicMock()
        with (
            patch("server.get_gmail_service", return_value=mock_service),
            patch("server.search_emails", return_value=[]),
        ):
            await authed_client.post("/api/sync", json={})

        resp = await authed_client.get("/api/sync/status")
        assert resp.status_code == 200
        data = resp.json()
        # Should now return a sync_log row, not the sentinel
        assert "message" not in data


# ── Gmail OAuth: /api/sync auth-required + POST /api/auth/gmail ───────────────

class TestGmailAuthFlow:
    async def test_sync_returns_428_when_gmail_not_connected(self, authed_client):
        """No/expired token → 428 so the frontend can launch the OAuth flow
        (previously this blocked the request inside run_local_server)."""
        with patch("server.get_gmail_service",
                   side_effect=OAuthRequiredError("Gmail not connected")):
            resp = await authed_client.post("/api/sync", json={})

        assert resp.status_code == 428
        assert "Gmail not connected" in resp.json()["detail"]

    async def test_auth_gmail_returns_consent_url(self, authed_client):
        url = "https://accounts.google.com/o/oauth2/auth?client_id=x"
        with patch("server.start_auth_flow", return_value=url):
            resp = await authed_client.post("/api/auth/gmail")

        assert resp.status_code == 200
        assert resp.json() == {"auth_url": url}

    async def test_auth_gmail_requires_pin_auth(self, client):
        resp = await client.post("/api/auth/gmail")
        assert resp.status_code == 401

    async def test_auth_gmail_missing_credentials_returns_500(self, authed_client):
        with patch("server.start_auth_flow",
                   side_effect=FileNotFoundError("Missing credentials.json")):
            resp = await authed_client.post("/api/auth/gmail")

        assert resp.status_code == 500
        assert "credentials.json" in resp.json()["detail"]

    async def test_auth_gmail_port_conflict_returns_500(self, authed_client):
        with patch("server.start_auth_flow",
                   side_effect=OSError("[Errno 48] Address already in use")):
            resp = await authed_client.post("/api/auth/gmail")

        assert resp.status_code == 500
        assert "Could not start Gmail sign-in" in resp.json()["detail"]


# ── POST /api/sync ────────────────────────────────────────────────────────────

class TestSyncEndpoint:
    async def test_empty_gmail_result_returns_zeroed_counts(self, authed_client):
        mock_service = MagicMock()
        with (
            patch("server.get_gmail_service", return_value=mock_service),
            patch("server.search_emails", return_value=[]),
        ):
            resp = await authed_client.post("/api/sync", json={})

        assert resp.status_code == 200
        data = resp.json()
        assert data["emails_found"] == 0
        assert data["parsed_ok"] == 0
        assert data["pending_review"] == 0
        assert data["skipped_parse"] == 0
        assert data["skipped_duplicate"] == 0

    async def test_parses_scb_debit_email(self, authed_client):
        emails = [_make_gmail_email("gmail1", "alerts.pk@sc.com", SCB_DEBIT_BODY)]
        mock_service = MagicMock()
        with (
            patch("server.get_gmail_service", return_value=mock_service),
            patch("server.search_emails", return_value=emails),
        ):
            resp = await authed_client.post("/api/sync", json={})

        assert resp.status_code == 200
        data = resp.json()
        assert data["emails_found"] == 1
        assert data["parsed_ok"] == 1
        assert data["skipped_parse"] == 0

    async def test_parses_meezan_debit_email(self, authed_client):
        emails = [_make_gmail_email("gmail2", "no-reply@meezanbank.com", MEEZAN_DEBIT_BODY)]
        mock_service = MagicMock()
        with (
            patch("server.get_gmail_service", return_value=mock_service),
            patch("server.search_emails", return_value=emails),
        ):
            resp = await authed_client.post("/api/sync", json={})

        assert resp.status_code == 200
        assert resp.json()["emails_found"] == 1

    async def test_duplicate_email_increments_skipped_duplicate(self, authed_client):
        emails = [_make_gmail_email("dup-id-1", "alerts.pk@sc.com", SCB_DEBIT_BODY)]
        mock_service = MagicMock()
        with (
            patch("server.get_gmail_service", return_value=mock_service),
            patch("server.search_emails", return_value=emails),
        ):
            # First sync — inserted
            await authed_client.post("/api/sync", json={})
            # Second sync — duplicate
            resp = await authed_client.post("/api/sync", json={})

        data = resp.json()
        assert data["skipped_duplicate"] == 1
        assert data["parsed_ok"] == 0

    async def test_unparseable_email_increments_skipped_parse(self, authed_client):
        # Email from unknown sender — parse_email returns None
        emails = [_make_gmail_email("gmail3", "unknown@bank.com", "no transaction data")]
        mock_service = MagicMock()
        with (
            patch("server.get_gmail_service", return_value=mock_service),
            patch("server.search_emails", return_value=emails),
        ):
            resp = await authed_client.post("/api/sync", json={})

        assert resp.json()["skipped_parse"] == 1

    async def test_email_with_empty_body_increments_skipped_parse(self, authed_client):
        emails = [{"gmail_id": "g4", "sender": "alerts.pk@sc.com", "subject": "A",
                   "date": "", "body": "", "snippet": ""}]
        mock_service = MagicMock()
        with (
            patch("server.get_gmail_service", return_value=mock_service),
            patch("server.search_emails", return_value=emails),
        ):
            resp = await authed_client.post("/api/sync", json={})

        assert resp.json()["skipped_parse"] == 1

    async def test_mixed_emails_correct_counts(self, authed_client):
        emails = [
            _make_gmail_email("g5", "alerts.pk@sc.com", SCB_DEBIT_BODY),
            _make_gmail_email("g6", "unknown@bank.com", "no data"),
        ]
        mock_service = MagicMock()
        with (
            patch("server.get_gmail_service", return_value=mock_service),
            patch("server.search_emails", return_value=emails),
        ):
            resp = await authed_client.post("/api/sync", json={})

        data = resp.json()
        assert data["emails_found"] == 2
        assert data["parsed_ok"] == 1
        assert data["skipped_parse"] == 1

    async def test_response_includes_date_from_and_date_to(self, authed_client):
        mock_service = MagicMock()
        with (
            patch("server.get_gmail_service", return_value=mock_service),
            patch("server.search_emails", return_value=[]),
        ):
            date_from = (date.today() - timedelta(days=20)).isoformat()
            date_to = (date.today() - timedelta(days=5)).isoformat()
            resp = await authed_client.post(
                "/api/sync", json={"date_from": date_from, "date_to": date_to}
            )

        data = resp.json()
        assert data["date_from"] == date_from
        assert data["date_to"] == date_to

    async def test_sync_works_with_no_body(self, authed_client):
        """Sync with empty/missing JSON body should not crash."""
        mock_service = MagicMock()
        with (
            patch("server.get_gmail_service", return_value=mock_service),
            patch("server.search_emails", return_value=[]),
        ):
            resp = await authed_client.post("/api/sync")
        assert resp.status_code == 200

    async def test_date_from_clamped_to_90_days(self, authed_client):
        """Dates older than 90 days are clamped to the 90-day limit."""
        ancient_date = (date.today() - timedelta(days=200)).isoformat()
        expected_min = (date.today() - timedelta(days=90)).isoformat()

        mock_service = MagicMock()
        with (
            patch("server.get_gmail_service", return_value=mock_service),
            patch("server.search_emails", return_value=[]),
        ):
            resp = await authed_client.post("/api/sync", json={"date_from": ancient_date})

        data = resp.json()
        assert data["date_from"] >= expected_min

    async def test_invalid_date_format_is_ignored(self, authed_client):
        """Malformed date strings don't crash the endpoint."""
        mock_service = MagicMock()
        with (
            patch("server.get_gmail_service", return_value=mock_service),
            patch("server.search_emails", return_value=[]),
        ):
            resp = await authed_client.post(
                "/api/sync", json={"date_from": "not-a-date", "date_to": "also-wrong"}
            )
        assert resp.status_code == 200


# ── Sync error paths ──────────────────────────────────────────────────────────

class TestSyncErrorHandling:
    async def test_missing_credentials_json_returns_500(self, authed_client):
        with patch("server.get_gmail_service", side_effect=FileNotFoundError("Missing credentials.json")):
            resp = await authed_client.post("/api/sync", json={})
        assert resp.status_code == 500
        assert "credentials" in resp.json()["detail"].lower() or "missing" in resp.json()["detail"].lower()

    async def test_gmail_auth_failure_returns_500(self, authed_client):
        with patch("server.get_gmail_service", side_effect=Exception("token expired")):
            resp = await authed_client.post("/api/sync", json={})
        assert resp.status_code == 500
        assert "Gmail auth failed" in resp.json()["detail"]

    async def test_gmail_search_failure_returns_500(self, authed_client):
        mock_service = MagicMock()
        with (
            patch("server.get_gmail_service", return_value=mock_service),
            patch("server.search_emails", side_effect=Exception("API rate limit")),
        ):
            resp = await authed_client.post("/api/sync", json={})
        assert resp.status_code == 500
        assert "Gmail search failed" in resp.json()["detail"]
