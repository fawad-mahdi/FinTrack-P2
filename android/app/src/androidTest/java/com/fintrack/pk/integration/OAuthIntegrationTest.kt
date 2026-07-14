package com.fintrack.pk.integration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fintrack.pk.utils.GmailTokenBroker
import com.fintrack.pk.utils.Logger
import com.fintrack.pk.utils.OAuthTokenManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant

/**
 * Integration tests for OAuth token storage and lifecycle.
 *
 * Tests verify:
 * - Token validation and expiry checking against the encrypted token store
 * - Legacy plaintext token.json migration into encrypted storage
 * - No refresh token / client secret persisted in plaintext files
 * - Credentials loading and validation
 * - Token lifecycle management
 */
@RunWith(AndroidJUnit4::class)
class OAuthIntegrationTest {

    private lateinit var context: Context
    private lateinit var oauthTokenManager: OAuthTokenManager
    private lateinit var configDir: File
    private lateinit var legacyTokenFile: File
    private lateinit var credentialsFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        oauthTokenManager = OAuthTokenManager(context)

        configDir = File(context.filesDir, "config")
        configDir.mkdirs()

        legacyTokenFile = File(configDir, "token.json")
        credentialsFile = File(configDir, "credentials.json")

        // Clean up any existing state
        GmailTokenBroker.clearTokens(context)
        legacyTokenFile.delete()
        credentialsFile.delete()

        Logger.logInfo("OAuthIntegrationTest", "Test setup complete")
    }

    @After
    fun tearDown() {
        GmailTokenBroker.clearTokens(context)
        legacyTokenFile.delete()
        credentialsFile.delete()

        Logger.logInfo("OAuthIntegrationTest", "Test teardown complete")
    }

    /**
     * Test token existence checking.
     */
    @Test
    fun testTokenExistenceCheck() {
        Logger.logInfo("OAuthIntegrationTest", "Testing token existence check")

        // Initially no token should exist
        assertFalse("Token should not exist initially", oauthTokenManager.hasToken())

        // Store a token in the encrypted store
        storeToken(expired = false)

        // Now token should exist
        assertTrue("Token should exist after creation", oauthTokenManager.hasToken())

        Logger.logInfo("OAuthIntegrationTest", "Token existence check test passed")
    }

    /**
     * Test token expiry checking with valid token.
     */
    @Test
    fun testTokenExpiryCheckValid() {
        Logger.logInfo("OAuthIntegrationTest", "Testing token expiry check with valid token")

        storeToken(expired = false)

        assertFalse("Valid token should not be expired", oauthTokenManager.isTokenExpired())

        Logger.logInfo("OAuthIntegrationTest", "Token expiry check (valid) test passed")
    }

    /**
     * Test token expiry checking with expired token.
     */
    @Test
    fun testTokenExpiryCheckExpired() {
        Logger.logInfo("OAuthIntegrationTest", "Testing token expiry check with expired token")

        storeToken(expired = true)

        assertTrue("Expired token should be detected", oauthTokenManager.isTokenExpired())

        Logger.logInfo("OAuthIntegrationTest", "Token expiry check (expired) test passed")
    }

    /**
     * Test token expiry checking with missing token.
     */
    @Test
    fun testTokenExpiryCheckMissing() {
        Logger.logInfo("OAuthIntegrationTest", "Testing token expiry check with missing token")

        // No token exists
        assertTrue("Missing token should be considered expired", oauthTokenManager.isTokenExpired())

        Logger.logInfo("OAuthIntegrationTest", "Token expiry check (missing) test passed")
    }

    /**
     * Test token deletion.
     */
    @Test
    fun testTokenDeletion() {
        Logger.logInfo("OAuthIntegrationTest", "Testing token deletion")

        storeToken(expired = false)
        assertTrue("Token should exist", oauthTokenManager.hasToken())

        // Delete token
        val deleted = oauthTokenManager.deleteToken()
        assertTrue("Token deletion should succeed", deleted)

        // Token should no longer exist
        assertFalse("Token should not exist after deletion", oauthTokenManager.hasToken())

        Logger.logInfo("OAuthIntegrationTest", "Token deletion test passed")
    }

    /**
     * Test credentials loading.
     */
    @Test
    fun testCredentialsLoading() {
        Logger.logInfo("OAuthIntegrationTest", "Testing credentials loading")

        // Create mock credentials file
        createMockCredentialsFile()

        // Load credentials
        val credentials = oauthTokenManager.loadCredentials()

        assertNotNull("Credentials should be loaded", credentials)
        assertEquals("Client ID should match", "test_client_id", credentials?.clientId)
        assertEquals("Project ID should match", "test_project", credentials?.projectId)
        assertEquals("Auth URI should match", "https://accounts.google.com/o/oauth2/auth", credentials?.authUri)
        assertEquals("Token URI should match", "https://oauth2.googleapis.com/token", credentials?.tokenUri)

        Logger.logInfo("OAuthIntegrationTest", "Credentials loading test passed")
    }

    /**
     * Test credentials loading with missing file.
     */
    @Test
    fun testCredentialsLoadingMissing() {
        Logger.logInfo("OAuthIntegrationTest", "Testing credentials loading with missing file")

        // No credentials file exists
        val credentials = oauthTokenManager.loadCredentials()

        assertNull("Credentials should be null when file missing", credentials)

        Logger.logInfo("OAuthIntegrationTest", "Credentials loading (missing) test passed")
    }

    /**
     * Test ensure valid token with valid token.
     */
    @Test
    fun testEnsureValidTokenWithValidToken() = runBlocking {
        Logger.logInfo("OAuthIntegrationTest", "Testing ensure valid token with valid token")

        storeToken(expired = false)

        // Ensure valid token should succeed
        val valid = oauthTokenManager.ensureValidToken()
        assertTrue("Valid token should pass validation", valid)

        Logger.logInfo("OAuthIntegrationTest", "Ensure valid token (valid) test passed")
    }

    /**
     * Test ensure valid token with missing token.
     */
    @Test
    fun testEnsureValidTokenWithMissingToken() = runBlocking {
        Logger.logInfo("OAuthIntegrationTest", "Testing ensure valid token with missing token")

        // No token exists
        val valid = oauthTokenManager.ensureValidToken()
        assertFalse("Missing token should fail validation", valid)

        Logger.logInfo("OAuthIntegrationTest", "Ensure valid token (missing) test passed")
    }

    /**
     * Test legacy plaintext token.json migration: the file must be imported
     * into encrypted storage and removed from disk, so no refresh token or
     * client secret remains in plaintext.
     */
    @Test
    fun testLegacyTokenFileMigratedToEncryptedStorage() {
        Logger.logInfo("OAuthIntegrationTest", "Testing legacy token.json migration")

        createLegacyTokenFile(expired = false)
        assertTrue("Legacy file should exist before migration", legacyTokenFile.exists())

        // Any token-store access triggers migration
        assertTrue("Migrated token should be available", oauthTokenManager.hasToken())
        assertFalse("Migrated token should not be expired", oauthTokenManager.isTokenExpired())

        // The plaintext file (containing refresh token + client secret) must be gone
        assertFalse("Legacy plaintext token.json must be deleted", legacyTokenFile.exists())
        assertFalse(
            "No stale .oauth_pending.json may remain",
            File(configDir, ".oauth_pending.json").exists()
        )
        assertFalse(
            "No stale .oauth_state.json may remain",
            File(configDir, ".oauth_state.json").exists()
        )

        // A short-lived access token is retrievable through the broker
        assertEquals(
            "Broker should serve the migrated access token",
            "mock_access_token",
            GmailTokenBroker.getAccessToken(context)
        )

        Logger.logInfo("OAuthIntegrationTest", "Legacy token migration test passed")
    }

    /**
     * Test that saving tokens never produces plaintext token files.
     */
    @Test
    fun testNoPlaintextTokenArtifactsAfterSave() {
        Logger.logInfo("OAuthIntegrationTest", "Testing that no plaintext token artifacts are written")

        storeToken(expired = false)

        assertTrue("Token should exist in encrypted store", oauthTokenManager.hasToken())
        assertFalse("No plaintext token.json may be written", legacyTokenFile.exists())

        Logger.logInfo("OAuthIntegrationTest", "No plaintext artifacts test passed")
    }

    /**
     * Test token lifecycle: create, validate, expire, delete.
     */
    @Test
    fun testTokenLifecycle() = runBlocking {
        Logger.logInfo("OAuthIntegrationTest", "Testing token lifecycle")

        // 1. Initially no token
        assertFalse("No token should exist initially", oauthTokenManager.hasToken())

        // 2. Create valid token
        storeToken(expired = false)
        assertTrue("Token should exist", oauthTokenManager.hasToken())
        assertFalse("Token should not be expired", oauthTokenManager.isTokenExpired())

        // 3. Simulate expiry by storing an expired token
        storeToken(expired = true)
        assertTrue("Token should still exist", oauthTokenManager.hasToken())
        assertTrue("Token should be expired", oauthTokenManager.isTokenExpired())

        // 4. Delete token
        oauthTokenManager.deleteToken()
        assertFalse("Token should not exist after deletion", oauthTokenManager.hasToken())

        Logger.logInfo("OAuthIntegrationTest", "Token lifecycle test passed")
    }

    /**
     * Test multiple token operations in sequence.
     */
    @Test
    fun testMultipleTokenOperations() = runBlocking {
        Logger.logInfo("OAuthIntegrationTest", "Testing multiple token operations")

        // Create and delete multiple times
        for (i in 1..3) {
            Logger.logInfo("OAuthIntegrationTest", "Iteration $i")

            storeToken(expired = false)
            assertTrue("Token should exist in iteration $i", oauthTokenManager.hasToken())

            oauthTokenManager.deleteToken()
            assertFalse("Token should not exist after deletion in iteration $i", oauthTokenManager.hasToken())
        }

        Logger.logInfo("OAuthIntegrationTest", "Multiple token operations test passed")
    }

    // Helper methods

    /**
     * Store a token directly in the encrypted token store.
     */
    private fun storeToken(expired: Boolean) {
        val expiry = if (expired) {
            Instant.now().minusSeconds(3600).toString()
        } else {
            Instant.now().plusSeconds(3600).toString()
        }
        GmailTokenBroker.saveTokens(
            context,
            accessToken = "mock_access_token",
            refreshToken = "mock_refresh_token",
            expiryIso = expiry
        )
    }

    /**
     * Create a legacy plaintext token file (pre-encryption format) for
     * migration testing.
     */
    private fun createLegacyTokenFile(expired: Boolean) {
        val expiry = if (expired) {
            Instant.now().minusSeconds(3600).toString()
        } else {
            Instant.now().plusSeconds(3600).toString()
        }

        val tokenContent = """
        {
            "token": "mock_access_token",
            "refresh_token": "mock_refresh_token",
            "token_uri": "https://oauth2.googleapis.com/token",
            "client_id": "test_client_id",
            "client_secret": "test_client_secret",
            "scopes": ["https://www.googleapis.com/auth/gmail.readonly"],
            "expiry": "$expiry"
        }
        """.trimIndent()

        legacyTokenFile.writeText(tokenContent)
    }

    /**
     * Create a mock credentials file for testing.
     */
    private fun createMockCredentialsFile() {
        val credentialsContent = """
        {
            "installed": {
                "client_id": "test_client_id",
                "project_id": "test_project",
                "auth_uri": "https://accounts.google.com/o/oauth2/auth",
                "token_uri": "https://oauth2.googleapis.com/token",
                "auth_provider_x509_cert_url": "https://www.googleapis.com/oauth2/v1/certs",
                "client_secret": "test_client_secret",
                "redirect_uris": ["com.fintrack.pk:/oauth2redirect"]
            }
        }
        """.trimIndent()

        credentialsFile.writeText(credentialsContent)
    }
}
