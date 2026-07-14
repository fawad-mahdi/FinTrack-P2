"""
Tests for the Android embedded server's API authorization model.

The Android server (android/app/src/main/python/server.py) must:
- expose an unauthenticated /health liveness probe
- reject every /api/* request that lacks the per-launch capability token
  (X-API-Token header, injected as FINTRACK_API_TOKEN by the native layer)
- reject requests when no token was provisioned (no default credential)
- not expose the legacy PIN /api/auth route

The Android server shares module names (server, database, ...) with the
desktop app, so it is exercised in a subprocess with its own sys.path and
working directory to avoid import collisions with the desktop test suite.
"""
import json
import os
import subprocess
import sys

import pytest

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ANDROID_PYTHON_DIR = os.path.join(REPO_ROOT, "android", "app", "src", "main", "python")

_PROBE_SCRIPT = r"""
import json
import sys

from fastapi.testclient import TestClient

import server

results = {}
with TestClient(server.app) as client:
    results["health_no_token"] = client.get("/health").status_code

    results["api_no_token"] = client.get("/api/transactions").status_code
    results["api_wrong_token"] = client.get(
        "/api/transactions", headers={"X-API-Token": "wrong-token"}
    ).status_code
    results["api_empty_token"] = client.get(
        "/api/transactions", headers={"X-API-Token": ""}
    ).status_code
    results["api_good_token"] = client.get(
        "/api/transactions", headers={"X-API-Token": "test-capability-token"}
    ).status_code

    results["summary_no_token"] = client.get("/api/summary").status_code
    results["summary_good_token"] = client.get(
        "/api/summary", headers={"X-API-Token": "test-capability-token"}
    ).status_code

    # The legacy PIN route must be gone; with a valid token the middleware
    # passes the request through and routing must yield 404/405, never 200.
    results["legacy_pin_route"] = client.post(
        "/api/auth",
        json={"pin": "1234"},
        headers={"X-API-Token": "test-capability-token"},
    ).status_code

print(json.dumps(results))
"""

_NO_TOKEN_SCRIPT = r"""
import json

from fastapi.testclient import TestClient

import server

results = {}
with TestClient(server.app) as client:
    results["api_no_server_token"] = client.get("/api/transactions").status_code
    results["api_empty_header_no_server_token"] = client.get(
        "/api/transactions", headers={"X-API-Token": ""}
    ).status_code
    results["health_still_open"] = client.get("/health").status_code

print(json.dumps(results))
"""


def _run_android_server_probe(tmp_path, script, api_token):
    env = os.environ.copy()
    env["FINTRACK_APP_DIR"] = ANDROID_PYTHON_DIR
    env["FINTRACK_CONFIG_DIR"] = str(tmp_path / "config")
    env["FINTRACK_LOGS_DIR"] = str(tmp_path / "logs")
    env["FINTRACK_DB_DIR"] = str(tmp_path / "db")
    env.pop("FINTRACK_API_TOKEN", None)
    if api_token is not None:
        env["FINTRACK_API_TOKEN"] = api_token
    for sub in ("config", "logs", "db"):
        (tmp_path / sub).mkdir(exist_ok=True)

    proc = subprocess.run(
        [sys.executable, "-c", script],
        cwd=ANDROID_PYTHON_DIR,
        env=env,
        capture_output=True,
        text=True,
        timeout=120,
    )
    assert proc.returncode == 0, f"probe failed:\n{proc.stdout}\n{proc.stderr}"
    return json.loads(proc.stdout.strip().splitlines()[-1])


@pytest.fixture(scope="module")
def probe_results(tmp_path_factory):
    tmp_path = tmp_path_factory.mktemp("android-server")
    return _run_android_server_probe(tmp_path, _PROBE_SCRIPT, "test-capability-token")


@pytest.fixture(scope="module")
def no_token_results(tmp_path_factory):
    tmp_path = tmp_path_factory.mktemp("android-server-no-token")
    return _run_android_server_probe(tmp_path, _NO_TOKEN_SCRIPT, None)


class TestAndroidApiToken:
    def test_health_is_unauthenticated(self, probe_results):
        assert probe_results["health_no_token"] == 200

    def test_api_rejects_missing_token(self, probe_results):
        assert probe_results["api_no_token"] == 401

    def test_api_rejects_wrong_token(self, probe_results):
        assert probe_results["api_wrong_token"] == 401

    def test_api_rejects_empty_token(self, probe_results):
        assert probe_results["api_empty_token"] == 401

    def test_api_accepts_valid_token(self, probe_results):
        assert probe_results["api_good_token"] == 200

    def test_summary_enforces_token(self, probe_results):
        assert probe_results["summary_no_token"] == 401
        assert probe_results["summary_good_token"] == 200

    def test_legacy_pin_route_removed(self, probe_results):
        assert probe_results["legacy_pin_route"] in (404, 405)


class TestAndroidApiTokenUnprovisioned:
    def test_api_denied_when_no_token_provisioned(self, no_token_results):
        """Without FINTRACK_API_TOKEN there is no default credential:
        every /api request is denied, including empty-header matches."""
        assert no_token_results["api_no_server_token"] == 401
        assert no_token_results["api_empty_header_no_server_token"] == 401

    def test_health_open_when_no_token_provisioned(self, no_token_results):
        assert no_token_results["health_still_open"] == 200
