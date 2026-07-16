"""
Unit tests for the ANDROID copy of auth_gmail.py
(android/app/src/main/python/auth_gmail.py).

The Android copy is intentionally divergent from the desktop one: all OAuth
token material (access + refresh token, client secret) lives in Android
Keystore-backed encrypted storage owned by the Kotlin GmailTokenBroker.
Python never touches token.json — it only calls into the broker via the
Chaquopy Java bridge to obtain an already-refreshed access token.

These tests protect the Kotlin <-> Python broker contract:
  - get_gmail_service() must raise OAuthRequiredError (not crash) whenever
    the broker call fails or returns no token, so the frontend can trigger
    the native re-auth flow.
  - get_account_email() must never raise — it swallows auth errors so it
    is safe to call from a GET endpoint.
"""
import importlib.util
import sys
from pathlib import Path
from unittest.mock import MagicMock

import pytest

ANDROID_AUTH_PATH = (
    Path(__file__).resolve().parent.parent
    / "android" / "app" / "src" / "main" / "python" / "auth_gmail.py"
)

_spec = importlib.util.spec_from_file_location("android_auth_gmail", ANDROID_AUTH_PATH)
android_auth = importlib.util.module_from_spec(_spec)
sys.modules["android_auth_gmail"] = android_auth
_spec.loader.exec_module(android_auth)


# ── get_gmail_service(): broker access token states ────────────────────────

class TestGetGmailService:
    def test_no_broker_token_raises_oauth_required(self, monkeypatch):
        monkeypatch.setattr(android_auth, "_get_broker_access_token", lambda: None)
        with pytest.raises(android_auth.OAuthRequiredError):
            android_auth.get_gmail_service()

    def test_broker_exception_raises_oauth_required(self, monkeypatch):
        def boom():
            raise RuntimeError("bridge unavailable")

        monkeypatch.setattr(android_auth, "_get_broker_access_token", boom)
        with pytest.raises(android_auth.OAuthRequiredError):
            android_auth.get_gmail_service()

    def test_valid_broker_token_builds_service(self, monkeypatch):
        monkeypatch.setattr(android_auth, "_get_broker_access_token", lambda: "access-token-abc")
        mock_build = MagicMock(return_value="gmail-service")
        monkeypatch.setattr(android_auth, "build", mock_build)

        assert android_auth.get_gmail_service() == "gmail-service"
        args, kwargs = mock_build.call_args
        assert args[:2] == ("gmail", "v1")
        assert kwargs["credentials"].token == "access-token-abc"

    def test_empty_broker_token_raises_oauth_required(self, monkeypatch):
        monkeypatch.setattr(android_auth, "_get_broker_access_token", lambda: "")
        with pytest.raises(android_auth.OAuthRequiredError):
            android_auth.get_gmail_service()


# ── get_account_email() ─────────────────────────────────────────────────────

class TestGetAccountEmail:
    def test_returns_none_when_no_token(self, monkeypatch):
        monkeypatch.setattr(android_auth, "_get_broker_access_token", lambda: None)
        assert android_auth.get_account_email() is None

    def test_returns_none_on_auth_error_instead_of_raising(self, monkeypatch):
        def boom():
            raise RuntimeError("bridge unavailable")

        monkeypatch.setattr(android_auth, "_get_broker_access_token", boom)
        assert android_auth.get_account_email() is None

    def test_returns_profile_email_for_valid_token(self, monkeypatch):
        monkeypatch.setattr(android_auth, "_get_broker_access_token", lambda: "access-token-abc")
        service = MagicMock()
        service.users().getProfile(userId="me").execute.return_value = {
            "emailAddress": "fawadmahdi@gmail.com"
        }
        monkeypatch.setattr(android_auth, "build", MagicMock(return_value=service))
        assert android_auth.get_account_email() == "fawadmahdi@gmail.com"
