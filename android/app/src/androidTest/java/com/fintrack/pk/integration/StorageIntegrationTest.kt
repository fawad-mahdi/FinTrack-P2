package com.fintrack.pk.integration

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fintrack.pk.server.ServerProcessManager
import com.fintrack.pk.storage.StorageManager
import com.fintrack.pk.utils.Logger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Integration tests for database backup/restore with server coordination.
 * 
 * Tests verify:
 * - Database backup with server stop/start coordination
 * - Database restore with server stop/start coordination
 * - Log rotation and export functionality
 * - File size reporting
 * - Storage operations with concurrent server access
 */
@RunWith(AndroidJUnit4::class)
class StorageIntegrationTest {

    private lateinit var context: Context
    private lateinit var storageManager: StorageManager
    private lateinit var serverProcessManager: ServerProcessManager
    private lateinit var testBackupFile: File
    private lateinit var testRestoreFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        storageManager = StorageManager(context)
        serverProcessManager = ServerProcessManager(context)
        
        // Create test backup directory
        val testDir = File(context.filesDir, "test_backups")
        testDir.mkdirs()
        
        testBackupFile = File(testDir, "test_backup.db")
        testRestoreFile = File(testDir, "test_restore.db")
        
        // Clean up test files
        testBackupFile.delete()
        testRestoreFile.delete()
        
        Logger.logInfo("StorageIntegrationTest", "Test setup complete")
    }

    @After
    fun tearDown() {
        // Stop server if running
        if (serverProcessManager.isServerRunning()) {
            serverProcessManager.stopServer()
        }
        serverProcessManager.cleanup()
        
        // Clean up test files
        testBackupFile.delete()
        testRestoreFile.delete()
        
        Logger.logInfo("StorageIntegrationTest", "Test teardown complete")
    }

    /**
     * Test database backup with server coordination.
     */
    @Test
    fun testDatabaseBackupWithServerCoordination() = runBlocking {
        Logger.logInfo("StorageIntegrationTest", "Testing database backup with server coordination")
        
        // Create a test database file
        val dbFile = storageManager.getDatabaseFile()
        dbFile.parentFile?.mkdirs()
        dbFile.writeText("SQLite format 3\u0000test database content")
        
        // Start server
        serverProcessManager.startServer()
        delay(2000)
        assertTrue("Server should be running", serverProcessManager.isServerRunning())
        
        // Perform backup (should stop server, backup, restart server)
        val backupUri = Uri.fromFile(testBackupFile)
        val success = storageManager.backupDatabase(backupUri, serverProcessManager)
        
        assertTrue("Backup should succeed", success)
        assertTrue("Backup file should exist", testBackupFile.exists())
        assertTrue("Backup file should have content", testBackupFile.length() > 0)
        
        // Server should be running again after backup
        delay(2000)
        assertTrue("Server should be running after backup", serverProcessManager.isServerRunning())
        
        Logger.logInfo("StorageIntegrationTest", "Database backup with server coordination test passed")
    }

    /**
     * Test database backup when server is not running.
     */
    @Test
    fun testDatabaseBackupWithoutServer() {
        Logger.logInfo("StorageIntegrationTest", "Testing database backup without server")
        
        // Create a test database file
        val dbFile = storageManager.getDatabaseFile()
        dbFile.parentFile?.mkdirs()
        dbFile.writeText("SQLite format 3\u0000test database content")
        
        // Server is not running
        assertFalse("Server should not be running", serverProcessManager.isServerRunning())
        
        // Perform backup
        val backupUri = Uri.fromFile(testBackupFile)
        val success = storageManager.backupDatabase(backupUri, serverProcessManager)
        
        assertTrue("Backup should succeed", success)
        assertTrue("Backup file should exist", testBackupFile.exists())
        
        Logger.logInfo("StorageIntegrationTest", "Database backup without server test passed")
    }

    /**
     * Test database restore with server coordination.
     */
    @Test
    fun testDatabaseRestoreWithServerCoordination() = runBlocking {
        Logger.logInfo("StorageIntegrationTest", "Testing database restore with server coordination")
        
        // Create a valid SQLite backup file
        testRestoreFile.writeText("SQLite format 3\u0000restored database content")
        
        // Create original database
        val dbFile = storageManager.getDatabaseFile()
        dbFile.parentFile?.mkdirs()
        dbFile.writeText("SQLite format 3\u0000original database content")
        
        // Start server
        serverProcessManager.startServer()
        delay(2000)
        assertTrue("Server should be running", serverProcessManager.isServerRunning())
        
        // Perform restore (should stop server, restore, restart server)
        val restoreUri = Uri.fromFile(testRestoreFile)
        val success = storageManager.restoreDatabase(restoreUri, serverProcessManager)
        
        assertTrue("Restore should succeed", success)
        
        // Verify database was restored
        val restoredContent = dbFile.readText()
        assertTrue("Database should contain restored content", 
            restoredContent.contains("restored database content"))
        
        // Server should be running again after restore
        delay(2000)
        assertTrue("Server should be running after restore", serverProcessManager.isServerRunning())
        
        Logger.logInfo("StorageIntegrationTest", "Database restore with server coordination test passed")
    }

    /**
     * Test database restore with invalid file.
     */
    @Test
    fun testDatabaseRestoreWithInvalidFile() {
        Logger.logInfo("StorageIntegrationTest", "Testing database restore with invalid file")
        
        // Create an invalid backup file (not SQLite format)
        testRestoreFile.writeText("This is not a valid SQLite database")
        
        // Create original database
        val dbFile = storageManager.getDatabaseFile()
        dbFile.parentFile?.mkdirs()
        val originalContent = "SQLite format 3\u0000original database content"
        dbFile.writeText(originalContent)
        
        // Attempt restore
        val restoreUri = Uri.fromFile(testRestoreFile)
        val success = storageManager.restoreDatabase(restoreUri, serverProcessManager)
        
        assertFalse("Restore should fail with invalid file", success)
        
        // Original database should be preserved
        val currentContent = dbFile.readText()
        assertEquals("Original database should be preserved", originalContent, currentContent)
        
        Logger.logInfo("StorageIntegrationTest", "Database restore with invalid file test passed")
    }

    /**
     * Test log rotation functionality.
     */
    @Test
    fun testLogRotation() {
        Logger.logInfo("StorageIntegrationTest", "Testing log rotation")
        
        // Create large log files that exceed the limit
        val appLogsFile = storageManager.getAppLogsFile()
        val serverLogsFile = storageManager.getServerLogsFile()
        
        appLogsFile.parentFile?.mkdirs()
        serverLogsFile.parentFile?.mkdirs()
        
        // Create 11MB log files (exceeds 10MB limit)
        val largeContent = "x".repeat(11 * 1024 * 1024)
        appLogsFile.writeText(largeContent)
        serverLogsFile.writeText(largeContent)
        
        // Perform log rotation
        val success = storageManager.rotateLogs()
        assertTrue("Log rotation should succeed", success)
        
        // Verify old log files were created
        val appLogsOld = File(appLogsFile.parentFile, "${appLogsFile.name}.old")
        val serverLogsOld = File(serverLogsFile.parentFile, "${serverLogsFile.name}.old")
        
        assertTrue("Old app logs should exist", appLogsOld.exists())
        assertTrue("Old server logs should exist", serverLogsOld.exists())
        
        // Verify new log files are empty or small
        assertTrue("New app logs should be smaller", appLogsFile.length() < 1024)
        assertTrue("New server logs should be smaller", serverLogsFile.length() < 1024)
        
        // Clean up
        appLogsOld.delete()
        serverLogsOld.delete()
        
        Logger.logInfo("StorageIntegrationTest", "Log rotation test passed")
    }

    /**
     * Test log export functionality.
     */
    @Test
    fun testLogExport() {
        Logger.logInfo("StorageIntegrationTest", "Testing log export")
        
        // Create test log files
        val appLogsFile = storageManager.getAppLogsFile()
        val serverLogsFile = storageManager.getServerLogsFile()
        
        appLogsFile.parentFile?.mkdirs()
        serverLogsFile.parentFile?.mkdirs()
        
        appLogsFile.writeText("App log line 1\nApp log line 2\n")
        serverLogsFile.writeText("Server log line 1\nServer log line 2\n")
        
        // Export logs
        val exportFile = File(context.filesDir, "test_export.txt")
        val exportUri = Uri.fromFile(exportFile)
        
        val success = storageManager.exportLogs(exportUri)
        assertTrue("Log export should succeed", success)
        assertTrue("Export file should exist", exportFile.exists())
        
        // Verify export content
        val exportContent = exportFile.readText()
        assertTrue("Export should contain app logs", exportContent.contains("App log line 1"))
        assertTrue("Export should contain server logs", exportContent.contains("Server log line 1"))
        assertTrue("Export should have header", exportContent.contains("FinTrack Android App Logs"))
        
        // Clean up
        exportFile.delete()
        
        Logger.logInfo("StorageIntegrationTest", "Log export test passed")
    }

    /**
     * Test database size reporting.
     */
    @Test
    fun testDatabaseSizeReporting() {
        Logger.logInfo("StorageIntegrationTest", "Testing database size reporting")
        
        // Create a test database file
        val dbFile = storageManager.getDatabaseFile()
        dbFile.parentFile?.mkdirs()
        val testContent = "SQLite format 3\u0000" + "x".repeat(1000)
        dbFile.writeText(testContent)
        
        // Get database size
        val size = storageManager.getDatabaseSize()
        
        assertTrue("Database size should be positive", size > 0)
        assertEquals("Database size should match file size", dbFile.length(), size)
        
        Logger.logInfo("StorageIntegrationTest", "Database size reporting test passed")
    }

    /**
     * Test log size reporting.
     */
    @Test
    fun testLogSizeReporting() {
        Logger.logInfo("StorageIntegrationTest", "Testing log size reporting")
        
        // Create test log files
        val appLogsFile = storageManager.getAppLogsFile()
        val serverLogsFile = storageManager.getServerLogsFile()
        
        appLogsFile.parentFile?.mkdirs()
        serverLogsFile.parentFile?.mkdirs()
        
        appLogsFile.writeText("x".repeat(500))
        serverLogsFile.writeText("x".repeat(300))
        
        // Get log size
        val size = storageManager.getLogSize()
        
        assertTrue("Log size should be positive", size > 0)
        assertEquals("Log size should be sum of both files", 800L, size)
        
        Logger.logInfo("StorageIntegrationTest", "Log size reporting test passed")
    }

    /**
     * Test backup and restore round-trip.
     */
    @Test
    fun testBackupRestoreRoundTrip() = runBlocking {
        Logger.logInfo("StorageIntegrationTest", "Testing backup and restore round-trip")
        
        // Create original database with specific content
        val dbFile = storageManager.getDatabaseFile()
        dbFile.parentFile?.mkdirs()
        val originalContent = "SQLite format 3\u0000original unique content ${System.currentTimeMillis()}"
        dbFile.writeText(originalContent)
        
        // Backup database
        val backupUri = Uri.fromFile(testBackupFile)
        val backupSuccess = storageManager.backupDatabase(backupUri, serverProcessManager)
        assertTrue("Backup should succeed", backupSuccess)
        
        // Modify original database
        dbFile.writeText("SQLite format 3\u0000modified content")
        
        // Restore from backup
        val restoreSuccess = storageManager.restoreDatabase(backupUri, serverProcessManager)
        assertTrue("Restore should succeed", restoreSuccess)
        
        // Verify original content was restored
        val restoredContent = dbFile.readText()
        assertEquals("Restored content should match original", originalContent, restoredContent)
        
        Logger.logInfo("StorageIntegrationTest", "Backup and restore round-trip test passed")
    }

    /**
     * Test concurrent storage operations.
     */
    @Test
    fun testConcurrentStorageOperations() = runBlocking {
        Logger.logInfo("StorageIntegrationTest", "Testing concurrent storage operations")
        
        // Create test files
        val dbFile = storageManager.getDatabaseFile()
        dbFile.parentFile?.mkdirs()
        dbFile.writeText("SQLite format 3\u0000test content")
        
        val appLogsFile = storageManager.getAppLogsFile()
        appLogsFile.parentFile?.mkdirs()
        appLogsFile.writeText("test logs")
        
        // Perform multiple operations
        val dbSize = storageManager.getDatabaseSize()
        val logSize = storageManager.getLogSize()
        val rotateSuccess = storageManager.rotateLogs()
        
        assertTrue("Database size should be positive", dbSize > 0)
        assertTrue("Log size should be positive", logSize > 0)
        assertTrue("Log rotation should succeed", rotateSuccess)
        
        Logger.logInfo("StorageIntegrationTest", "Concurrent storage operations test passed")
    }
}
