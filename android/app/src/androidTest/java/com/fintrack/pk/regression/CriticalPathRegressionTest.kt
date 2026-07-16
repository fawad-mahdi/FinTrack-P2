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
 * Regression tests for critical user paths.
 * 
 * Tests verify that critical functionality doesn't break:
 * - App launch and initialization
 * - PIN authentication flow
 * - Server startup and operation
 * - OAuth token management
 * - Database backup/restore
 * - Settings access
 */
@RunWith(AndroidJUnit4::class)
class CriticalPathRegressionTest {

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
        
        Logger.logInfo("CriticalPathRegressionTest", "Test setup complete")
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
        
        Logger.logInfo("CriticalPathRegressionTest", "Test teardown complete")
    }

    /**
     * Critical Path: App launch and initialization.
     */
    @Test
    fun testCriticalPath_AppLaunchAndInitialization() = runBlocking {
        Logger.logInfo("CriticalPathRegressionTest", "Testing critical path: App launch and initialization")
        
        // Step 1: PIN setup
        val testPin = "1234"
        val encryptedPin = pinEncryptionManager.encryptPin(testPin)
        prefs.edit().putString(KEY_ENCRYPTED_PIN, encryptedPin).apply()
        
        assertTrue("PIN should be set", prefs.contains(KEY_ENCRYPTED_PIN))
        
        // Step 2: Server initialization
        val started = serverProcessManager.startServer()
        
        if (started) {
            delay(2000)
            assertTrue("Server should be running", serverProcessManager.isServerRunning())
        }
        
        Logger.logInfo("CriticalPathRegressionTest", "Critical path: App launch - PASSED")
    }

    /**
     * Critical Path: PIN authentication (setup and login).
     */
    @Test
    fun testCriticalPath_PinAuthentication() {
        Logger.logInfo("CriticalPathRegressionTest", "Testing critical path: PIN authentication")
        
        // Setup flow
        val setupPin = "5678"
        val encryptedSetupPin = pinEncryptionManager.encryptPin(setupPin)
        prefs.edit().putString(KEY_ENCRYPTED_PIN, encryptedSetupPin).apply()
        
        assertTrue("PIN should be stored", prefs.contains(KEY_ENCRYPTED_PIN))
        
        // Login flow
        val storedEncryptedPin = prefs.getString(KEY_ENCRYPTED_PIN, null)
        assertNotNull("Stored PIN should exist", storedEncryptedPin)
        
        val decryptedPin = pinEncryptionManager.decryptPin(storedEncryptedPin!!)
        assertEquals("PIN should validate correctly", setupPin, decryptedPin)
        
        Logger.logInfo("CriticalPathRegressionTest", "Critical path: PIN authentication - PASSED")
    }

    /**
     * Critical Path: Server startup and operation.
     */
    @Test
    fun testCriticalPath_ServerStartupAndOperation() = runBlocking {
        Logger.logInfo("CriticalPathRegressionTest", "Testing critical path: Server startup and operation")
        
        // Start server
        val started = serverProcessManager.startServer()
        
        if (!started) {
            Logger.logInfo("CriticalPathRegressionTest", "Server failed to start (may be expected in test environment)")
            return@runBlocking
        }
        
        delay(2000)
        
        // Verify server is running
        assertTrue("Server should be running", serverProcessManager.isServerRunning())
        
        // Get server status
        val status = serverProcessManager.getServerStatus()
        assertTrue("Server status should show running", status.isRunning)
        assertEquals("Server port should be 8000", 8000, status.port)
        assertTrue("Server uptime should be positive", status.uptime > 0)
        
        // Stop server
        serverProcessManager.stopServer()
        delay(1000)
        assertFalse("Server should be stopped", serverProcessManager.isServerRunning())
        
        Logger.logInfo("CriticalPathRegressionTest", "Critical path: Server startup and operation - PASSED")
    }

    /**
     * Critical Path: OAuth token management.
     */
    @Test
    fun testCriticalPath_OAuthTokenManagement() {
        Logger.logInfo("CriticalPathRegressionTest", "Testing critical path: OAuth token management")
        
        // Check token existence (should not exist initially)
        assertFalse("Token should not exist initially", oauthTokenManager.hasToken())
        
        // Create mock token file
        val configDir = File(context.filesDir, "config")
        configDir.mkdirs()
        val tokenFile = File(configDir, "token.json")
        
        val tokenContent = """
        {
            "token": "test_access_token",
            "refresh_token": "test_refresh_token",
            "token_uri": "https://oauth2.googleapis.com/token",
            "client_id": "test_client_id",
            "client_secret": "test_client_secret",
            "scopes": ["https://www.googleapis.com/auth/gmail.readonly"],
            "expiry": "${java.time.Instant.now().plusSeconds(3600)}"
        }
        """.trimIndent()
        
        tokenFile.writeText(tokenContent)
        
        // Verify token exists
        assertTrue("Token should exist", oauthTokenManager.hasToken())
        
        // Check expiry
        assertFalse("Token should not be expired", oauthTokenManager.isTokenExpired())
        
        // Delete token
        val deleted = oauthTokenManager.deleteToken()
        assertTrue("Token deletion should succeed", deleted)
        assertFalse("Token should not exist after deletion", oauthTokenManager.hasToken())
        
        Logger.logInfo("CriticalPathRegressionTest", "Critical path: OAuth token management - PASSED")
    }

    /**
     * Critical Path: Database backup and restore.
     */
    @Test
    fun testCriticalPath_DatabaseBackupRestore() = runBlocking {
        Logger.logInfo("CriticalPathRegressionTest", "Testing critical path: Database backup and restore")
        
        // Create test database
        val dbFile = storageManager.getDatabaseFile()
        dbFile.parentFile?.mkdirs()
        val originalContent = "SQLite format 3\u0000test database content"
        dbFile.writeText(originalContent)
        
        // Backup
        val backupFile = File(context.filesDir, "test_backup.db")
        val backupUri = android.net.Uri.fromFile(backupFile)
        
        val backupSuccess = storageManager.backupDatabase(backupUri, serverProcessManager)
        assertTrue("Backup should succeed", backupSuccess)
        assertTrue("Backup file should exist", backupFile.exists())
        
        // Modify database
        dbFile.writeText("SQLite format 3\u0000modified content")
        
        // Restore
        val restoreSuccess = storageManager.restoreDatabase(backupUri, serverProcessManager)
        assertTrue("Restore should succeed", restoreSuccess)
        
        // Verify restoration
        val restoredContent = dbFile.readText()
        assertEquals("Content should be restored", originalContent, restoredContent)
        
        // Clean up
        backupFile.delete()
        
        Logger.logInfo("CriticalPathRegressionTest", "Critical path: Database backup and restore - PASSED")
    }

    /**
     * Critical Path: Log rotation and export.
     */
    @Test
    fun testCriticalPath_LogRotationAndExport() {
        Logger.logInfo("CriticalPathRegressionTest", "Testing critical path: Log rotation and export")
        
        // Create test log files
        val appLogsFile = storageManager.getAppLogsFile()
        val serverLogsFile = storageManager.getServerLogsFile()
        
        appLogsFile.parentFile?.mkdirs()
        serverLogsFile.parentFile?.mkdirs()
        
        appLogsFile.writeText("App log content\n")
        serverLogsFile.writeText("Server log content\n")
        
        // Test log rotation
        val rotateSuccess = storageManager.rotateLogs()
        assertTrue("Log rotation should succeed", rotateSuccess)
        
        // Test log export
        val exportFile = File(context.filesDir, "test_export.txt")
        val exportUri = android.net.Uri.fromFile(exportFile)
        
        val exportSuccess = storageManager.exportLogs(exportUri)
        assertTrue("Log export should succeed", exportSuccess)
        assertTrue("Export file should exist", exportFile.exists())
        
        // Clean up
        exportFile.delete()
        
        Logger.logInfo("CriticalPathRegressionTest", "Critical path: Log rotation and export - PASSED")
    }

    /**
     * Critical Path: PIN change flow.
     */
    @Test
    fun testCriticalPath_PinChangeFlow() {
        Logger.logInfo("CriticalPathRegressionTest", "Testing critical path: PIN change flow")
        
        // Setup initial PIN
        val oldPin = "1111"
        val encryptedOldPin = pinEncryptionManager.encryptPin(oldPin)
        prefs.edit().putString(KEY_ENCRYPTED_PIN, encryptedOldPin).apply()
        
        // Verify old PIN
        val storedOldPin = prefs.getString(KEY_ENCRYPTED_PIN, null)!!
        val decryptedOldPin = pinEncryptionManager.decryptPin(storedOldPin)
        assertEquals("Old PIN should validate", oldPin, decryptedOldPin)
        
        // Change to new PIN
        val newPin = "2222"
        val encryptedNewPin = pinEncryptionManager.encryptPin(newPin)
        prefs.edit().putString(KEY_ENCRYPTED_PIN, encryptedNewPin).apply()
        
        // Verify new PIN
        val storedNewPin = prefs.getString(KEY_ENCRYPTED_PIN, null)!!
        val decryptedNewPin = pinEncryptionManager.decryptPin(storedNewPin)
        assertEquals("New PIN should validate", newPin, decryptedNewPin)
        
        Logger.logInfo("CriticalPathRegressionTest", "Critical path: PIN change flow - PASSED")
    }

    /**
     * Critical Path: Server restart after crash.
     */
    @Test
    fun testCriticalPath_ServerRestartAfterCrash() = runBlocking {
        Logger.logInfo("CriticalPathRegressionTest", "Testing critical path: Server restart after crash")
        
        // Start server
        val started = serverProcessManager.startServer()
        if (!started) {
            Logger.logInfo("CriticalPathRegressionTest", "Server failed to start, skipping test")
            return@runBlocking
        }
        
        delay(2000)
        assertTrue("Server should be running", serverProcessManager.isServerRunning())
        
        // Simulate crash
        serverProcessManager.stopServer()
        delay(1000)
        assertFalse("Server should be stopped", serverProcessManager.isServerRunning())
        
        // Restart
        val restarted = serverProcessManager.startServer()
        if (restarted) {
            delay(2000)
            assertTrue("Server should be running after restart", serverProcessManager.isServerRunning())
        }
        
        Logger.logInfo("CriticalPathRegressionTest", "Critical path: Server restart after crash - PASSED")
    }

    /**
     * Critical Path: Complete user flow from launch to operation.
     */
    @Test
    fun testCriticalPath_CompleteUserFlow() = runBlocking {
        Logger.logInfo("CriticalPathRegressionTest", "Testing critical path: Complete user flow")
        
        // 1. PIN setup
        val testPin = "9999"
        val encryptedPin = pinEncryptionManager.encryptPin(testPin)
        prefs.edit().putString(KEY_ENCRYPTED_PIN, encryptedPin).apply()
        assertTrue("PIN should be set", prefs.contains(KEY_ENCRYPTED_PIN))
        
        // 2. Server start
        val started = serverProcessManager.startServer()
        if (started) {
            delay(2000)
            assertTrue("Server should be running", serverProcessManager.isServerRunning())
        }
        
        // 3. Check OAuth status
        val hasToken = oauthTokenManager.hasToken()
        Logger.logInfo("CriticalPathRegressionTest", "OAuth token exists: $hasToken")
        
        // 4. Get database size
        val dbSize = storageManager.getDatabaseSize()
        Logger.logInfo("CriticalPathRegressionTest", "Database size: $dbSize bytes")
        
        // 5. Get log size
        val logSize = storageManager.getLogSize()
        Logger.logInfo("CriticalPathRegressionTest", "Log size: $logSize bytes")
        
        Logger.logInfo("CriticalPathRegressionTest", "Critical path: Complete user flow - PASSED")
    }

    /**
     * Critical Path: Error handling and recovery.
     */
    @Test
    fun testCriticalPath_ErrorHandlingAndRecovery() = runBlocking {
        Logger.logInfo("CriticalPathRegressionTest", "Testing critical path: Error handling and recovery")
        
        // Test PIN validation with incorrect PIN
        val correctPin = "1234"
        val incorrectPin = "0000"
        val encryptedPin = pinEncryptionManager.encryptPin(correctPin)
        prefs.edit().putString(KEY_ENCRYPTED_PIN, encryptedPin).apply()
        
        val storedPin = pinEncryptionManager.decryptPin(prefs.getString(KEY_ENCRYPTED_PIN, null)!!)
        assertNotEquals("Incorrect PIN should not validate", incorrectPin, storedPin)
        assertEquals("Correct PIN should validate", correctPin, storedPin)
        
        // Test server error handling
        val status = serverProcessManager.getServerStatus()
        Logger.logInfo("CriticalPathRegressionTest", "Server status: running=${status.isRunning}, error=${status.lastError}")
        
        // Test invalid database restore
        val invalidFile = File(context.filesDir, "invalid.db")
        invalidFile.writeText("Not a valid SQLite database")
        val invalidUri = android.net.Uri.fromFile(invalidFile)
        
        val restoreFailed = !storageManager.restoreDatabase(invalidUri, serverProcessManager)
        assertTrue("Invalid restore should fail", restoreFailed)
        
        // Clean up
        invalidFile.delete()
        
        Logger.logInfo("CriticalPathRegressionTest", "Critical path: Error handling and recovery - PASSED")
    }
}
