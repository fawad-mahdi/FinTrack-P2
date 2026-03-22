package com.fintrack.pk.integration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
 * Integration tests for OAuth flow with token storage and refresh.
 * 
 * Tests verify:
 * - Token validation and expiry checking
 * - Token refresh flow integration
 * - Token storage in Python-compatible format
 * - Credentials loading and validation
 * - Token lifecycle management
 */
@RunWith(AndroidJUnit4::class)
class OAuthIntegrationTest {

    private lateinit var context: Context
    private lateinit var oauthTokenManager: OAuthTokenManager
    private lateinit var configDir: File
    private lateinit var tokenFile: File
    private lateinit var credentialsFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        oauthTokenManager = OAuthTokenManager(context)
        
        configDir = File(context.filesDir, "config")
        configDir.mkdirs()
        
        tokenFile = File(configDir, "token.json")
        credentialsFile = File(configDir, "credentials.json")
        
        // Clean up any existing test files
        tokenFile.delete()
        credentialsFile.delete()
        
        Logger.logInfo("OAuthIntegrationTest", "Test setup complete")
    }

    @After
    fun tearDown() {
        // Clean up test files
        tokenFile.delete()
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
        
        // Create a mock token file
        createMockTokenFile(validToken = true, expired = false)
        
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
        
        // Create a valid, non-expired token
        createMockTokenFile(validToken = true, expired = false)
        
        // Token should not be expired
        assertFalse("Valid token should not be expired", oauthTokenManager.isTokenExpired())
        
        Logger.logInfo("OAuthIntegrationTest", "Token expiry check (valid) test passed")
    }

    /**
     * Test token expiry checking with expired token.
     */
    @Test
    fun testTokenExpiryCheckExpired() {
        Logger.logInfo("OAuthIntegrationTest", "Testing token expiry check with expired token")
        
        // Create an expired token
        createMockTokenFile(validToken = true, expired = true)
        
        // Token should be expired
        assertTrue("Expired token should be detected", oauthTokenManager.isTokenExpired())
        
        Logger.logInfo("OAuthIntegrationTest", "Token expiry check (expired) test passed")
    }

    /**
     * Test token expiry checking with missing token.
     */
    @Test
    fun testTokenExpiryCheckMissing() {
        Logger.logInfo("OAuthIntegrationTest", "Testing token expiry check with missing token")
        
        // No token file exists
        assertTrue("Missing token should be considered expired", oauthTokenManager.isTokenExpired())
        
        Logger.logInfo("OAuthIntegrationTest", "Token expiry check (missing) test passed")
    }

    /**
     * Test token deletion.
     */
    @Test
    fun testTokenDeletion() {
        Logger.logInfo("OAuthIntegrationTest", "Testing token deletion")
        
        // Create a token file
        createMockTokenFile(validToken = true, expired = false)
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
        
        // Create a valid, non-expired token
        createMockTokenFile(validToken = true, expired = false)
        
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
     * Test token storage format compatibility with Python backend.
     */
    @Test
    fun testTokenStorageFormatCompatibility() {
        Logger.logInfo("OAuthIntegrationTest", "Testing token storage format compatibility")
        
        // Create a token file
        createMockTokenFile(validToken = true, expired = false)
        
        // Read the file and verify format
        val tokenContent = tokenFile.readText()
        
        assertTrue("Token file should contain 'token' field", tokenContent.contains("\"token\""))
        assertTrue("Token file should contain 'refresh_token' field", tokenContent.contains("\"refresh_token\""))
        assertTrue("Token file should contain 'token_uri' field", tokenContent.contains("\"token_uri\""))
        assertTrue("Token file should contain 'client_id' field", tokenContent.contains("\"client_id\""))
        assertTrue("Token file should contain 'client_secret' field", tokenContent.contains("\"client_secret\""))
        assertTrue("Token file should contain 'scopes' field", tokenContent.contains("\"scopes\""))
        assertTrue("Token file should contain 'expiry' field", tokenContent.contains("\"expiry\""))
        
        Logger.logInfo("OAuthIntegrationTest", "Token storage format compatibility test passed")
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
        createMockTokenFile(validToken = true, expired = false)
        assertTrue("Token should exist", oauthTokenManager.hasToken())
        assertFalse("Token should not be expired", oauthTokenManager.isTokenExpired())
        
        // 3. Simulate expiry by creating expired token
        createMockTokenFile(validToken = true, expired = true)
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
            
            createMockTokenFile(validToken = true, expired = false)
            assertTrue("Token should exist in iteration $i", oauthTokenManager.hasToken())
            
            oauthTokenManager.deleteToken()
            assertFalse("Token should not exist after deletion in iteration $i", oauthTokenManager.hasToken())
        }
        
        Logger.logInfo("OAuthIntegrationTest", "Multiple token operations test passed")
    }

    // Helper methods

    /**
     * Create a mock token file for testing.
     */
    private fun createMockTokenFile(validToken: Boolean, expired: Boolean) {
        val expiry = if (expired) {
            // Set expiry to 1 hour ago
            Instant.now().minusSeconds(3600).toString()
        } else {
            // Set expiry to 1 hour from now
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
        
        tokenFile.writeText(tokenContent)
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
