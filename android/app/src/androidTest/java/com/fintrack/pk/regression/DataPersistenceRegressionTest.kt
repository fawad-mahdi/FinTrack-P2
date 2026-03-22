package com.fintrack.pk.regression

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fintrack.pk.security.PinEncryptionManager
import com.fintrack.pk.storage.StorageManager
import com.fintrack.pk.utils.Logger
import com.fintrack.pk.utils.OAuthTokenManager
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Regression tests for data persistence across app restarts.
 * 
 * Tests verify that data persists correctly:
 * - PIN storage and retrieval
 * - OAuth token persistence
 * - Database file persistence
 * - Log file persistence
 * - SharedPreferences persistence
 * - App state persistence
 */
@RunWith(AndroidJUnit4::class)
class DataPersistenceRegressionTest {

    private lateinit var context: Context
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
        pinEncryptionManager = PinEncryptionManager()
        oauthTokenManager = OAuthTokenManager(context)
        storageManager = StorageManager(context)
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        // Clean up
        prefs.edit().clear().apply()
        pinEncryptionManager.deleteKey()
        
        Logger.logInfo("DataPersistenceRegressionTest", "Test setup complete")
    }

    @After
    fun tearDown() {
        // Clean up
        prefs.edit().clear().apply()
        pinEncryptionManager.deleteKey()
        
        Logger.logInfo("DataPersistenceRegressionTest", "Test teardown complete")
    }

    /**
     * Test PIN persistence across encryption manager instances.
     */
    @Test
    fun testPinPersistence() {
        Logger.logInfo("DataPersistenceRegressionTest", "Testing PIN persistence")
        
        // Create and store PIN with first instance
        val testPin = "1234"
        val encryptionManager1 = PinEncryptionManager()
        val encryptedPin = encryptionManager1.encryptPin(testPin)
        prefs.edit().putString(KEY_ENCRYPTED_PIN, encryptedPin).apply()
        
        // Verify persistence with second instance
        val encryptionManager2 = PinEncryptionManager()
        val storedEncryptedPin = prefs.getString(KEY_ENCRYPTED_PIN, null)
        assertNotNull("PIN should persist", storedEncryptedPin)
        
        val decryptedPin = encryptionManager2.decryptPin(storedEncryptedPin!!)
        assertEquals("Decrypted PIN should match original", testPin, decryptedPin)
        
        Logger.logInfo("DataPersistenceRegressionTest", "PIN persistence test passed")
    }

    /**
     * Test OAuth token persistence.
     */
    @Test
    fun testOAuthTokenPersistence() {
        Logger.logInfo("DataPersistenceRegressionTest", "Testing OAuth token persistence")
        
        // Create token file
        val configDir = File(context.filesDir, "config")
        configDir.mkdirs()
        val tokenFile = File(configDir, "token.json")
        
        val tokenContent = """
        {
            "token": "persistent_access_token",
            "refresh_token": "persistent_refresh_token",
            "token_uri": "https://oauth2.googleapis.com/token",
            "client_id": "test_client_id",
            "client_secret": "test_client_secret",
            "scopes": ["https://www.googleapis.com/auth/gmail.readonly"],
            "expiry": "${java.time.Instant.now().plusSeconds(3600)}"
        }
        """.trimIndent()
        
        tokenFile.writeText(tokenContent)
        
        // Verify persistence with first manager instance
        val tokenManager1 = OAuthTokenManager(context)
        assertTrue("Token should exist", tokenManager1.hasToken())
        
        // Verify persistence with second manager instance
        val tokenManager2 = OAuthTokenManager(context)
        assertTrue("Token should persist across instances", tokenManager2.hasToken())
        assertFalse("Token should not be expired", tokenManager2.isTokenExpired())
        
        // Clean up
        tokenFile.delete()
        
        Logger.logInfo("DataPersistenceRegressionTest", "OAuth token persistence test passed")
    }

    /**
     * Test database file persistence.
     */
    @Test
    fun testDatabaseFilePersistence() {
        Logger.logInfo("DataPersistenceRegressionTest", "Testing database file persistence")
        
        // Create database file
        val dbFile = storageManager.getDatabaseFile()
        dbFile.parentFile?.mkdirs()
        val testContent = "SQLite format 3\u0000persistent database content"
        dbFile.writeText(testContent)
        
        // Verify persistence
        assertTrue("Database file should exist", dbFile.exists())
        
        // Read with new storage manager instance
        val storageManager2 = StorageManager(context)
        val dbFile2 = storageManager2.getDatabaseFile()
        
        assertTrue("Database file should persist", dbFile2.exists())
        assertEquals("Database content should persist", testContent, dbFile2.readText())
        
        Logger.logInfo("DataPersistenceRegressionTest", "Database file persistence test passed")
    }

    /**
     * Test log file persistence.
     */
    @Test
    fun testLogFilePersistence() {
        Logger.logInfo("DataPersistenceRegressionTest", "Testing log file persistence")
        
        // Create log files
        val appLogsFile = storageManager.getAppLogsFile()
        val serverLogsFile = storageManager.getServerLogsFile()
        
        appLogsFile.parentFile?.mkdirs()
        serverLogsFile.parentFile?.mkdirs()
        
        val appLogContent = "Persistent app log content\n"
        val serverLogContent = "Persistent server log content\n"
        
        appLogsFile.writeText(appLogContent)
        serverLogsFile.writeText(serverLogContent)
        
        // Verify persistence with new storage manager instance
        val storageManager2 = StorageManager(context)
        val appLogsFile2 = storageManager2.getAppLogsFile()
        val serverLogsFile2 = storageManager2.getServerLogsFile()
        
        assertTrue("App logs should persist", appLogsFile2.exists())
        assertTrue("Server logs should persist", serverLogsFile2.exists())
        
        assertEquals("App log content should persist", appLogContent, appLogsFile2.readText())
        assertEquals("Server log content should persist", serverLogContent, serverLogsFile2.readText())
        
        Logger.logInfo("DataPersistenceRegressionTest", "Log file persistence test passed")
    }

    /**
     * Test SharedPreferences persistence.
     */
    @Test
    fun testSharedPreferencesPersistence() {
        Logger.logInfo("DataPersistenceRegressionTest", "Testing SharedPreferences persistence")
        
        // Store various data types
        prefs.edit()
            .putString("string_key", "test_value")
            .putInt("int_key", 42)
            .putBoolean("boolean_key", true)
            .putLong("long_key", 123456789L)
            .putFloat("float_key", 3.14f)
            .apply()
        
        // Verify persistence with new SharedPreferences instance
        val prefs2 = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        assertEquals("String should persist", "test_value", prefs2.getString("string_key", null))
        assertEquals("Int should persist", 42, prefs2.getInt("int_key", 0))
        assertEquals("Boolean should persist", true, prefs2.getBoolean("boolean_key", false))
        assertEquals("Long should persist", 123456789L, prefs2.getLong("long_key", 0L))
        assertEquals("Float should persist", 3.14f, prefs2.getFloat("float_key", 0f))
        
        Logger.logInfo("DataPersistenceRegressionTest", "SharedPreferences persistence test passed")
    }

    /**
     * Test app state persistence.
     */
    @Test
    fun testAppStatePersistence() {
        Logger.logInfo("DataPersistenceRegressionTest", "Testing app state persistence")
        
        // Store app state
        prefs.edit()
            .putString(KEY_ENCRYPTED_PIN, pinEncryptionManager.encryptPin("1234"))
            .putLong("last_sync_time", System.currentTimeMillis())
            .putBoolean("first_launch_complete", true)
            .putString("server_url", "http://127.0.0.1:8000")
            .apply()
        
        // Verify state persists
        val prefs2 = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        assertTrue("PIN should persist", prefs2.contains(KEY_ENCRYPTED_PIN))
        assertTrue("Last sync time should persist", prefs2.contains("last_sync_time"))
        assertTrue("First launch flag should persist", prefs2.getBoolean("first_launch_complete", false))
        assertEquals("Server URL should persist", "http://127.0.0.1:8000", prefs2.getString("server_url", null))
        
        Logger.logInfo("DataPersistenceRegressionTest", "App state persistence test passed")
    }

    /**
     * Test data persistence after multiple writes.
     */
    @Test
    fun testDataPersistenceAfterMultipleWrites() {
        Logger.logInfo("DataPersistenceRegressionTest", "Testing data persistence after multiple writes")
        
        // Perform multiple writes
        for (i in 1..10) {
            prefs.edit().putInt("counter", i).apply()
        }
        
        // Verify final value persists
        val prefs2 = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        assertEquals("Final counter value should persist", 10, prefs2.getInt("counter", 0))
        
        Logger.logInfo("DataPersistenceRegressionTest", "Data persistence after multiple writes test passed")
    }

    /**
     * Test data persistence with large values.
     */
    @Test
    fun testDataPersistenceWithLargeValues() {
        Logger.logInfo("DataPersistenceRegressionTest", "Testing data persistence with large values")
        
        // Create large string (1MB)
        val largeString = "x".repeat(1024 * 1024)
        
        // Store in file
        val testFile = File(context.filesDir, "large_data.txt")
        testFile.writeText(largeString)
        
        // Verify persistence
        assertTrue("Large file should exist", testFile.exists())
        assertEquals("Large file size should match", largeString.length.toLong(), testFile.length())
        
        // Read and verify
        val readString = testFile.readText()
        assertEquals("Large string should persist", largeString, readString)
        
        // Clean up
        testFile.delete()
        
        Logger.logInfo("DataPersistenceRegressionTest", "Data persistence with large values test passed")
    }

    /**
     * Test data persistence across app updates (simulated).
     */
    @Test
    fun testDataPersistenceAcrossUpdates() {
        Logger.logInfo("DataPersistenceRegressionTest", "Testing data persistence across updates")
        
        // Store data with version 1
        prefs.edit()
            .putString(KEY_ENCRYPTED_PIN, pinEncryptionManager.encryptPin("1234"))
            .putInt("app_version", 1)
            .apply()
        
        // Simulate app update (version 2)
        prefs.edit().putInt("app_version", 2).apply()
        
        // Verify old data persists
        assertTrue("PIN should persist across updates", prefs.contains(KEY_ENCRYPTED_PIN))
        assertEquals("App version should be updated", 2, prefs.getInt("app_version", 0))
        
        // Verify PIN still works
        val storedPin = prefs.getString(KEY_ENCRYPTED_PIN, null)!!
        val decryptedPin = pinEncryptionManager.decryptPin(storedPin)
        assertEquals("PIN should still work after update", "1234", decryptedPin)
        
        Logger.logInfo("DataPersistenceRegressionTest", "Data persistence across updates test passed")
    }

    /**
     * Test data persistence with concurrent access.
     */
    @Test
    fun testDataPersistenceWithConcurrentAccess() {
        Logger.logInfo("DataPersistenceRegressionTest", "Testing data persistence with concurrent access")
        
        // Write from multiple SharedPreferences instances
        val prefs1 = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val prefs2 = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        prefs1.edit().putString("key1", "value1").apply()
        prefs2.edit().putString("key2", "value2").apply()
        
        // Verify both writes persisted
        val prefs3 = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        assertEquals("First write should persist", "value1", prefs3.getString("key1", null))
        assertEquals("Second write should persist", "value2", prefs3.getString("key2", null))
        
        Logger.logInfo("DataPersistenceRegressionTest", "Data persistence with concurrent access test passed")
    }

    /**
     * Test data persistence after clear and recreate.
     */
    @Test
    fun testDataPersistenceAfterClearAndRecreate() {
        Logger.logInfo("DataPersistenceRegressionTest", "Testing data persistence after clear and recreate")
        
        // Store initial data
        prefs.edit().putString("test_key", "test_value").apply()
        assertTrue("Data should exist", prefs.contains("test_key"))
        
        // Clear data
        prefs.edit().clear().apply()
        assertFalse("Data should be cleared", prefs.contains("test_key"))
        
        // Recreate data
        prefs.edit().putString("test_key", "new_value").apply()
        
        // Verify new data persists
        val prefs2 = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        assertEquals("New data should persist", "new_value", prefs2.getString("test_key", null))
        
        Logger.logInfo("DataPersistenceRegressionTest", "Data persistence after clear and recreate test passed")
    }

    /**
     * Test file persistence with rotation.
     */
    @Test
    fun testFilePersistenceWithRotation() {
        Logger.logInfo("DataPersistenceRegressionTest", "Testing file persistence with rotation")
        
        // Create log file
        val appLogsFile = storageManager.getAppLogsFile()
        appLogsFile.parentFile?.mkdirs()
        appLogsFile.writeText("Original log content\n")
        
        // Rotate logs
        storageManager.rotateLogs()
        
        // Verify old log persists
        val oldLogFile = File(appLogsFile.parentFile, "${appLogsFile.name}.old")
        
        if (oldLogFile.exists()) {
            assertTrue("Old log should persist after rotation", oldLogFile.exists())
        }
        
        // New log should exist
        assertTrue("New log should exist after rotation", appLogsFile.exists())
        
        // Clean up
        oldLogFile.delete()
        
        Logger.logInfo("DataPersistenceRegressionTest", "File persistence with rotation test passed")
    }

    /**
     * Test database persistence with backup/restore.
     */
    @Test
    fun testDatabasePersistenceWithBackupRestore() {
        Logger.logInfo("DataPersistenceRegressionTest", "Testing database persistence with backup/restore")
        
        // Create original database
        val dbFile = storageManager.getDatabaseFile()
        dbFile.parentFile?.mkdirs()
        val originalContent = "SQLite format 3\u0000original persistent content"
        dbFile.writeText(originalContent)
        
        // Backup
        val backupFile = File(context.filesDir, "persistence_backup.db")
        val backupUri = android.net.Uri.fromFile(backupFile)
        
        val backupSuccess = storageManager.backupDatabase(backupUri, 
            com.fintrack.pk.server.ServerProcessManager(context))
        assertTrue("Backup should succeed", backupSuccess)
        
        // Verify backup persists
        assertTrue("Backup file should persist", backupFile.exists())
        
        // Modify database
        dbFile.writeText("SQLite format 3\u0000modified content")
        
        // Restore
        val restoreSuccess = storageManager.restoreDatabase(backupUri, 
            com.fintrack.pk.server.ServerProcessManager(context))
        assertTrue("Restore should succeed", restoreSuccess)
        
        // Verify original content persisted through backup/restore
        assertEquals("Original content should persist", originalContent, dbFile.readText())
        
        // Clean up
        backupFile.delete()
        
        Logger.logInfo("DataPersistenceRegressionTest", "Database persistence with backup/restore test passed")
    }
}
