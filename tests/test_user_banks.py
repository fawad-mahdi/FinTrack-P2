"""
Tests for user-added bank senders: /api/banks CRUD and sync integration.
"""
from unittest.mock import MagicMock, patch


def _email(gmail_id: str, sender: str, body: str) -> dict:
    return {
        "gmail_id": gmail_id,
        "sender": sender,
        "subject": "Alert",
        "date": "2026-03-01T10:00:00",
        "body": body,
        "snippet": body[:100],
    }


class TestBanksAuth:
    async def test_endpoints_require_auth(self, client):
        assert (await client.get("/api/banks")).status_code == 401
        assert (await client.post("/api/banks", json={"bank": "X", "address": "x.com"})).status_code == 401
        assert (await client.delete("/api/banks/1")).status_code == 401


class TestBanksCrud:
    async def test_list_includes_builtin_registry(self, authed_client):
        resp = await authed_client.get("/api/banks")
        assert resp.status_code == 200
        data = resp.json()
        assert any(e["bank"] == "SCB" for e in data["builtin"])
        assert data["custom"] == []

    async def test_add_list_delete_roundtrip(self, authed_client):
        resp = await authed_client.post(
            "/api/banks", json={"bank": "TestBank", "address": "Alerts@TestBank.com.pk "}
        )
        assert resp.status_code == 200
        row = resp.json()
        assert row["bank"] == "TestBank"
        assert row["address"] == "alerts@testbank.com.pk"  # trimmed + lowercased
        assert row["enabled"] == 1

        resp = await authed_client.get("/api/banks")
        assert [c["address"] for c in resp.json()["custom"]] == ["alerts@testbank.com.pk"]

        resp = await authed_client.delete(f"/api/banks/{row['id']}")
        assert resp.status_code == 200
        resp = await authed_client.get("/api/banks")
        assert resp.json()["custom"] == []

    async def test_bare_domain_accepted(self, authed_client):
        resp = await authed_client.post(
            "/api/banks", json={"bank": "TestBank", "address": "testbank.com.pk"}
        )
        assert resp.status_code == 200

    async def test_missing_fields_rejected(self, authed_client):
        assert (await authed_client.post("/api/banks", json={"bank": "X"})).status_code == 400
        assert (await authed_client.post("/api/banks", json={"address": "x.com"})).status_code == 400

    async def test_malformed_address_rejected(self, authed_client):
        for bad in ["not a domain", "nodot", "@x.com", "a@b@c.com", "http://x.com"]:
            resp = await authed_client.post("/api/banks", json={"bank": "X", "address": bad})
            assert resp.status_code == 400, f"expected 400 for {bad!r}"

    async def test_freemail_domain_blocked(self, authed_client):
        for bad in ["gmail.com", "someone@gmail.com", "yahoo.com", "user@hotmail.com"]:
            resp = await authed_client.post("/api/banks", json={"bank": "X", "address": bad})
            assert resp.status_code == 400
            assert "personal email provider" in resp.json()["detail"]

    async def test_builtin_covered_address_rejected(self, authed_client):
        for covered in ["hbl.com", "alerts@hbl.com", "sub.meezanbank.com"]:
            resp = await authed_client.post("/api/banks", json={"bank": "X", "address": covered})
            assert resp.status_code == 400
            assert "Already covered by" in resp.json()["detail"]

    async def test_duplicate_custom_address_rejected(self, authed_client):
        resp = await authed_client.post(
            "/api/banks", json={"bank": "TestBank", "address": "alerts@testbank.com.pk"}
        )
        assert resp.status_code == 200
        # Exact repeat and domain covered by the existing custom sender's bank
        resp = await authed_client.post(
            "/api/banks", json={"bank": "Other", "address": "alerts@testbank.com.pk"}
        )
        assert resp.status_code == 400

    async def test_overlong_bank_name_rejected(self, authed_client):
        resp = await authed_client.post(
            "/api/banks", json={"bank": "X" * 51, "address": "testbank.com.pk"}
        )
        assert resp.status_code == 400


class TestSyncWithUserBanks:
    async def test_sync_query_and_parse_include_custom_bank(self, authed_client):
        resp = await authed_client.post(
            "/api/banks", json={"bank": "TestBank", "address": "alerts@testbank.com.pk"}
        )
        assert resp.status_code == 200

        body = "PKR 1,500.00 debited from your account at KFC DHA on 01-03-26"
        captured = {}

        def fake_search(service, query, max_results=50):
            captured["query"] = query
            return [_email("ub1", "TestBank <alerts@testbank.com.pk>", body)]

        with (
            patch("server.get_gmail_service", return_value=MagicMock()),
            patch("server.search_emails", side_effect=fake_search),
        ):
            resp = await authed_client.post("/api/sync", json={})

        assert resp.status_code == 200
        data = resp.json()
        assert "alerts@testbank.com.pk" in captured["query"]
        # The email is recognised (not skipped as an unregistered sender):
        # it lands as parsed or pending, never in skipped_parse.
        assert data["emails_found"] == 1
        assert data["skipped_parse"] == 0
        assert data["parsed_ok"] + data["pending_review"] == 1

    async def test_sync_without_custom_banks_unchanged(self, authed_client):
        captured = {}

        def fake_search(service, query, max_results=50):
            captured["query"] = query
            return []

        with (
            patch("server.get_gmail_service", return_value=MagicMock()),
            patch("server.search_emails", side_effect=fake_search),
        ):
            resp = await authed_client.post("/api/sync", json={})

        assert resp.status_code == 200
        assert "sc.com" in captured["query"]
