"""
Unit tests for the ANDROID copy of auth_gmail.py
(android/app/src/main/python/auth_gmail.py).

The Android copy is intentionally divergent from the desktop one: token.json
is created by the native Kotlin OAuth flow (AppAuth + OAuthCallbackActivity),
and token refresh goes through Java networking (_java_post) because Python
sockets cannot resolve DNS under Chaquopy.

These tests protect the Kotlin ↔ Python token.json contract:
  - Python must accept token.json exactly as OAuthTokenManager.kt writes it
    (Instant.toString() expiry with or without millis, empty client_secret).
  - Python must write token.json back in a shape Kotlin can re-read
    (all keys present, expiry parseable by java.time.Instant.parse()).
  - Broken token files must surface as OAuthRequiredError (re-auth prompt),
    never as an unhandled crash that bricks every sync.
"""
import importlib.util
import json
import re
import sys
from datetime import datetime
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


CLIENT_ID = "746798969138-example.apps.googleusercontent.com"


@pytest.fixture
def config_dir(tmp_path, monkeypatch):
    """Point FINTRACK_CONFIG_DIR at an isolated temp dir."""
    monkeypatch.setenv("FINTRACK_CONFIG_DIR", str(tmp_path))
    return tmp_path


def write_token(config_dir, **overrides):
    """Write token.json exactly as OAuthTokenManager.saveInitialTokens() does."""
    data = {
        "token": "access-token-abc",
        "refresh_token": "refresh-token-xyz",
        "token_uri": "https://oauth2.googleapis.com/token",
        "client_id": CLIENT_ID,
        "client_secret": "",
        "scopes": ["https://www.googleapis.com/auth/gmail.readonly"],
        "expiry": "2099-01-01T00:00:00Z",
    }
    data.update(overrides)
    (config_dir / "token.json").write_text(json.dumps(data))
    return data


# ── get_gmail_service(): token states ─────────────────────────────────────────

class TestGetGmailService:
    def test_no_token_raises_oauth_required(self, config_dir):
        with pytest.raises(android_auth.OAuthRequiredError):
            android_auth.get_gmail_service()

    def test_valid_kotlin_token_builds_service(self, config_dir, monkeypatch):
        """Kotlin writes expiry via Instant.toString() → may include millis."""
        write_token(config_dir, expiry="2099-01-01T00:00:00.123Z")
        mock_build = MagicMock(return_value="gmail-service")
        monkeypatch.setattr(android_auth, "build", mock_build)

        assert android_auth.get_gmail_service() == "gmail-service"
        args, kwargs = mock_build.call_args
        assert args[:2] == ("gmail", "v1")
        assert kwargs["credentials"].token == "access-token-abc"

    def test_valid_python_format_token_builds_service(self, config_dir, monkeypatch):
        """Python's own refresh writes expiry without millis."""
        write_token(config_dir, expiry="2099-01-01T00:00:00Z")
        monkeypatch.setattr(android_auth, "build", MagicMock(return_value="svc"))
        assert android_auth.get_gmail_service() == "svc"

    def test_corrupted_token_json_raises_oauth_required_and_deletes_file(self, config_dir):
        """A truncated/corrupt token.json must trigger re-auth, not crash sync."""
        token_path = config_dir / "token.json"
        token_path.write_text('{"token": "abc", "refresh_')  # truncated write

        with pytest.raises(android_auth.OAuthRequiredError):
            android_auth.get_gmail_service()
        assert not token_path.exists(), "corrupt token.json should be removed"

    def test_expired_token_without_refresh_token_raises_oauth_required(self, config_dir):
        write_token(config_dir, expiry="2020-01-01T00:00:00Z", refresh_token="")
        with pytest.raises(android_auth.OAuthRequiredError):
            android_auth.get_gmail_service()

    def test_empty_access_token_without_refresh_token_raises_oauth_required(self, config_dir):
        """Kotlin persists "" when Google returned no access token."""
        write_token(config_dir, token="", refresh_token="")
        with pytest.raises(android_auth.OAuthRequiredError):
            android_auth.get_gmail_service()

    def test_missing_expiry_is_treated_as_expired_and_refreshed(self, config_dir, monkeypatch):
        """expiry=None means 'never expires' to google-auth — we must instead
        force the refresh path so a stale token is not used until it 401s."""
        write_token(config_dir, expiry=None)
        posted = {}

        def fake_java_post(url, form):
            posted["url"] = url
            posted["form"] = form
            return {"access_token": "fresh-token", "expires_in": 3600}

        monkeypatch.setattr(android_auth, "_java_post", fake_java_post)
        monkeypatch.setattr(android_auth, "build", MagicMock(return_value="svc"))

        assert android_auth.get_gmail_service() == "svc"
        assert posted["url"] == "https://oauth2.googleapis.com/token"
        assert posted["form"]["grant_type"] == "refresh_token"

    def test_unparseable_expiry_is_treated_as_expired_and_refreshed(self, config_dir, monkeypatch):
        write_token(config_dir, expiry="not-a-date")
        monkeypatch.setattr(
            android_auth, "_java_post",
            lambda url, form: {"access_token": "fresh", "expires_in": 3600},
        )
        monkeypatch.setattr(android_auth, "build", MagicMock(return_value="svc"))
        assert android_auth.get_gmail_service() == "svc"


# ── Refresh: secretless Android client contract ───────────────────────────────

class TestRefresh:
    def test_secretless_client_omits_client_secret_from_refresh_form(self, config_dir, monkeypatch):
        """Android-type OAuth clients have no secret; the token endpoint must
        not receive a client_secret key at all (not even an empty one)."""
        write_token(config_dir, expiry="2020-01-01T00:00:00Z", client_secret="")
        posted = {}

        def fake_java_post(url, form):
            posted["form"] = form
            return {"access_token": "fresh", "expires_in": 3600}

        monkeypatch.setattr(android_auth, "_java_post", fake_java_post)
        monkeypatch.setattr(android_auth, "build", MagicMock())

        android_auth.get_gmail_service()
        assert "client_secret" not in posted["form"]
        assert posted["form"]["client_id"] == CLIENT_ID
        assert posted["form"]["refresh_token"] == "refresh-token-xyz"

    def test_legacy_desktop_client_secret_still_sent_when_present(self, config_dir, monkeypatch):
        """Tokens from the retired desktop-type client carry a secret."""
        write_token(config_dir, expiry="2020-01-01T00:00:00Z", client_secret="legacy-secret")
        posted = {}

        def fake_java_post(url, form):
            posted["form"] = form
            return {"access_token": "fresh", "expires_in": 3600}

        monkeypatch.setattr(android_auth, "_java_post", fake_java_post)
        monkeypatch.setattr(android_auth, "build", MagicMock())

        android_auth.get_gmail_service()
        assert posted["form"]["client_secret"] == "legacy-secret"

    def test_refresh_persists_token_json_kotlin_can_read(self, config_dir, monkeypatch):
        """After a Python-side refresh, token.json must still be loadable by
        OAuthTokenManager.kt: all keys present, expiry parseable by
        java.time.Instant.parse() (strict ISO-8601 instant, Z suffix)."""
        write_token(config_dir, expiry="2020-01-01T00:00:00Z")
        monkeypatch.setattr(
            android_auth, "_java_post",
            lambda url, form: {"access_token": "fresh-token", "expires_in": 3600},
        )
        monkeypatch.setattr(android_auth, "build", MagicMock())

        android_auth.get_gmail_service()

        saved = json.loads((config_dir / "token.json").read_text())
        for key in ("token", "refresh_token", "token_uri", "client_id",
                    "client_secret", "scopes", "expiry"):
            assert key in saved, f"Kotlin OAuthTokenManager expects key: {key}"
        assert saved["token"] == "fresh-token"
        assert saved["refresh_token"] == "refresh-token-xyz"  # preserved
        assert re.fullmatch(
            r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z", saved["expiry"]
        ), f"expiry {saved['expiry']!r} not parseable by Instant.parse()"
        # And Python itself must be able to re-read what it wrote
        datetime.strptime(saved["expiry"], "%Y-%m-%dT%H:%M:%SZ")

    def test_rotated_refresh_token_is_persisted(self, config_dir, monkeypatch):
        """If Google rotates the refresh token, the new one must be saved."""
        write_token(config_dir, expiry="2020-01-01T00:00:00Z")
        monkeypatch.setattr(
            android_auth, "_java_post",
            lambda url, form: {
                "access_token": "fresh",
                "refresh_token": "rotated-refresh",
                "expires_in": 3600,
            },
        )
        monkeypatch.setattr(android_auth, "build", MagicMock())

        android_auth.get_gmail_service()
        saved = json.loads((config_dir / "token.json").read_text())
        assert saved["refresh_token"] == "rotated-refresh"


# ── get_account_email() ───────────────────────────────────────────────────────

class TestGetAccountEmail:
    def test_returns_none_when_no_token(self, config_dir):
        assert android_auth.get_account_email() is None

    def test_returns_none_on_auth_error_instead_of_raising(self, config_dir):
        """Safe to call from a GET endpoint: swallow OAuthRequiredError."""
        write_token(config_dir, token="", refresh_token="")
        assert android_auth.get_account_email() is None

    def test_returns_profile_email_for_valid_token(self, config_dir, monkeypatch):
        write_token(config_dir)
        service = MagicMock()
        service.users().getProfile(userId="me").execute.return_value = {
            "emailAddress": "fawadmahdi@gmail.com"
        }
        monkeypatch.setattr(android_auth, "build", MagicMock(return_value=service))
        assert android_auth.get_account_email() == "fawadmahdi@gmail.com"
