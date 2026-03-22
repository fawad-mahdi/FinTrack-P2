package com.fintrack.pk.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * Logging utility that writes to both Logcat and file
 */
object Logger {
    private const val TAG = "FinTrack"
    private const val MAX_LOG_SIZE_BYTES = 10 * 1024 * 1024L // 10MB
    private const val LOG_FILE_NAME = "app_logs.txt"
    private const val LOG_FILE_OLD_NAME = "app_logs.old.txt"

    private lateinit var logFile: File
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /**
     * Initialize the logger with application context
     */
    fun initialize(context: Context) {
        val logsDir = context.filesDir.resolve("logs")
        logsDir.mkdirs()
        logFile = logsDir.resolve(LOG_FILE_NAME)

        // Rotate logs if needed
        rotateLogs()
    }

    /**
     * Log an info message
     */
    fun logInfo(component: String, message: String) {
        val logMessage = formatLogMessage("INFO", component, message)
        Log.i(TAG, logMessage)
        writeToFile(logMessage)
    }

    /**
     * Log an error message
     */
    fun logError(component: String, message: String, throwable: Throwable? = null) {
        val logMessage = formatLogMessage("ERROR", component, message)
        Log.e(TAG, logMessage, throwable)
        writeToFile(logMessage)
        throwable?.let {
            writeToFile("Stack trace: ${it.stackTraceToString()}")
        }
    }

    /**
     * Log a debug message
     */
    fun logDebug(component: String, message: String) {
        val logMessage = formatLogMessage("DEBUG", component, message)
        Log.d(TAG, logMessage)
        writeToFile(logMessage)
    }

    /**
     * Log a warning message
     */
    fun logWarning(component: String, message: String) {
        val logMessage = formatLogMessage("WARN", component, message)
        Log.w(TAG, logMessage)
        writeToFile(logMessage)
    }

    /**
     * Format a log message with timestamp, level, and component
     */
    private fun formatLogMessage(level: String, component: String, message: String): String {
        val timestamp = dateFormat.format(Date())
        val threadName = Thread.currentThread().name
        return "[$timestamp] [$level] [$component] [$threadName] $message"
    }

    /**
     * Write log message to file
     */
    private fun writeToFile(message: String) {
        try {
            if (!::logFile.isInitialized) return

            // Check if rotation is needed
            if (logFile.exists() && logFile.length() > MAX_LOG_SIZE_BYTES) {
                rotateLogs()
            }

            FileWriter(logFile, true).use { writer ->
                writer.appendLine(message)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write to log file", e)
        }
    }

    /**
     * Rotate log files when size limit is reached
     */
    private fun rotateLogs() {
        try {
            if (!::logFile.isInitialized) return

            if (logFile.exists() && logFile.length() > MAX_LOG_SIZE_BYTES) {
                val oldLogFile = logFile.parentFile?.resolve(LOG_FILE_OLD_NAME)
                oldLogFile?.delete()
                logFile.renameTo(oldLogFile ?: return)
                logFile.createNewFile()
                Log.i(TAG, "Log files rotated")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rotate logs", e)
        }
    }

    /**
     * Get the log file for reading
     */
    fun getLogFile(): File? = if (::logFile.isInitialized) logFile else null

    /**
     * Get the old log file for reading
     */
    fun getOldLogFile(): File? {
        return if (::logFile.isInitialized) {
            logFile.parentFile?.resolve(LOG_FILE_OLD_NAME)
        } else null
    }

    /**
     * Clear all logs
     */
    fun clearLogs() {
        try {
            logFile.delete()
            getOldLogFile()?.delete()
            logFile.createNewFile()
            Log.i(TAG, "Logs cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear logs", e)
        }
    }

    /**
     * Get log file size in bytes
     */
    fun getLogSize(): Long {
        return if (::logFile.isInitialized && logFile.exists()) {
            logFile.length()
        } else 0L
    }
}
