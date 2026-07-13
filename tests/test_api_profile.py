"""
Tests for the Profile endpoints: GET/PUT /api/profile and POST /api/profile/pin.

Gmail lookups are mocked — get_account_email never hits the network here.
"""
import pytest

import server as server_module


@pytest.fixture(autouse=True)
def no_gmail(monkeypatch):
    """Default: no Gmail token — profile email resolves to None."""
    monkeypatch.setattr(server_module, "get_account_email", lambda: None)


# ─── GET /api/profile ────────────────────────────────────────────

async def test_profile_requires_auth(client):
    resp = await client.get("/api/profile")
    assert resp.status_code == 401


async def test_profile_defaults(authed_client):
    resp = await authed_client.get("/api/profile")
    assert resp.status_code == 200
    assert resp.json() == {"name": "User", "email": None}


async def test_profile_email_fetched_and_cached(authed_client, monkeypatch):
    monkeypatch.setattr(
        server_module, "get_account_email", lambda: "user@gmail.com"
    )
    resp = await authed_client.get("/api/profile")
    assert resp.json()["email"] == "user@gmail.com"

    # Second call must come from the settings cache, not the Gmail API.
    def boom():
        raise AssertionError("should not re-fetch")
    monkeypatch.setattr(server_module, "get_account_email", boom)
    resp = await authed_client.get("/api/profile")
    assert resp.json()["email"] == "user@gmail.com"


# ─── PUT /api/profile ────────────────────────────────────────────

async def test_update_name(authed_client):
    resp = await authed_client.put("/api/profile", json={"name": "Fawad"})
    assert resp.status_code == 200
    resp = await authed_client.get("/api/profile")
    assert resp.json()["name"] == "Fawad"


@pytest.mark.parametrize("bad", ["", "   ", "x" * 51, None])
async def test_update_name_rejects_invalid(authed_client, bad):
    resp = await authed_client.put("/api/profile", json={"name": bad})
    assert resp.status_code == 400


# ─── POST /api/profile/pin ───────────────────────────────────────

async def test_change_pin_wrong_current(authed_client):
    resp = await authed_client.post(
        "/api/profile/pin", json={"current_pin": "0000", "new_pin": "5678"}
    )
    assert resp.status_code == 401


@pytest.mark.parametrize("bad", ["123", "12345", "abcd", "12a4", ""])
async def test_change_pin_rejects_invalid_new(authed_client, bad):
    resp = await authed_client.post(
        "/api/profile/pin", json={"current_pin": "1234", "new_pin": bad}
    )
    assert resp.status_code == 400


async def test_change_pin_then_auth_with_new(authed_client):
    resp = await authed_client.post(
        "/api/profile/pin", json={"current_pin": "1234", "new_pin": "5678"}
    )
    assert resp.status_code == 200

    # Old (default env) PIN no longer works, new one does.
    server_module._authenticated.clear()
    resp = await authed_client.post("/api/auth", json={"pin": "1234"})
    assert resp.status_code == 401
    resp = await authed_client.post("/api/auth", json={"pin": "5678"})
    assert resp.status_code == 200


async def test_change_pin_twice_requires_latest(authed_client):
    await authed_client.post(
        "/api/profile/pin", json={"current_pin": "1234", "new_pin": "5678"}
    )
    # Old PIN is no longer accepted as "current".
    resp = await authed_client.post(
        "/api/profile/pin", json={"current_pin": "1234", "new_pin": "9999"}
    )
    assert resp.status_code == 401
    resp = await authed_client.post(
        "/api/profile/pin", json={"current_pin": "5678", "new_pin": "9999"}
    )
    assert resp.status_code == 200
