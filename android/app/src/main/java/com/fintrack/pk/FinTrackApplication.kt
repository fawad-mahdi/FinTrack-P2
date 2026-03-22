package com.fintrack.pk

import android.app.Application
import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.fintrack.pk.utils.Logger
import com.fintrack.pk.utils.PythonRuntimeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Application class for FinTrack PK
 * Initializes app-wide components and provides global context
 */
class FinTrackApplication : Application() {

    companion object {
        private lateinit var instance: FinTrackApplication

        /**
         * Get the application instance
         */
        fun getInstance(): FinTrackApplication = instance

        /**
         * Get the application context
         */
        fun getAppContext(): Context = instance.applicationContext
    }

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var pythonRuntimeManager: PythonRuntimeManager
    private lateinit var serverProcessManager: com.fintrack.pk.server.ServerProcessManager

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize logger
        Logger.initialize(this)
        Logger.logInfo("FinTrackApplication", "Application started")
        
        // Initialize Python platform for Android
        initializePythonPlatform()

        // Task 14.1: Set up global uncaught exception handler
        setupGlobalExceptionHandler()

        // Initialize Python runtime manager
        pythonRuntimeManager = PythonRuntimeManager(this)
        
        // Initialize server process manager
        serverProcessManager = com.fintrack.pk.server.ServerProcessManager(this)

        // Create necessary directories
        createAppDirectories()

        // Extract static web assets (index.html) to filesDir so server.py can serve them
        extractStaticAssets()

        // Check if first launch and extract Python runtime if needed
        checkAndExtractRuntime()
        
        // Task 14.1: Check for crash log from previous session
        checkForCrashLog()
    }
    
    /**
     * Initialize Python platform for Android
     * This must be called before any Python code is executed
     */
    private fun initializePythonPlatform() {
        try {
            if (!Python.isStarted()) {
                Logger.logInfo("FinTrackApplication", "Initializing Python platform for Android")
                Python.start(AndroidPlatform(this))
                Logger.logInfo("FinTrackApplication", "Python platform initialized successfully")
            } else {
                Logger.logInfo("FinTrackApplication", "Python platform already initialized")
            }
        } catch (e: Exception) {
            Logger.logError("FinTrackApplication", "Failed to initialize Python platform", e)
        }
    }

    /**
     * Check if this is first launch and extract Python runtime if needed
     * This runs asynchronously to avoid blocking app startup
     */
    private fun checkAndExtractRuntime() {
        applicationScope.launch(Dispatchers.IO) {
            try {
                if (pythonRuntimeManager.isFirstLaunch()) {
                    Logger.logInfo("FinTrackApplication", "First launch detected, extracting Python runtime")
                    
                    val success = pythonRuntimeManager.extractRuntime()
                    if (success) {
                        // Verify extraction
                        val verified = pythonRuntimeManager.verifyExtraction()
                        if (verified) {
                            Logger.logInfo("FinTrackApplication", "Python runtime extraction and verification successful")
                        } else {
                            Logger.logError("FinTrackApplication", "Python runtime verification failed")
                        }
                    } else {
                        Logger.logError("FinTrackApplication", "Python runtime extraction failed")
                    }
                } else {
                    Logger.logInfo("FinTrackApplication", "Python runtime already extracted")
                }
            } catch (e: Exception) {
                Logger.logError("FinTrackApplication", "Error during runtime extraction", e)
            }
        }
    }

    /**
     * Create app-private directories for Python runtime, config, and logs
     */
    private fun createAppDirectories() {
        try {
            val pythonDir = filesDir.resolve("python")
            val configDir = filesDir.resolve("config")
            val logsDir = filesDir.resolve("logs")

            pythonDir.mkdirs()
            configDir.mkdirs()
            logsDir.mkdirs()

            Logger.logInfo("FinTrackApplication", "App directories created successfully")
        } catch (e: Exception) {
            Logger.logError("FinTrackApplication", "Failed to create app directories", e)
        }
    }

    /**
     * Copy static web assets from APK assets to filesDir so server.py can serve index.html.
     * Always overwrites to pick up updates on app upgrade.
     */
    private fun extractStaticAssets() {
        try {
            val staticDir = filesDir.resolve("static")
            staticDir.mkdirs()
            val assetFiles = assets.list("static") ?: return
            for (fileName in assetFiles) {
                val outFile = staticDir.resolve(fileName)
                assets.open("static/$fileName").use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
            Logger.logInfo("FinTrackApplication", "Static assets extracted to ${staticDir.absolutePath}")
        } catch (e: Exception) {
            Logger.logError("FinTrackApplication", "Failed to extract static assets", e)
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Logger.logInfo("FinTrackApplication", "Low memory warning received")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Logger.logInfo("FinTrackApplication", "Trim memory level: $level")
    }
    
    /**
     * Set up global uncaught exception handler
     * Task 14.1 Implementation
     * 
     * On uncaught exception:
     * - Log stack trace to crash_log.txt
     * - Attempt to stop server gracefully
     * - Show system crash dialog
     * 
     * Requirements: 13.1, 13.4
     */
    private fun setupGlobalExceptionHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Logger.logError("FinTrackApplication", "Uncaught exception in thread ${thread.name}", throwable)
                
                // Write crash log to file
                writeCrashLog(throwable, thread)
                
                // Attempt to stop server gracefully
                try {
                    if (::serverProcessManager.isInitialized) {
                        Logger.logInfo("FinTrackApplication", "Attempting to stop server gracefully after crash")
                        serverProcessManager.stopServer()
                    }
                } catch (e: Exception) {
                    Logger.logError("FinTrackApplication", "Failed to stop server during crash handling", e)
                }
                
            } catch (e: Exception) {
                // If crash handling itself fails, log it
                Logger.logError("FinTrackApplication", "Error in crash handler", e)
            } finally {
                // Call the default handler to show system crash dialog
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
        
        Logger.logInfo("FinTrackApplication", "Global exception handler configured")
    }
    
    /**
     * Write crash log to file
     * Task 14.1 Implementation
     */
    private fun writeCrashLog(throwable: Throwable, thread: Thread) {
        try {
            val logsDir = filesDir.resolve("logs")
            logsDir.mkdirs()
            
            val crashLogFile = logsDir.resolve("crash_log.txt")
            
            val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date())
            
            val crashReport = buildString {
                appendLine("===== FinTrack Android App Crash Report =====")
                appendLine("Timestamp: $timestamp")
                appendLine("Thread: ${thread.name}")
                appendLine("Exception: ${throwable.javaClass.name}")
                appendLine("Message: ${throwable.message}")
                appendLine()
                appendLine("Stack Trace:")
                appendLine(throwable.stackTraceToString())
                appendLine()
                
                // Include cause if present
                var cause = throwable.cause
                var level = 1
                while (cause != null && level <= 5) {
                    appendLine("Caused by (level $level): ${cause.javaClass.name}")
                    appendLine("Message: ${cause.message}")
                    appendLine(cause.stackTraceToString())
                    appendLine()
                    cause = cause.cause
                    level++
                }
                
                appendLine("===== End of Crash Report =====")
            }
            
            crashLogFile.writeText(crashReport)
            Logger.logInfo("FinTrackApplication", "Crash log written to ${crashLogFile.absolutePath}")
            
        } catch (e: Exception) {
            Logger.logError("FinTrackApplication", "Failed to write crash log", e)
        }
    }
    
    /**
     * Check for crash log from previous session
     * Task 14.1 Implementation
     * 
     * On next launch:
     * - Detect crash log exists
     * - Offer to share/export it
     * - Then delete
     * 
     * Requirements: 13.1, 13.4
     */
    private fun checkForCrashLog() {
        applicationScope.launch(Dispatchers.IO) {
            try {
                val logsDir = filesDir.resolve("logs")
                val crashLogFile = logsDir.resolve("crash_log.txt")
                
                if (crashLogFile.exists()) {
                    Logger.logInfo("FinTrackApplication", "Crash log detected from previous session")
                    
                    // Notify user on main thread
                    launch(Dispatchers.Main) {
                        showCrashLogDialog(crashLogFile)
                    }
                }
            } catch (e: Exception) {
                Logger.logError("FinTrackApplication", "Error checking for crash log", e)
            }
        }
    }
    
    /**
     * Show dialog to user about crash log
     * Task 14.1 Implementation
     */
    private fun showCrashLogDialog(crashLogFile: File) {
        try {
            // Get the current activity context
            // Since we're in Application class, we need to show this when MainActivity starts
            // We'll store a flag and let MainActivity handle the dialog
            val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("has_crash_log", true).apply()
            
            Logger.logInfo("FinTrackApplication", "Crash log flag set for MainActivity to handle")
            
        } catch (e: Exception) {
            Logger.logError("FinTrackApplication", "Error setting crash log flag", e)
        }
    }
}
