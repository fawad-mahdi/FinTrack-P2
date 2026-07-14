"""
Live OAuth preflight: replay the exact authorization request the Android app
sends (see MainActivity.initiateOAuthFlow) against Google's real endpoint and
assert Google does not reject it.

This is the test that catches Google Cloud Console misconfiguration — e.g.
"Custom URI scheme is not enabled for your Android client" (the July 2026
production incident: every user saw "Access blocked: Error 400:
invalid_request" and could never connect Gmail). App-side unit tests cannot
see console-side settings; only replaying the request against Google can.

Google renders structural errors on an unauthenticated "Sign in with Google"
page with machine-readable attributes:
    data-error-code="invalid_request"
    data-dev-error-description="Custom URI scheme is not enabled ..."
A healthy client returns a sign-in page without data-error-code.

Skipped automatically when no client ID is configured or the network is down,
so the offline suite stays green.
"""
import base64
import hashlib
import os
import re
import secrets
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

import pytest

# Must mirror Constants.kt / MainActivity.initiateOAuthFlow exactly
OAUTH_AUTH_URI = "https://accounts.google.com/o/oauth2/v2/auth"
OAUTH_SCOPE = "https://www.googleapis.com/auth/gmail.readonly"
CLIENT_ID_SUFFIX = ".apps.googleusercontent.com"


def _configured_client_ids():
    """Client IDs from env or ~/.gradle/gradle.properties (same source the
    Android build injects into BuildConfig)."""
    names = ("FINTRACK_OAUTH_CLIENT_ID_DEBUG", "FINTRACK_OAUTH_CLIENT_ID_RELEASE")
    found = {}
    for name in names:
        if os.environ.get(name):
            found[name] = os.environ[name].strip()
    props = Path.home() / ".gradle" / "gradle.properties"
    if props.exists():
        for line in props.read_text().splitlines():
            line = line.strip()
            for name in names:
                if name not in found and line.startswith(name + "="):
                    value = line.split("=", 1)[1].strip()
                    if value:
                        found[name] = value
    return found


def _redirect_uri(client_id):
    """Reversed-client-ID custom scheme, as Constants.OAUTH_REDIRECT_URI builds it."""
    scheme = "com.googleusercontent.apps." + client_id[: -len(CLIENT_ID_SUFFIX)]
    return scheme + ":/oauth2callback"


def _build_auth_url(client_id):
    """The same request AppAuth sends: code flow + PKCE + consent prompt."""
    verifier = base64.urlsafe_b64encode(secrets.token_bytes(32)).rstrip(b"=")
    challenge = base64.urlsafe_b64encode(
        hashlib.sha256(verifier).digest()
    ).rstrip(b"=").decode()
    params = {
        "client_id": client_id,
        "redirect_uri": _redirect_uri(client_id),
        "response_type": "code",
        "scope": OAUTH_SCOPE,
        "prompt": "consent",
        "access_type": "offline",
        "state": secrets.token_urlsafe(16),
        "code_challenge": challenge,
        "code_challenge_method": "S256",
    }
    return OAUTH_AUTH_URI + "?" + urllib.parse.urlencode(params)


def _fetch(url):
    req = urllib.request.Request(
        url,
        headers={
            "User-Agent": (
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
                "(KHTML, like Gecko) Chrome/126.0 Safari/537.36"
            )
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.status, resp.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", errors="replace")
    except (urllib.error.URLError, TimeoutError) as e:
        pytest.skip(f"Network unavailable for OAuth preflight: {e}")


CLIENT_IDS = _configured_client_ids()


@pytest.mark.skipif(
    not CLIENT_IDS,
    reason="No FINTRACK_OAUTH_CLIENT_ID_* configured (env or ~/.gradle/gradle.properties)",
)
@pytest.mark.parametrize("name", sorted(CLIENT_IDS))
def test_google_accepts_android_authorization_request(name):
    client_id = CLIENT_IDS[name]
    assert client_id.endswith(CLIENT_ID_SUFFIX), (
        f"{name} does not look like a Google OAuth client ID: {client_id!r}"
    )

    status, body = _fetch(_build_auth_url(client_id))

    error_code = re.search(r'data-error-code="([^"]*)"', body)
    dev_description = re.search(r'data-dev-error-description="([^"]*)"', body)
    assert error_code is None, (
        f"Google rejected the authorization request for {name} "
        f"({client_id}): {error_code.group(1)}"
        + (f" — {dev_description.group(1)}" if dev_description else "")
        + ". Users will see 'Access blocked … Error 400' and cannot connect "
        "Gmail. Check the OAuth client in Google Cloud Console "
        "(APIs & Services → Credentials): for Android clients, 'Custom URI "
        "scheme' must be enabled under Advanced Settings."
    )
    assert status == 200, f"Unexpected HTTP {status} from Google auth endpoint"
