package com.fintrack.pk.storage

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Integration tests for StorageManager log rotation and export functionality.
 * 
 * Tests the log management features including:
 * - Log rotation when exceeding size limits
 * - Log export with combined app and server logs
 * 
 * Requirements: 13.3, 13.5
 */
@RunWith(AndroidJUnit4::class)
class StorageManagerLogTest {
    
    private lateinit var context: Context
    private lateinit var storageManager: StorageManager
    private lateinit var appLogsFile: File
    private lateinit var serverLogsFile: File
    
    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        storageManager = StorageManager(context)
        
        appLogsFile = storageManager.getAppLogsFile()
        serverLogsFile = storageManager.getServerLogsFile()
        
        // Clean up any existing log files
        cleanupLogFiles()
    }
    
    @After
    fun cleanup() {
        cleanupLogFiles()
    }
    
    private fun cleanupLogFiles() {
        appLogsFile.delete()
        serverLogsFile.delete()
        File(appLogsFile.parentFile, "${appLogsFile.name}.old").delete()
        File(serverLogsFile.parentFile, "${serverLogsFile.name}.old").delete()
    }
    
    @Test
    fun testRotateLogs_withSmallFiles_shouldNotRotate() {
        // Create small log files (less than 10MB)
        appLogsFile.writeText("Small app log content")
        serverLogsFile.writeText("Small server log content")
        
        val result = storageManager.rotateLogs()
        
        assertTrue("Rotation should succeed", result)
        assertTrue("App log file should still exist", appLogsFile.exists())
        assertTrue("Server log file should still exist", serverLogsFile.exists())
        assertFalse("Old app log should not exist", 
            File(appLogsFile.parentFile, "${appLogsFile.name}.old").exists())
        assertFalse("Old server log should not exist", 
            File(serverLogsFile.parentFile, "${serverLogsFile.name}.old").exists())
    }
    
    @Test
    fun testRotateLogs_withLargeFile_shouldRotate() {
        // Create a large log file (over 10MB)
        val largeContent = "X".repeat(11 * 1024 * 1024) // 11MB
        appLogsFile.writeText(largeContent)
        
        val result = storageManager.rotateLogs()
        
        assertTrue("Rotation should succeed", result)
        assertTrue("New app log file should exist", appLogsFile.exists())
        assertTrue("Old app log file should exist", 
            File(appLogsFile.parentFile, "${appLogsFile.name}.old").exists())
        
        // New file should be empty or very small
        assertTrue("New log file should be small", appLogsFile.length() < 1024)
    }
    
    @Test
    fun testExportLogs_withBothLogs_shouldCombine() {
        // Create test log files
        appLogsFile.writeText("App log line 1\nApp log line 2\n")
        serverLogsFile.writeText("Server log line 1\nServer log line 2\n")
        
        // Create a temporary file for export
        val exportFile = File(context.cacheDir, "exported_logs.txt")
        val exportUri = Uri.fromFile(exportFile)
        
        try {
            val result = storageManager.exportLogs(exportUri)
            
            assertTrue("Export should succeed", result)
            assertTrue("Export file should exist", exportFile.exists())
            
            val exportedContent = exportFile.readText()
            
            // Verify the export contains headers
            assertTrue("Export should contain header", 
                exportedContent.contains("===== FinTrack Android App Logs ====="))
            assertTrue("Export should contain timestamp", 
                exportedContent.contains("Exported:"))
            
            // Verify both log sections are present
            assertTrue("Export should contain app logs section", 
                exportedContent.contains("----- App Logs (app_logs.txt) -----"))
            assertTrue("Export should contain server logs section", 
                exportedContent.contains("----- Server Logs (server_logs.txt) -----"))
            
            // Verify actual log content is included
            assertTrue("Export should contain app log content", 
                exportedContent.contains("App log line 1"))
            assertTrue("Export should contain server log content", 
                exportedContent.contains("Server log line 1"))
            
        } finally {
            exportFile.delete()
        }
    }
    
    @Test
    fun testExportLogs_withMissingLogs_shouldHandleGracefully() {
        // Don't create any log files
        
        // Create a temporary file for export
        val exportFile = File(context.cacheDir, "exported_logs.txt")
        val exportUri = Uri.fromFile(exportFile)
        
        try {
            val result = storageManager.exportLogs(exportUri)
            
            assertTrue("Export should succeed even with missing logs", result)
            assertTrue("Export file should exist", exportFile.exists())
            
            val exportedContent = exportFile.readText()
            
            // Verify the export contains placeholders for missing logs
            assertTrue("Export should indicate no app logs", 
                exportedContent.contains("(No app logs available)"))
            assertTrue("Export should indicate no server logs", 
                exportedContent.contains("(No server logs available)"))
            
        } finally {
            exportFile.delete()
        }
    }
    
    @Test
    fun testGetLogSize_withBothLogs_shouldReturnTotal() {
        // Create test log files with known sizes
        val appLogContent = "A".repeat(1000)
        val serverLogContent = "B".repeat(2000)
        
        appLogsFile.writeText(appLogContent)
        serverLogsFile.writeText(serverLogContent)
        
        val totalSize = storageManager.getLogSize()
        
        assertEquals("Total log size should be sum of both files", 
            3000L, totalSize)
    }
    
    @Test
    fun testGetLogSize_withNoLogs_shouldReturnZero() {
        // Don't create any log files
        
        val totalSize = storageManager.getLogSize()
        
        assertEquals("Total log size should be zero when no logs exist", 
            0L, totalSize)
    }
}
