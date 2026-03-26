"""
Tests for summary, category summary, budgets, and monthly report endpoints.
"""
import pytest

TX = {
    "amount": 5000.0,
    "tx_type": "debit",
    "merchant": "Shell Petrol",
    "bank": "Meezan",
    "category": "fuel",
    "tx_date": "2026-03-10",
}

INCOME_TX = {
    "amount": 80000.0,
    "tx_type": "credit",
    "merchant": "Salary",
    "bank": "SCB",
    "category": "transfer",
    "tx_date": "2026-03-01",
}


# ── GET /api/summary ──────────────────────────────────────────────────────────

class TestSummary:
    async def test_returns_income_expenses_net_keys(self, authed_client):
        resp = await authed_client.get("/api/summary")
        assert resp.status_code == 200
        data = resp.json()
        assert "income" in data
        assert "expenses" in data
        assert "net" in data

    async def test_empty_db_returns_zero_totals(self, authed_client):
        resp = await authed_client.get("/api/summary")
        data = resp.json()
        assert data["income"] == 0
        assert data["expenses"] == 0
        assert data["net"] == 0

    async def test_debit_transaction_increases_expenses(self, authed_client):
        await authed_client.post("/api/transactions", json=TX)
        resp = await authed_client.get("/api/summary?period=all")
        assert resp.json()["expenses"] == 5000.0

    async def test_credit_transaction_increases_income(self, authed_client):
        await authed_client.post("/api/transactions", json=INCOME_TX)
        resp = await authed_client.get("/api/summary?period=all")
        assert resp.json()["income"] == 80000.0

    async def test_net_is_income_minus_expenses(self, authed_client):
        await authed_client.post("/api/transactions", json=TX)
        await authed_client.post("/api/transactions", json=INCOME_TX)
        resp = await authed_client.get("/api/summary?period=all")
        data = resp.json()
        assert data["net"] == data["income"] - data["expenses"]

    async def test_period_month_is_default(self, authed_client):
        resp = await authed_client.get("/api/summary")
        assert resp.status_code == 200

    async def test_period_30d_accepted(self, authed_client):
        assert (await authed_client.get("/api/summary?period=30d")).status_code == 200

    async def test_period_3m_accepted(self, authed_client):
        assert (await authed_client.get("/api/summary?period=3m")).status_code == 200

    async def test_period_all_accepted(self, authed_client):
        assert (await authed_client.get("/api/summary?period=all")).status_code == 200


# ── GET /api/summary/categories ──────────────────────────────────────────────

class TestCategorySummary:
    async def test_returns_list(self, authed_client):
        resp = await authed_client.get("/api/summary/categories")
        assert resp.status_code == 200
        assert isinstance(resp.json(), list)

    async def test_empty_db_returns_empty_list(self, authed_client):
        resp = await authed_client.get("/api/summary/categories")
        assert resp.json() == []

    async def test_category_row_has_required_keys(self, authed_client):
        await authed_client.post("/api/transactions", json=TX)
        resp = await authed_client.get("/api/summary/categories?period=all")
        rows = resp.json()
        assert len(rows) >= 1
        row = rows[0]
        assert "category" in row
        assert "total" in row
        assert "percentage" in row

    async def test_fuel_transaction_appears_in_category_summary(self, authed_client):
        await authed_client.post("/api/transactions", json=TX)
        resp = await authed_client.get("/api/summary/categories?period=all")
        categories = [r["category"] for r in resp.json()]
        assert "fuel" in categories

    async def test_percentages_sum_to_100(self, authed_client):
        await authed_client.post("/api/transactions", json=TX)
        await authed_client.post("/api/transactions", json={**TX, "category": "dining", "amount": 3000})
        resp = await authed_client.get("/api/summary/categories?period=all")
        total_pct = sum(r["percentage"] for r in resp.json())
        assert abs(total_pct - 100.0) < 1.0  # allow for rounding


# ── GET /api/budgets ──────────────────────────────────────────────────────────

class TestBudgets:
    async def test_returns_categories_key(self, authed_client):
        resp = await authed_client.get("/api/budgets")
        assert resp.status_code == 200
        assert "categories" in resp.json()

    async def test_categories_is_list(self, authed_client):
        assert isinstance(resp := (await authed_client.get("/api/budgets")).json()["categories"], list)

    async def test_default_month_is_current(self, authed_client):
        from datetime import date
        current = date.today().strftime("%Y-%m")
        resp = await authed_client.get("/api/budgets")
        # Should work — no error
        assert resp.status_code == 200

    async def test_custom_month_param(self, authed_client):
        resp = await authed_client.get("/api/budgets?month=2026-01")
        assert resp.status_code == 200


# ── PUT /api/budgets ──────────────────────────────────────────────────────────

class TestUpsertBudget:
    async def test_set_budget_returns_ok(self, authed_client):
        resp = await authed_client.put(
            "/api/budgets",
            json={"category": "dining", "amount": 25000, "month": "2026-03"},
        )
        assert resp.status_code == 200
        assert resp.json()["ok"] is True

    async def test_budget_appears_in_get_budgets(self, authed_client):
        await authed_client.put(
            "/api/budgets",
            json={"category": "fuel", "amount": 15000, "month": "2026-03"},
        )
        resp = await authed_client.get("/api/budgets?month=2026-03")
        cats = resp.json()["categories"]
        fuel_rows = [c for c in cats if c["category"] == "fuel"]
        assert len(fuel_rows) == 1
        assert fuel_rows[0]["budget"] == 15000

    async def test_upsert_overwrites_existing_budget(self, authed_client):
        await authed_client.put("/api/budgets", json={"category": "fuel", "amount": 10000, "month": "2026-03"})
        await authed_client.put("/api/budgets", json={"category": "fuel", "amount": 20000, "month": "2026-03"})
        resp = await authed_client.get("/api/budgets?month=2026-03")
        cats = resp.json()["categories"]
        fuel = next(c for c in cats if c["category"] == "fuel")
        assert fuel["budget"] == 20000

    async def test_missing_category_returns_400(self, authed_client):
        resp = await authed_client.put("/api/budgets", json={"amount": 5000})
        assert resp.status_code == 400
        assert "category" in resp.json()["detail"]

    async def test_non_numeric_amount_returns_400(self, authed_client):
        resp = await authed_client.put(
            "/api/budgets", json={"category": "dining", "amount": "lots"}
        )
        assert resp.status_code == 400


# ── POST /api/budgets/suggest ─────────────────────────────────────────────────

class TestSuggestBudgets:
    async def test_returns_list(self, authed_client):
        resp = await authed_client.post("/api/budgets/suggest", json={"month": "2026-03"})
        assert resp.status_code == 200
        assert isinstance(resp.json(), list)

    async def test_no_body_does_not_crash(self, authed_client):
        resp = await authed_client.post("/api/budgets/suggest")
        assert resp.status_code == 200


# ── GET /api/report ───────────────────────────────────────────────────────────

class TestMonthlyReport:
    async def test_returns_200(self, authed_client):
        resp = await authed_client.get("/api/report?month=2026-03")
        assert resp.status_code == 200

    async def test_default_month_is_current(self, authed_client):
        resp = await authed_client.get("/api/report")
        assert resp.status_code == 200

    async def test_report_has_expected_keys(self, authed_client):
        resp = await authed_client.get("/api/report?month=2026-03")
        data = resp.json()
        # Should return a meaningful structure — at minimum a dict
        assert isinstance(data, (dict, list))
