"""
Tests for PIN authentication: POST /api/auth and protected-endpoint enforcement.
"""
import pytest


# ── POST /api/auth ────────────────────────────────────────────────────────────

class TestPinAuth:
    async def test_correct_pin_returns_ok(self, client):
        resp = await client.post("/api/auth", json={"pin": "1234"})
        assert resp.status_code == 200
        assert resp.json() == {"ok": True}

    async def test_wrong_pin_returns_401(self, client):
        resp = await client.post("/api/auth", json={"pin": "0000"})
        assert resp.status_code == 401

    async def test_wrong_pin_has_detail_message(self, client):
        resp = await client.post("/api/auth", json={"pin": "9999"})
        assert "Wrong PIN" in resp.json()["detail"]

    async def test_empty_pin_returns_401(self, client):
        resp = await client.post("/api/auth", json={"pin": ""})
        assert resp.status_code == 401

    async def test_missing_pin_field_returns_401(self, client):
        resp = await client.post("/api/auth", json={})
        assert resp.status_code == 401

    async def test_auth_state_persists_within_session(self, client):
        """After a successful auth, subsequent protected requests are allowed."""
        await client.post("/api/auth", json={"pin": "1234"})
        resp = await client.get("/api/sync/status")
        assert resp.status_code == 200


# ── Auth guard on protected endpoints ────────────────────────────────────────

class TestAuthGuard:
    """Every protected endpoint must return 401 when not authenticated."""

    async def test_transactions_requires_auth(self, client):
        assert (await client.get("/api/transactions")).status_code == 401

    async def test_create_transaction_requires_auth(self, client):
        assert (await client.post("/api/transactions", json={})).status_code == 401

    async def test_create_cash_transaction_requires_auth(self, client):
        assert (await client.post("/api/transactions/cash", json={})).status_code == 401

    async def test_patch_transaction_requires_auth(self, client):
        assert (await client.patch("/api/transactions/1", json={})).status_code == 401

    async def test_delete_transaction_requires_auth(self, client):
        assert (await client.delete("/api/transactions/1")).status_code == 401

    async def test_summary_requires_auth(self, client):
        assert (await client.get("/api/summary")).status_code == 401

    async def test_category_summary_requires_auth(self, client):
        assert (await client.get("/api/summary/categories")).status_code == 401

    async def test_sync_requires_auth(self, client):
        assert (await client.post("/api/sync", json={})).status_code == 401

    async def test_sync_status_requires_auth(self, client):
        assert (await client.get("/api/sync/status")).status_code == 401

    async def test_category_mappings_requires_auth(self, client):
        assert (await client.get("/api/categories/mappings")).status_code == 401

    async def test_budgets_requires_auth(self, client):
        assert (await client.get("/api/budgets")).status_code == 401

    async def test_upsert_budget_requires_auth(self, client):
        assert (await client.put("/api/budgets", json={})).status_code == 401

    async def test_report_requires_auth(self, client):
        assert (await client.get("/api/report")).status_code == 401
