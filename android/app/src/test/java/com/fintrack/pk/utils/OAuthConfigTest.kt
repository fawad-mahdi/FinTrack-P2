package com.fintrack.pk.utils

import com.fintrack.pk.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Unit tests for the OAuth client configuration in Constants.kt.
 *
 * These guard the invariants Google enforces on Android-type OAuth clients.
 * When they are violated users see "Access blocked: Error 400:
 * invalid_request" in the consent screen and can never connect Gmail:
 * - The client ID must be injected at build time (empty ID = broken build)
 * - The redirect URI must use the reversed-client-ID custom scheme with the
 *   exact form Google documents for native apps ("scheme:/path")
 * - Endpoints and scope must match what OAuthCallbackActivity and the Python
 *   backend (auth_gmail.py) expect
 *
 * NOTE: one Google-side setting cannot be checked from Kotlin: the Android
 * client must have "Custom URI scheme" ENABLED in Google Cloud Console
 * (APIs & Services → Credentials → client → Advanced Settings). That is
 * covered by the live preflight test tests/test_oauth_preflight.py.
 */
class OAuthConfigTest {

    private val clientId = Constants.OAUTH_CLIENT_ID

    @Test
    fun debugBuildsMustHaveClientIdConfigured() {
        // Release builds may legitimately lack the ID on machines without
        // the release gradle properties; debug builds must always have it.
        assumeTrue(BuildConfig.BUILD_TYPE == "debug")
        assertTrue(
            "OAUTH_CLIENT_ID is empty — set FINTRACK_OAUTH_CLIENT_ID_DEBUG " +
                "in ~/.gradle/gradle.properties. Gmail connect is broken in this build.",
            clientId.isNotEmpty()
        )
    }

    @Test
    fun clientIdIsAGoogleOAuthClientId() {
        assumeTrue(clientId.isNotEmpty())
        assertTrue(
            "OAUTH_CLIENT_ID must be a Google OAuth client ID, got: $clientId",
            clientId.endsWith(".apps.googleusercontent.com")
        )
    }

    @Test
    fun redirectUriUsesReversedClientIdCustomScheme() {
        assumeTrue(clientId.isNotEmpty())
        val expectedScheme = "com.googleusercontent.apps." +
            clientId.removeSuffix(".apps.googleusercontent.com")
        // Google's documented native-app form: single slash after the scheme.
        assertEquals("$expectedScheme:/oauth2callback", Constants.OAUTH_REDIRECT_URI)
    }

    @Test
    fun redirectUriHasNoDoubleSlashAuthority() {
        // "scheme://path" (an authority) is a DIFFERENT redirect URI to Google
        // than "scheme:/path" and causes redirect_uri_mismatch.
        assumeTrue(clientId.isNotEmpty())
        assertTrue(!Constants.OAUTH_REDIRECT_URI.contains("://"))
    }

    @Test
    fun authEndpointIsGoogleOAuthV2() {
        assertEquals("https://accounts.google.com/o/oauth2/v2/auth", Constants.OAUTH_AUTH_URI)
    }

    @Test
    fun tokenEndpointMatchesPythonBackend() {
        // auth_gmail.py refreshes against this exact endpoint; token.json's
        // token_uri field is written from this constant.
        assertEquals("https://oauth2.googleapis.com/token", Constants.OAUTH_TOKEN_URI)
    }

    @Test
    fun scopeIsGmailReadonly() {
        assertEquals("https://www.googleapis.com/auth/gmail.readonly", Constants.OAUTH_SCOPE)
    }
}
