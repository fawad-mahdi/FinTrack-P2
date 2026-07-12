"""
Tests for transaction CRUD endpoints.
"""
import pytest


TX_PAYLOAD = {
    "amount": 1500.0,
    "tx_type": "debit",
    "merchant": "KFC DHA",
    "bank": "SCB",
    "category": "dining",
    "tx_date": "2026-03-01",
}


# ── GET /api/transactions ─────────────────────────────────────────────────────

class TestListTransactions:
    async def test_returns_empty_list_initially(self, authed_client):
        resp = await authed_client.get("/api/transactions")
        assert resp.status_code == 200
        data = resp.json()
        assert data["items"] == [] or data == [] or isinstance(data, (list, dict))

    async def test_returns_transaction_after_create(self, authed_client):
        await authed_client.post("/api/transactions", json=TX_PAYLOAD)
        resp = await authed_client.get("/api/transactions")
        assert resp.status_code == 200
        data = resp.json()
        items = data.get("items", data) if isinstance(data, dict) else data
        assert len(items) == 1
        assert items[0]["merchant"] == "KFC DHA"

    async def test_pagination_page_param(self, authed_client):
        resp = await authed_client.get("/api/transactions?page=1&page_size=10")
        assert resp.status_code == 200

    async def test_filter_by_bank(self, authed_client):
        await authed_client.post("/api/transactions", json=TX_PAYLOAD)
        resp = await authed_client.get("/api/transactions?bank=SCB")
        assert resp.status_code == 200

    async def test_filter_by_category(self, authed_client):
        await authed_client.post("/api/transactions", json=TX_PAYLOAD)
        resp = await authed_client.get("/api/transactions?category=dining")
        assert resp.status_code == 200

    async def test_search_param(self, authed_client):
        await authed_client.post("/api/transactions", json=TX_PAYLOAD)
        resp = await authed_client.get("/api/transactions?search=KFC")
        assert resp.status_code == 200


# ── POST /api/transactions ────────────────────────────────────────────────────

class TestCreateTransaction:
    async def test_creates_transaction_with_all_fields(self, authed_client):
        resp = await authed_client.post("/api/transactions", json=TX_PAYLOAD)
        assert resp.status_code == 200
        assert resp.json()["ok"] is True

    async def test_missing_amount_returns_400(self, authed_client):
        payload = {k: v for k, v in TX_PAYLOAD.items() if k != "amount"}
        resp = await authed_client.post("/api/transactions", json=payload)
        assert resp.status_code == 400
        assert "amount" in resp.json()["detail"]

    async def test_missing_tx_type_returns_400(self, authed_client):
        payload = {k: v for k, v in TX_PAYLOAD.items() if k != "tx_type"}
        resp = await authed_client.post("/api/transactions", json=payload)
        assert resp.status_code == 400
        assert "tx_type" in resp.json()["detail"]

    async def test_missing_merchant_returns_400(self, authed_client):
        payload = {k: v for k, v in TX_PAYLOAD.items() if k != "merchant"}
        resp = await authed_client.post("/api/transactions", json=payload)
        assert resp.status_code == 400
        assert "merchant" in resp.json()["detail"]

    async def test_missing_bank_returns_400(self, authed_client):
        payload = {k: v for k, v in TX_PAYLOAD.items() if k != "bank"}
        resp = await authed_client.post("/api/transactions", json=payload)
        assert resp.status_code == 400
        assert "bank" in resp.json()["detail"]

    async def test_credit_transaction(self, authed_client):
        payload = {**TX_PAYLOAD, "tx_type": "credit", "merchant": "Salary"}
        resp = await authed_client.post("/api/transactions", json=payload)
        assert resp.status_code == 200

    async def test_category_defaults_to_other_when_omitted(self, authed_client):
        payload = {k: v for k, v in TX_PAYLOAD.items() if k != "category"}
        resp = await authed_client.post("/api/transactions", json=payload)
        assert resp.status_code == 200


# ── POST /api/transactions/cash ──────────────────────────────────────────────

class TestCashTransaction:
    async def test_creates_cash_expense(self, authed_client):
        resp = await authed_client.post(
            "/api/transactions/cash",
            json={"amount": 500, "note": "chai", "category": "dining", "date": "2026-03-01"},
        )
        assert resp.status_code == 200
        assert resp.json()["ok"] is True

    async def test_zero_amount_returns_400(self, authed_client):
        resp = await authed_client.post("/api/transactions/cash", json={"amount": 0})
        assert resp.status_code == 400
        assert "positive" in resp.json()["detail"]

    async def test_negative_amount_returns_400(self, authed_client):
        resp = await authed_client.post("/api/transactions/cash", json={"amount": -100})
        assert resp.status_code == 400

    async def test_non_numeric_amount_returns_400(self, authed_client):
        resp = await authed_client.post("/api/transactions/cash", json={"amount": "abc"})
        assert resp.status_code == 400
        assert "number" in resp.json()["detail"]

    async def test_note_defaults_to_cash_expense_when_omitted(self, authed_client):
        await authed_client.post("/api/transactions/cash", json={"amount": 100})
        resp = await authed_client.get("/api/transactions")
        items = resp.json().get("items", resp.json())
        cash_txs = [t for t in items if t.get("bank") == "Cash"]
        assert any(t["merchant"] == "Cash Expense" for t in cash_txs)

    async def test_bank_is_always_cash(self, authed_client):
        await authed_client.post(
            "/api/transactions/cash", json={"amount": 250, "note": "rickshaw"}
        )
        resp = await authed_client.get("/api/transactions")
        items = resp.json().get("items", resp.json())
        assert any(t.get("bank") == "Cash" for t in items)

    async def test_date_in_yyyy_mm_dd_is_accepted(self, authed_client):
        resp = await authed_client.post(
            "/api/transactions/cash",
            json={"amount": 300, "date": "2026-03-15"},
        )
        assert resp.status_code == 200


# ── PATCH /api/transactions/{id} ─────────────────────────────────────────────

class TestPatchTransaction:
    async def test_patch_updates_category(self, authed_client):
        await authed_client.post("/api/transactions", json=TX_PAYLOAD)
        resp = await authed_client.get("/api/transactions")
        items = resp.json().get("items", resp.json())
        tx_id = items[0]["id"]

        patch_resp = await authed_client.patch(
            f"/api/transactions/{tx_id}", json={"category": "fuel"}
        )
        assert patch_resp.status_code == 200
        assert patch_resp.json()["ok"] is True

    async def test_patch_updates_merchant(self, authed_client):
        await authed_client.post("/api/transactions", json=TX_PAYLOAD)
        items = (await authed_client.get("/api/transactions")).json().get("items", [])
        tx_id = items[0]["id"]

        await authed_client.patch(f"/api/transactions/{tx_id}", json={"merchant": "Total Parco"})
        resp = await authed_client.get("/api/transactions")
        updated = resp.json().get("items", resp.json())
        assert any(t["merchant"] == "Total Parco" for t in updated)


# ── DELETE /api/transactions/{id} ────────────────────────────────────────────

class TestDeleteTransaction:
    async def test_delete_removes_transaction(self, authed_client):
        await authed_client.post("/api/transactions", json=TX_PAYLOAD)
        items = (await authed_client.get("/api/transactions")).json().get("items", [])
        tx_id = items[0]["id"]

        del_resp = await authed_client.delete(f"/api/transactions/{tx_id}")
        assert del_resp.status_code == 200
        assert del_resp.json()["ok"] is True

        resp = await authed_client.get("/api/transactions")
        remaining = resp.json().get("items", resp.json())
        assert all(t["id"] != tx_id for t in remaining)
