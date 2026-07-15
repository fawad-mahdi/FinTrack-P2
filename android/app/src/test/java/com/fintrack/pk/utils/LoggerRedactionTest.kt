package com.fintrack.pk.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [Logger.redactSensitive] (AUTH-11).
 *
 * Verifies that OAuth codes, tokens, and secrets are scrubbed from log
 * lines before they can be written to the log file or exported, while
 * ordinary diagnostic text is left intact.
 */
class LoggerRedactionTest {

    @Test
    fun redactsAccessAndRefreshTokensInJson() {
        val input = """{"access_token":"ya29.SECRETVALUE","refresh_token":"1//REFRESHSECRET"}"""
        val out = Logger.redactSensitive(input)
        assertFalse("access token must be redacted", out.contains("ya29.SECRETVALUE"))
        assertFalse("refresh token must be redacted", out.contains("1//REFRESHSECRET"))
        assertTrue(out.contains("access_token"))
        assertTrue(out.contains("REDACTED"))
    }

    @Test
    fun redactsClientSecret() {
        val input = "client_secret=GOCSPX-supersecret&grant_type=refresh_token"
        val out = Logger.redactSensitive(input)
        assertFalse(out.contains("GOCSPX-supersecret"))
        assertTrue(out.contains("client_secret"))
    }

    @Test
    fun redactsAuthorizationCodeInRedirectUrl() {
        val input = "redirect com.fintrack.pk://oauth2callback?code=4/0AeanSECRETCODE&state=abc123"
        val out = Logger.redactSensitive(input)
        assertFalse("auth code must be redacted", out.contains("4/0AeanSECRETCODE"))
        // The state is not a secret and may remain for correlation
        assertTrue(out.contains("state=abc123"))
    }

    @Test
    fun redactsBareTokenJsonField() {
        val input = """saved {"token": "ya29.PLAINTEXTTOKEN", "expiry": "2026-01-01T00:00:00Z"}"""
        val out = Logger.redactSensitive(input)
        assertFalse(out.contains("ya29.PLAINTEXTTOKEN"))
        assertTrue(out.contains("expiry"))
    }

    @Test
    fun redactsCodeVerifier() {
        val input = "code_verifier=abcdef0123456789VERIFIER"
        val out = Logger.redactSensitive(input)
        assertFalse(out.contains("abcdef0123456789VERIFIER"))
    }

    @Test
    fun leavesOrdinaryDiagnosticsIntact() {
        val input = "Server health check response code: 200 (uptime 1234ms)"
        val out = Logger.redactSensitive(input)
        // "code:" here is a status code, not an OAuth param — must be untouched
        assertTrue(out.contains("response code: 200"))
        assertFalse(out.contains("REDACTED"))
    }
}
