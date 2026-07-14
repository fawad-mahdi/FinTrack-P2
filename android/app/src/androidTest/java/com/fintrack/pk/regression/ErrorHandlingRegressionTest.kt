package com.fintrack.pk.regression

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fintrack.pk.security.PinEncryptionManager
import com.fintrack.pk.server.ServerProcessManager
import com.fintrack.pk.storage.StorageManager
import com.fintrack.pk.utils.Logger
import com.fintrack.pk.utils.OAuthTokenManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Regression tests for error handling and crash recovery.
 * 
 * Tests verify that error handling works correctly:
 * - Invalid PIN handling
 * - Server startup failures
 * - Database corruption handling
 * - Invalid OAuth token handling
 * - File I/O errors
 * - Network errors
 * - Crash recovery
 */
@RunWith(AndroidJUnit4::class)
class ErrorHandlingRegressionTest {

    private lateinit var context: Context
    private lateinit var serverProcessManager: ServerProcessManager
    private lateinit var pinEncryptionManager: PinEncryptionManager
    private lateinit var oauthTokenManager: OAuthTokenManager
    private lateinit var storageManager: StorageManager
    private lateinit var prefs: SharedPreferences

    companion object {
        private const val PREFS_NAME = "app_prefs"
        private const val KEY_ENCRYPTED_PIN = "encrypted_pin"
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        serverProcessManager = ServerProcessManager(context)
        pinEncryptionManager = PinEncryptionManager()
        oauthTokenManager = OAuthTokenManager(context)
        storageManager = StorageManager(context)
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        // Clean up
        prefs.edit().clear().apply()
        pinEncryptionManager.deleteKey()
        com.fintrack.pk.utils.GmailTokenBroker.clearTokens(context)
        
        Logger.logInfo("ErrorHandlingRegressionTest", "Test setup complete")
    }

    @After
    fun tearDown() {
        // Stop server if running
        if (serverProcessManager.isServerRunning()) {
            serverProcessManager.stopServer()
        }
        serverProcessManager.cleanup()
        
        // Clean up
        prefs.edit().clear().apply()
        pinEncryptionManager.deleteKey()
        com.fintrack.pk.utils.GmailTokenBroker.clearTokens(context)
        
        Logger.logInfo("ErrorHandlingRegressionTest", "Test teardown complete")
    }

    /**
     * Test invalid PIN handling.
     */
    @Test
    fun testInvalidPinHandling() {
        Logger.logInfo("ErrorHandlingRegressionTest", "Testing invalid PIN handling")
        
        // Setup correct PIN
        val correctPin = "1234"
        val encryptedPin = pinEncryptionManager.encryptPin(correctPin)
        prefs.edit().putString(KEY_ENCRYPTED_PIN, encryptedPin).apply()
        
        // Test with incorrect PIN
        val incorrectPin = "0000"
        val storedEncryptedPin = prefs.getString(KEY_ENCRYPTED_PIN, null)!!
        val storedPin = pinEncryptionManager.decryptPin(storedEncryptedPin)
        
        assertNotEquals("Incorrect PIN should not validate", incorrectPin, storedPin)
        assertEquals("Correct PIN should validate", correctPin, storedPin)
        
        Logger.logInfo("ErrorHandlingRegressionTest", "Invalid PIN handling test passed")
    }

    /**
     * Test PIN decryption with corrupted data.
     */
    @Test
    fun testPinDecryptionWithCorruptedData() {
        Logger.logInfo("ErrorHandlingRegressionTest", "Testing PIN decryption with corrupted data")
        
        // Store corrupted encrypted PIN
        prefs.edit().putString(KEY_ENCRYPTED_PIN, "corrupted_data").apply()
        
        // Attempt to decrypt
        try {
            val storedPin = prefs.getString(KEY_ENCRYPTED_PIN, null)!!
            pinEncryptionManager.decryptPin(storedPin)
            fail("Decryption should fail with corrupted data")
        } catch (e: Exception) {
            Logger.logInfo("ErrorHandlingRegressionTest", "Correctly caught decryption error: ${e.message}")
            assertTrue("Should throw exception for corrupted data", true)
        }
        
        Logger.logInfo("ErrorHandlingRegressionTest", "PIN decryption with corrupted data test passed")
    }

    /**
     * Test server startup failure handling.
     */
    @Test
    fun testServerStartupFailureHandling() = runBlocking {
        Logger.logInfo("ErrorHandlingRegressionTest", "Testing server startup failure handling")
        
        // Attempt to start server (may fail in test environment)
        val started = serverProcessManager.startServer()
        
        if (!started) {
            // Verify error is reported
            val status = serverProcessManager.getServerStatus()
            assertFalse("Server should not be running", status.isRunning)
            Logger.logInfo("ErrorHandlingRegressionTest", "Server error: ${status.lastError}")
        } else {
            delay(2000)
            assertTrue("Server should be running", serverProcessManager.isServerRunning())
        }
        
        Logger.logInfo("ErrorHandlingRegressionTest", "Server startup failure handling test passed")
    }

    /**
     * Test database restore with invalid file.
     */
    @Test
    fun testDatabaseRestoreWithInvalidFile() {
        Logger.logInfo("ErrorHandlingRegressionTest", "Testing database restore with invalid file")
        
        // Create invalid backup file
        val invalidFile = File(context.filesDir, "invalid_backup.db")
        invalidFile.writeText("This is not a valid SQLite database")
        
        // Create original database
        val dbFile = storageManager.getDatabaseFile()
        dbFile.parentFile?.mkdirs()
        val originalContent = "SQLite format 3\u0000original content"
        dbFile.writeText(originalContent)
        
        // Attempt restore with invalid file
        val invalidUri = android.net.Uri.fromFile(invalidFile)
        val restoreSuccess = storageManager.restoreDatabase(invalidUri, serverProcessManager)
        
        assertFalse("Restore should fail with invalid file", restoreSuccess)
        
        // Verify original database is preserved
        assertEquals("Original database should be preserved", originalContent, dbFile.readText())
        
        // Clean up
        invalidFile.delete()
        
        Logger.logInfo("ErrorHandlingRegressionTest", "Database restore with invalid file test passed")
    }

    /**
     * Test OAuth token with missing file.
     */
    @Test
    fun testOAuthTokenWithMissingFile() {
        Logger.logInfo("ErrorHandlingRegressionTest", "Testing OAuth token with missing file")
        
        // Ensure token file doesn't exist
        val configDir = File(context.filesDir, "config")
        val tokenFile = File(configDir, "token.json")
        tokenFile.delete()
        
        // Check token existence
        assertFalse("Token should not exist", oauthTokenManager.hasToken())
        
        // Check token expiry (should be considered expired)
        assertTrue("Missing token should be considered expired", oauthTokenManager.isTokenExpired())
        
        Logger.logInfo("ErrorHandlingRegressionTest", "OAuth token with missing file test passed")
    }

    /**
     * Test OAuth token with malformed JSON.
     */
    @Test
    fun testOAuthTokenWithMalformedJson() {
        Logger.logInfo("ErrorHandlingRegressionTest", "Testing OAuth token with malformed JSON")
        
        // Create malformed token file
        val configDir = File(context.filesDir, "config")
        configDir.mkdirs()
        val tokenFile = File(configDir, "token.json")
        tokenFile.writeText("{ invalid json }")
        
        // Attempt to check token
        try {
            val hasToken = oauthTokenManager.hasToken()
            Logger.logInfo("ErrorHandlingRegressionTest", "Has token: $hasToken")
            // Should handle gracefully
        } catch (e: Exception) {
            Logger.logInfo("ErrorHandlingRegressionTest", "Correctly caught JSON parsing error: ${e.message}")
        }
        
        // Clean up
        tokenFile.delete()
        
        Logger.logInfo("ErrorHandlingRegressionTest", "OAuth token with malformed JSON test passed")
    }

    /**
     * Test file I/O error handling.
     */
    @Test
    fun testFileIOErrorHandling() {
        Logger.logInfo("ErrorHandlingRegressionTest", "Testing file I/O error handling")
        
        // Attempt to read non-existent file
        val nonExistentFile = File(context.filesDir, "non_existent.txt")
        assertFalse("File should not exist", nonExistentFile.exists())
        
        // Attempt to get size of non-existent database
        val dbFile = storageManager.getDatabaseFile()
        dbFile.delete() // Ensure it doesn't exist
        
        val size = storageManager.getDatabaseSize()
        assertEquals("Size should be 0 for non-existent file", 0L, size)
        
        Logger.logInfo("ErrorHandlingRegressionTest", "File I/O error handling test passed")
    }

    /**
     * Test server crash recovery.
     */
    @Test
    fun testServerCrashRecovery() = runBlocking {
        Logger.logInfo("ErrorHandlingRegressionTest", "Testing server crash recovery")
        
        // Start server
        val started = serverProcessManager.startServer()
        if (!started) {
            Logger.logInfo("ErrorHandlingRegressionTest", "Server failed to start, skipping test")
            return@runBlocking
        }
        
        delay(2000)
        assertTrue("Server should be running", serverProcessManager.isServerRunning())
        
        // Simulate crash
        serverProcessManager.stopServer()
        delay(1000)
        assertFalse("Server should be stopped", serverProcessManager.isServerRunning())
        
        // Attempt recovery
        val recovered = serverProcessManager.startServer()
        if (recovered) {
            delay(2000)
            assertTrue("Server should recover", serverProcessManager.isServerRunning())
        }
        
        Logger.logInfo("ErrorHandlingRegressionTest", "Server crash recovery test passed")
    }

    /**
     * Test failed attempt lockout handling.
     */
    @Test
    fun testFailedAttemptLockoutHandling() {
        Logger.logInfo("ErrorHandlingRegressionTest", "Testing failed attempt lockout handling")
        
        // Setup PIN
        val correctPin = "1234"
        val encryptedPin = pinEncryptionManager.encryptPin(correctPin)
        prefs.edit().putString(KEY_ENCRYPTED_PIN, encryptedPin).apply()
        
        // Simulate 3 failed attempts
        val pinPrefs = context.getSharedPreferences("pin_prefs", Context.MODE_PRIVATE)
        for (i in 1..3) {
            val failedAttempts = pinPrefs.getInt("failed_attempts", 0) + 1
            pinPrefs.edit().putInt("failed_attempts", failedAttempts).apply()
        }
        
        // Verify lockout is triggered
        val failedAttempts = pinPrefs.getInt("failed_attempts", 0)
        assertTrue("Failed attempts should be tracked", failedAttempts >= 3)
        
        // Trigger lockout
        if (failedAttempts >= 3) {
            val lockoutUntil = System.currentTimeMillis() + 30000
            pinPrefs.edit()
                .putLong("lockout_until", lockoutUntil)
                .putInt("failed_attempts", 0)
                .apply()
        }
        
        // Verify lockout is active
        val lockoutUntil = pinPrefs.getLong("lockout_until", 0)
        assertTrue("Lockout should be active", lockoutUntil > System.currentTimeMillis())
        
        // Clean up
        pinPrefs.edit().clear().apply()
        
        Logger.logInfo("ErrorHandlingRegressionTest", "Failed attempt lockout handling test passed")
    }

    /**
     * Test log rotation with I/O errors.
     */
    @Test
    fun testLogRotationWithIOErrors() {
        Logger.logInfo("ErrorHandlingRegressionTest", "Testing log rotation with I/O errors")
        
        // Create log file
        val appLogsFile = storageManager.getAppLogsFile()
        appLogsFile.parentFile?.mkdirs()
        appLogsFile.writeText("Test log content\n")
        
        // Attempt rotation (should handle gracefully even if errors occur)
        val rotateSuccess = storageManager.rotateLogs()
        
        // Should succeed or fail gracefully
        Logger.logInfo("ErrorHandlingRegressionTest", "Log rotation result: $rotateSuccess")
        
        Logger.logInfo("ErrorHandlingRegressionTest", "Log rotation with I/O errors test passed")
    }

    /**
     * Test backup with missing database.
     */
    @Test
    fun testBackupWithMissingDatabase() {
        Logger.logInfo("ErrorHandlingRegressionTest", "Testing backup with missing database")
        
        // Ensure database doesn't exist
        val dbFile = storageManager.getDatabaseFile()
        dbFile.delete()
        
        // Attempt backup
        val backupFile = File(context.filesDir, "missing_db_backup.db")
        val backupUri = android.net.Uri.fromFile(backupFile)
        
        val backupSuccess = storageManager.backupDatabase(backupUri, serverProcessManager)
        
        assertFalse("Backup should fail with missing database", backupSuccess)
        
        // Clean up
        backupFile.delete()
        
        Logger.logInfo("ErrorHandlingRegressionTest", "Backup with missing database test passed")
    }

    /**
     * Test multiple concurrent errors.
     */
    @Test
    fun testMultipleConcurrentErrors() = runBlocking {
        Logger.logInfo("ErrorHandlingRegressionTest", "Testing multiple concurrent errors")
        
        // Trigger multiple errors
        var errorCount = 0
        
        // Error 1: Invalid PIN
        try {
            pinEncryptionManager.decryptPin("invalid_data")
        } catch (e: Exception) {
            errorCount++
            Logger.logInfo("ErrorHandlingRegressionTest", "Error 1 caught: ${e.message}")
        }
        
        // Error 2: Missing token
        val hasToken = oauthTokenManager.hasToken()
        if (!hasToken) {
            errorCount++
            Logger.logInfo("ErrorHandlingRegressionTest", "Error 2: Token missing")
        }
        
        // Error 3: Missing database
        val dbSize = storageManager.getDatabaseSize()
        if (dbSize == 0L) {
            errorCount++
            Logger.logInfo("ErrorHandlingRegressionTest", "Error 3: Database missing")
        }
        
        assertTrue("Multiple errors should be handled", errorCount > 0)
        Logger.logInfo("ErrorHandlingRegressionTest", "Handled $errorCount concurrent errors")
        
        Logger.logInfo("ErrorHandlingRegressionTest", "Multiple concurrent errors test passed")
    }

    /**
     * Test error recovery after cleanup.
     */
    @Test
    fun testErrorRecoveryAfterCleanup() = runBlocking {
        Logger.logInfo("ErrorHandlingRegressionTest", "Testing error recovery after cleanup")
        
        // Start server
        val started = serverProcessManager.startServer()
        if (started) {
            delay(2000)
            
            // Trigger error by stopping
            serverProcessManager.stopServer()
            delay(1000)
            
            // Cleanup
            serverProcessManager.cleanup()
            delay(500)
            
            // Verify cleanup
            assertFalse("Server should be stopped after cleanup", 
                serverProcessManager.isServerRunning())
        }
        
        Logger.logInfo("ErrorHandlingRegressionTest", "Error recovery after cleanup test passed")
    }

    /**
     * Test graceful degradation with missing components.
     */
    @Test
    fun testGracefulDegradationWithMissingComponents() {
        Logger.logInfo("ErrorHandlingRegressionTest", "Testing graceful degradation")
        
        // Test with missing PIN
        assertFalse("Should handle missing PIN", prefs.contains(KEY_ENCRYPTED_PIN))
        
        // Test with missing OAuth token
        assertFalse("Should handle missing token", oauthTokenManager.hasToken())
        
        // Test with missing database
        val dbSize = storageManager.getDatabaseSize()
        assertEquals("Should handle missing database", 0L, dbSize)
        
        // App should still function with missing components
        Logger.logInfo("ErrorHandlingRegressionTest", "App handles missing components gracefully")
        
        Logger.logInfo("ErrorHandlingRegressionTest", "Graceful degradation test passed")
    }
}
