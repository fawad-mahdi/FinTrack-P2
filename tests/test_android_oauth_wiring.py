"""
Guard the Android OAuth redirect wiring (AndroidManifest.xml + MainActivity.kt).

Regression for the July 2026 "Authorization failed: No response received" bug:
after consent, Google's redirect launched OAuthCallbackActivity directly with a
raw VIEW intent (authorization code in intent.data), but the activity reads
AppAuth's serialized extras via AuthorizationResponse.fromIntent() — null for a
raw intent — and the PKCE code_verifier only exists inside AppAuth's in-flight
request, so the code could never be exchanged.

The only correct wiring with AppAuth is:
  1. MainActivity starts the flow with performAuthorizationRequest() and
     completion/cancellation PendingIntents (mutable on Android 12+, because
     AppAuth delivers the result via Intent fill-in).
  2. AppAuth's RedirectUriReceiverActivity is the ONLY activity registered on
     the ${appAuthRedirectScheme} custom scheme (the library declares it; the
     scheme value comes from manifestPlaceholders in build.gradle.kts).
  3. OAuthCallbackActivity is exported=false with no intent filter — reachable
     only through AppAuth's completion PendingIntent, which carries the extras
     fromIntent() needs.

These are cross-file invariants the Android toolchain never checks; each one
individually compiles and only fails at runtime, after real user consent.
"""
import re
import xml.etree.ElementTree as ET
from pathlib import Path

ANDROID_APP = Path(__file__).resolve().parent.parent / "android" / "app"
MANIFEST = ANDROID_APP / "src" / "main" / "AndroidManifest.xml"
MAIN_ACTIVITY = ANDROID_APP / "src" / "main" / "java" / "com" / "fintrack" / "pk" / "ui" / "MainActivity.kt"
BUILD_GRADLE = ANDROID_APP / "build.gradle.kts"

ANDROID_NS = "{http://schemas.android.com/apk/res/android}"


def _activities():
    root = ET.parse(MANIFEST).getroot()
    return root.find("application").findall("activity")


def _find_activity(name_suffix):
    for activity in _activities():
        if activity.get(f"{ANDROID_NS}name", "").endswith(name_suffix):
            return activity
    raise AssertionError(f"{name_suffix} not found in AndroidManifest.xml")


class TestManifestRedirectWiring:
    def test_oauth_callback_activity_has_no_intent_filter(self):
        """A VIEW filter here races AppAuth's RedirectUriReceiverActivity for
        the redirect and receives a raw intent without AppAuth extras."""
        activity = _find_activity("OAuthCallbackActivity")
        assert activity.findall("intent-filter") == [], (
            "OAuthCallbackActivity must not have an intent filter — AppAuth's "
            "RedirectUriReceiverActivity must be the only receiver of the "
            "OAuth redirect scheme"
        )

    def test_oauth_callback_activity_is_not_exported(self):
        activity = _find_activity("OAuthCallbackActivity")
        assert activity.get(f"{ANDROID_NS}exported") == "false", (
            "OAuthCallbackActivity is only launched via AppAuth's completion "
            "PendingIntent and must not be exported"
        )

    def test_no_app_activity_claims_the_redirect_scheme(self):
        """Only the appauth library's receiver may own ${appAuthRedirectScheme}."""
        for activity in _activities():
            name = activity.get(f"{ANDROID_NS}name")
            for intent_filter in activity.findall("intent-filter"):
                for data in intent_filter.findall("data"):
                    scheme = data.get(f"{ANDROID_NS}scheme", "")
                    assert "appAuthRedirectScheme" not in scheme, (
                        f"{name} registers the OAuth redirect scheme; this "
                        "conflicts with AppAuth's RedirectUriReceiverActivity"
                    )

    def test_build_gradle_injects_redirect_scheme_for_all_build_types(self):
        """The scheme placeholder is what AppAuth's receiver registers on;
        without it the redirect has no handler at all."""
        gradle = BUILD_GRADLE.read_text()
        assignments = gradle.count('manifestPlaceholders["appAuthRedirectScheme"]')
        assert assignments >= 4, (  # defaultConfig + release + debug + dev
            "appAuthRedirectScheme manifestPlaceholder missing for some build "
            f"types (found {assignments} assignments, expected >= 4)"
        )


class TestMainActivityLaunchPattern:
    def test_flow_is_started_with_completion_pending_intents(self):
        """performAuthorizationRequest(request, completed, canceled) is the
        only AppAuth API that both keeps the PKCE verifier and delivers the
        response extras to OAuthCallbackActivity."""
        source = MAIN_ACTIVITY.read_text()
        assert "performAuthorizationRequest(" in source

    def test_flow_result_is_not_dropped_via_plain_start_activity(self):
        """getAuthorizationRequestIntent() delivers the result through
        onActivityResult(); launched with startActivity() the result vanishes
        and the user sees 'Authorization failed: No response received'."""
        code = re.sub(r"//[^\n]*", "", MAIN_ACTIVITY.read_text())  # drop comments
        assert "getAuthorizationRequestIntent" not in code

    def test_completion_pending_intents_are_mutable_on_android_12_plus(self):
        """AppAuth fills the response extras into the PendingIntent's Intent
        (Intent fill-in); Android 12+ silently drops fill-in extras for
        immutable PendingIntents, which re-creates the 'No response' bug."""
        source = MAIN_ACTIVITY.read_text()
        launch = re.search(
            r"fun initiateOAuthFlow\(\).*?performAuthorizationRequest\(.*?\n\s*\)",
            source, re.DOTALL,
        )
        assert launch, "initiateOAuthFlow no longer calls performAuthorizationRequest"
        assert "FLAG_MUTABLE" in launch.group(0)
