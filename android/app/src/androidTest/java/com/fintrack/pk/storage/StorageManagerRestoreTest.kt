package com.fintrack.pk.storage

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fintrack.pk.server.ServerProcessManager
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Integration tests for StorageManager database restore functionality.
 * 
 * Tests the database restore feature including:
 * - SQLite file validation
 * - Server coordination during restore
 * - Rollback on failure
 * 
 * Requirements: 7.4
 */
@RunWith(AndroidJUnit4::class)
class StorageManagerRestoreTest {
    
    private lateinit var context: Context
    private lateinit var storageManager: StorageManager
    private lateinit var serverProcessManager: ServerProcessManager
    private lateinit var testDatabaseFile: File
    
    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        storageManager = StorageManager(context)
        serverProcessManager = ServerProcessManager(context)
        
        // Create a test database file
        testDatabaseFile = File(context.cacheDir, "test_backup.db")
    }
    
    @After
    fun cleanup() {
        // Clean up test files
        if (testDatabaseFile.exists()) {
            testDatabaseFile.delete()
        }
        
        // Stop server if running
        if (serverProcessManager.isServerRunning()) {
            serverProcessManager.stopServer()
        }
    }
    
    @Test
    fun testValidateSQLiteFile_withValidDatabase() {
        // Create a valid SQLite database file
        createValidSQLiteFile(testDatabaseFile)
        
        val uri = Uri.fromFile(testDatabaseFile)
        
        // Use reflection to access the private validateSQLiteFile method
        val method = StorageManager::class.java.getDeclaredMethod(
            "validateSQLiteFile",
            Uri::class.java
        )
        method.isAccessible = true
        
        val result = method.invoke(storageManager, uri) as Boolean
        
        assertTrue("Valid SQLite file should pass validation", result)
    }
    
    @Test
    fun testValidateSQLiteFile_withInvalidFile() {
        // Create an invalid file (not a SQLite database)
        FileOutputStream(testDatabaseFile).use { output ->
            output.write("This is not a SQLite database".toByteArray())
        }
        
        val uri = Uri.fromFile(testDatabaseFile)
        
        // Use reflection to access the private validateSQLiteFile method
        val method = StorageManager::class.java.getDeclaredMethod(
            "validateSQLiteFile",
            Uri::class.java
        )
        method.isAccessible = true
        
        val result = method.invoke(storageManager, uri) as Boolean
        
        assertFalse("Invalid file should fail validation", result)
    }
    
    @Test
    fun testRestoreDatabase_withInvalidFile_shouldReturnFalse() {
        // Create an invalid file
        FileOutputStream(testDatabaseFile).use { output ->
            output.write("Not a database".toByteArray())
        }
        
        val uri = Uri.fromFile(testDatabaseFile)
        
        // Attempt restore with invalid file
        val result = storageManager.restoreDatabase(uri, serverProcessManager)
        
        assertFalse("Restore should fail with invalid file", result)
    }
    
    @Test
    fun testRestoreDatabase_withValidFile_shouldSucceed() {
        // Create a valid SQLite database file
        createValidSQLiteFile(testDatabaseFile)
        
        val uri = Uri.fromFile(testDatabaseFile)
        
        // Attempt restore with valid file
        val result = storageManager.restoreDatabase(uri, serverProcessManager)
        
        assertTrue("Restore should succeed with valid file", result)
        
        // Verify the database file was created
        val databaseFile = storageManager.getDatabaseFile()
        assertTrue("Database file should exist after restore", databaseFile.exists())
    }
    
    /**
     * Helper method to create a valid SQLite database file.
     * 
     * Creates a minimal SQLite database with the correct magic bytes.
     */
    private fun createValidSQLiteFile(file: File) {
        FileOutputStream(file).use { output ->
            // SQLite magic bytes: "SQLite format 3\0"
            val magicBytes = "SQLite format 3\u0000".toByteArray(Charsets.UTF_8)
            output.write(magicBytes)
            
            // Add minimal SQLite header (100 bytes total)
            // Page size (2 bytes at offset 16): 4096 (0x1000)
            val header = ByteArray(100 - magicBytes.size)
            header[0] = 0x10  // Page size high byte
            header[1] = 0x00  // Page size low byte
            
            output.write(header)
        }
    }
}
