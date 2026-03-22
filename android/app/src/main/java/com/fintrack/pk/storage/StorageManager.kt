package com.fintrack.pk.storage

import android.content.Context
import android.net.Uri
import com.fintrack.pk.server.ServerProcessManager
import com.fintrack.pk.utils.Constants
import com.fintrack.pk.utils.Logger
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Manages database backups, log rotation, and file exports.
 * 
 * This class provides functionality to:
 * - Backup the SQLite database to user-selected location
 * - Restore database from backup file
 * - Export logs for debugging
 * - Rotate logs when they exceed size limits
 * - Get file sizes for diagnostics
 * 
 * Requirements: 7.3, 7.4, 13.5
 */
class StorageManager(private val context: Context) {
    
    companion object {
        private const val COMPONENT = "StorageManager"
        private const val DATABASE_DIR = "databases"
        private const val BUFFER_SIZE = 8192
    }

    /**
     * Backup the SQLite database to a user-selected location.
     * 
     * This method stops the server, copies the fintrack.db file to the destination URI
     * provided by the Storage Access Framework, and then restarts the server.
     * 
     * @param destinationUri The URI where the backup should be saved
     * @param serverProcessManager The server process manager to coordinate server lifecycle
     * @return true if backup was successful, false otherwise
     * 
     * Requirements: 7.3
     */
    fun backupDatabase(destinationUri: Uri, serverProcessManager: ServerProcessManager): Boolean {
        Logger.logInfo(COMPONENT, "Starting database backup to $destinationUri")
        
        var serverWasRunning = false
        
        try {
            val databaseFile = getDatabaseFile()
            
            if (!databaseFile.exists()) {
                Logger.logError(COMPONENT, "Database file does not exist: ${databaseFile.absolutePath}")
                return false
            }
            
            // Check if server is running and stop it to prevent corruption
            serverWasRunning = serverProcessManager.isServerRunning()
            
            if (serverWasRunning) {
                Logger.logInfo(COMPONENT, "Stopping server before backup")
                serverProcessManager.stopServer()
                
                // Wait a moment to ensure server has fully stopped
                Thread.sleep(500)
            }
            
            // Perform the backup
            val success = copyFileToUri(databaseFile, destinationUri)
            
            if (success) {
                Logger.logInfo(COMPONENT, "Database backup completed successfully")
            } else {
                Logger.logError(COMPONENT, "Database backup failed")
            }
            
            return success
            
        } catch (e: Exception) {
            Logger.logError(COMPONENT, "Error during database backup", e)
            return false
        } finally {
            // Always restart the server if it was running before
            if (serverWasRunning) {
                try {
                    Logger.logInfo(COMPONENT, "Restarting server after backup")
                    serverProcessManager.startServer()
                } catch (e: Exception) {
                    Logger.logError(COMPONENT, "Failed to restart server after backup", e)
                }
            }
        }
    }

    /**
     * Restore the SQLite database from a backup file.
     * 
     * This method validates the backup file, stops the server, replaces the current
     * database with the backup file, and restarts the server.
     * 
     * @param sourceUri The URI of the backup file to restore
     * @param serverProcessManager The server process manager to coordinate server lifecycle
     * @return true if restore was successful, false otherwise
     * 
     * Requirements: 7.4
     */
    fun restoreDatabase(sourceUri: Uri, serverProcessManager: ServerProcessManager): Boolean {
        Logger.logInfo(COMPONENT, "Starting database restore from $sourceUri")
        
        var serverWasRunning = false
        var backupFile: File? = null
        
        try {
            val databaseFile = getDatabaseFile()
            
            // Validate the backup file before stopping the server
            Logger.logInfo(COMPONENT, "Validating backup file format")
            if (!validateSQLiteFile(sourceUri)) {
                Logger.logError(COMPONENT, "Backup file is not a valid SQLite database")
                return false
            }
            
            // Create a backup of the current database in case restore fails
            val currentDbBackup = File(databaseFile.parentFile, "${databaseFile.name}.rollback")
            if (databaseFile.exists()) {
                Logger.logInfo(COMPONENT, "Creating rollback backup of current database")
                databaseFile.copyTo(currentDbBackup, overwrite = true)
            }
            
            // Check if server is running and stop it to prevent corruption
            serverWasRunning = serverProcessManager.isServerRunning()
            
            if (serverWasRunning) {
                Logger.logInfo(COMPONENT, "Stopping server before restore")
                serverProcessManager.stopServer()
                
                // Wait a moment to ensure server has fully stopped
                Thread.sleep(500)
            }
            
            // Perform the restore
            val success = copyFileFromUri(sourceUri, databaseFile)
            
            if (!success) {
                Logger.logError(COMPONENT, "Failed to copy backup file to database location")
                
                // Rollback to the original database
                if (currentDbBackup.exists()) {
                    Logger.logInfo(COMPONENT, "Rolling back to original database")
                    currentDbBackup.copyTo(databaseFile, overwrite = true)
                }
                
                return false
            }
            
            // Clean up rollback backup on success
            if (currentDbBackup.exists()) {
                currentDbBackup.delete()
            }
            
            Logger.logInfo(COMPONENT, "Database restore completed successfully")
            return true
            
        } catch (e: Exception) {
            Logger.logError(COMPONENT, "Error during database restore", e)
            return false
        } finally {
            // Always restart the server if it was running before
            if (serverWasRunning) {
                try {
                    Logger.logInfo(COMPONENT, "Restarting server after restore")
                    serverProcessManager.startServer()
                } catch (e: Exception) {
                    Logger.logError(COMPONENT, "Failed to restart server after restore", e)
                }
            }
        }
    }
    
    /**
     * Validate that a file is a valid SQLite database by checking its magic bytes.
     * 
     * SQLite database files start with the magic string "SQLite format 3\0" (16 bytes).
     * 
     * @param sourceUri The URI of the file to validate
     * @return true if the file is a valid SQLite database, false otherwise
     */
    private fun validateSQLiteFile(sourceUri: Uri): Boolean {
        return try {
            context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                val magicBytes = ByteArray(16)
                val bytesRead = inputStream.read(magicBytes)
                
                if (bytesRead < 16) {
                    Logger.logError(COMPONENT, "File too small to be a valid SQLite database")
                    return false
                }
                
                // SQLite magic string: "SQLite format 3\0"
                val expectedMagic = "SQLite format 3\u0000".toByteArray(Charsets.UTF_8)
                
                val isValid = magicBytes.contentEquals(expectedMagic)
                
                if (!isValid) {
                    Logger.logError(COMPONENT, "File does not have SQLite magic bytes")
                }
                
                isValid
            } ?: false
            
        } catch (e: Exception) {
            Logger.logError(COMPONENT, "Error validating SQLite file", e)
            false
        }
    }

    /**
     * Export log files to a user-selected location.
     * 
     * This method exports both app_logs.txt and server_logs.txt to the
     * destination URI. The logs are combined into a single file with headers.
     * 
     * @param destinationUri The URI where the logs should be exported
     * @return true if export was successful, false otherwise
     * 
     * Requirements: 13.5
     */
    fun exportLogs(destinationUri: Uri): Boolean {
        Logger.logInfo(COMPONENT, "Starting log export to $destinationUri")
        
        try {
            val appLogsFile = getAppLogsFile()
            val serverLogsFile = getServerLogsFile()
            
            // Create combined log content with headers
            val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date())
            
            val combinedContent = buildString {
                appendLine("===== FinTrack Android App Logs =====")
                appendLine("Exported: $timestamp")
                appendLine()
                
                // Add app logs
                appendLine("----- App Logs (app_logs.txt) -----")
                if (appLogsFile.exists()) {
                    appendLine(appLogsFile.readText())
                } else {
                    appendLine("(No app logs available)")
                }
                appendLine()
                
                // Add server logs
                appendLine("----- Server Logs (server_logs.txt) -----")
                if (serverLogsFile.exists()) {
                    appendLine(serverLogsFile.readText())
                } else {
                    appendLine("(No server logs available)")
                }
            }
            
            // Write combined content to destination
            val success = context.contentResolver.openOutputStream(destinationUri)?.use { outputStream ->
                outputStream.write(combinedContent.toByteArray(Charsets.UTF_8))
                true
            } ?: false
            
            if (success) {
                Logger.logInfo(COMPONENT, "Log export completed successfully")
            } else {
                Logger.logError(COMPONENT, "Log export failed")
            }
            
            return success
            
        } catch (e: Exception) {
            Logger.logError(COMPONENT, "Error during log export", e)
            return false
        }
    }

    /**
     * Rotate log files when they exceed the size limit.
     * 
     * This method checks both app and server log files and rotates them
     * if they exceed the maximum size (10MB). Rotation involves renaming
     * the current log file to .old and creating a new empty log file.
     * 
     * @return true if rotation was successful or not needed, false on error
     * 
     * Requirements: 13.3
     */
    fun rotateLogs(): Boolean {
        Logger.logInfo(COMPONENT, "Checking if log rotation is needed")
        
        try {
            val appLogsFile = getAppLogsFile()
            val serverLogsFile = getServerLogsFile()
            val maxSize = Constants.MAX_LOG_SIZE_MB * 1024 * 1024
            
            var rotated = false
            
            if (appLogsFile.exists() && appLogsFile.length() > maxSize) {
                rotateLogFile(appLogsFile)
                rotated = true
            }
            
            if (serverLogsFile.exists() && serverLogsFile.length() > maxSize) {
                rotateLogFile(serverLogsFile)
                rotated = true
            }
            
            if (rotated) {
                Logger.logInfo(COMPONENT, "Log rotation completed")
            } else {
                Logger.logDebug(COMPONENT, "No log rotation needed")
            }
            
            return true
            
        } catch (e: Exception) {
            Logger.logError(COMPONENT, "Error during log rotation", e)
            return false
        }
    }

    /**
     * Get the size of the SQLite database file in bytes.
     * 
     * @return Database file size in bytes, or 0 if file doesn't exist
     */
    fun getDatabaseSize(): Long {
        return try {
            val databaseFile = getDatabaseFile()
            if (databaseFile.exists()) {
                databaseFile.length()
            } else {
                0L
            }
        } catch (e: Exception) {
            Logger.logError(COMPONENT, "Error getting database size", e)
            0L
        }
    }

    /**
     * Get the total size of all log files in bytes.
     * 
     * @return Total log file size in bytes
     */
    fun getLogSize(): Long {
        return try {
            val appLogsFile = getAppLogsFile()
            val serverLogsFile = getServerLogsFile()
            
            var totalSize = 0L
            
            if (appLogsFile.exists()) {
                totalSize += appLogsFile.length()
            }
            
            if (serverLogsFile.exists()) {
                totalSize += serverLogsFile.length()
            }
            
            totalSize
            
        } catch (e: Exception) {
            Logger.logError(COMPONENT, "Error getting log size", e)
            0L
        }
    }

    /**
     * Get the app logs file.
     * 
     * @return File object for app_logs.txt
     */
    fun getAppLogsFile(): File {
        val logsDir = context.filesDir.resolve(Constants.LOGS_DIR)
        logsDir.mkdirs()
        return logsDir.resolve(Constants.APP_LOG_FILE)
    }

    /**
     * Get the server logs file.
     * 
     * @return File object for server_logs.txt
     */
    fun getServerLogsFile(): File {
        val logsDir = context.filesDir.resolve(Constants.LOGS_DIR)
        logsDir.mkdirs()
        return logsDir.resolve(Constants.SERVER_LOG_FILE)
    }

    /**
     * Get the database file.
     * 
     * @return File object for fintrack.db
     */
    fun getDatabaseFile(): File {
        val databaseDir = context.filesDir.resolve(DATABASE_DIR)
        databaseDir.mkdirs()
        return databaseDir.resolve(Constants.DATABASE_NAME)
    }

    /**
     * Copy a file to a URI using the Storage Access Framework.
     * 
     * @param sourceFile The source file to copy
     * @param destinationUri The destination URI
     * @return true if copy was successful, false otherwise
     */
    private fun copyFileToUri(sourceFile: File, destinationUri: Uri): Boolean {
        return try {
            context.contentResolver.openOutputStream(destinationUri)?.use { outputStream ->
                FileInputStream(sourceFile).use { inputStream ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                    }
                }
            }
            true
        } catch (e: Exception) {
            Logger.logError(COMPONENT, "Error copying file to URI", e)
            false
        }
    }

    /**
     * Copy a file from a URI using the Storage Access Framework.
     * 
     * @param sourceUri The source URI
     * @param destinationFile The destination file
     * @return true if copy was successful, false otherwise
     */
    private fun copyFileFromUri(sourceUri: Uri, destinationFile: File): Boolean {
        return try {
            context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                FileOutputStream(destinationFile).use { outputStream ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                    }
                }
            }
            true
        } catch (e: Exception) {
            Logger.logError(COMPONENT, "Error copying file from URI", e)
            false
        }
    }

    /**
     * Rotate a single log file by renaming it to .old and creating a new file.
     * 
     * @param logFile The log file to rotate
     */
    private fun rotateLogFile(logFile: File) {
        try {
            val oldLogFile = File(logFile.parentFile, "${logFile.name}.old")
            
            // Delete old backup if it exists
            if (oldLogFile.exists()) {
                oldLogFile.delete()
            }
            
            // Rename current log to .old
            logFile.renameTo(oldLogFile)
            
            // Create new empty log file
            logFile.createNewFile()
            
            Logger.logInfo(COMPONENT, "Rotated log file: ${logFile.name}")
            
        } catch (e: Exception) {
            Logger.logError(COMPONENT, "Error rotating log file: ${logFile.name}", e)
        }
    }
}
