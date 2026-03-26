"""Shared pytest fixtures for FinTrack PK backend tests."""
import pytest
import database
import server as server_module
from httpx import AsyncClient, ASGITransport


@pytest.fixture(autouse=True)
def reset_auth_state():
    """Clear PIN auth between every test so tests are fully isolated."""
    server_module._authenticated.clear()
    yield
    server_module._authenticated.clear()


@pytest.fixture
async def client(tmp_path):
    """Async HTTP client wired to the FastAPI app with an isolated temp database."""
    original_db = database.DB_PATH
    database.DB_PATH = str(tmp_path / "test.db")
    database.init_db()

    async with AsyncClient(
        transport=ASGITransport(app=server_module.app),
        base_url="http://test",
    ) as ac:
        yield ac

    database.DB_PATH = original_db


@pytest.fixture
async def authed_client(client):
    """client fixture pre-authenticated with the default PIN."""
    resp = await client.post("/api/auth", json={"pin": "1234"})
    assert resp.status_code == 200, f"Auth setup failed: {resp.text}"
    yield client
