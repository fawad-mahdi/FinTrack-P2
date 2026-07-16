package com.fintrack.pk.server

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.PyObject
import com.fintrack.pk.data.ServerStatus
import com.fintrack.pk.utils.Logger
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Manages the lifecycle of the Python FastAPI server process
 * Handles starting, stopping, monitoring, and restarting the server
 * 
 * Requirements: 2.1, 2.2, 2.3
 */
class ServerProcessManager(private val context: Context) {

    companion object {
        private const val SERVER_HOST = "127.0.0.1"
        private const val SERVER_PORT = 8000
        private const val HEALTH_CHECK_INTERVAL_MS = 10000L // 10 seconds
        private const val HEALTH_CHECK_TIMEOUT_MS = 5000L // 5 seconds — increased to handle GIL contention during sync
        private const val CRASH_DETECT_DURATION_MS = 180000L // 3 minutes continuous unhealthy = crash
        private const val MAX_RESTART_ATTEMPTS = 5
        private const val RESTART_WINDOW_MS = 60000L // 1 minute

        /**
         * Per-process token required on all API calls to the local server.
         * The server binds to 127.0.0.1, but any app on the device can reach
         * loopback ports — this proves the caller is our own WebView/app.
         * Injected into the Python server via FINTRACK_API_TOKEN and handed
         * to the WebView through a one-time ?boot= query parameter.
         */
        val apiToken: String = java.util.UUID.randomUUID().toString()
    }
    
    /**
     * Callback interface for server crash events
     * Task 14.2 Implementation
     */
    interface ServerCrashCallback {
        fun onServerCrashedAndExhausted()
    }

    private var serverProcess: Process? = null
    private var serverThread: Thread? = null
    private var pythonModule: PyObject? = null
    private var healthCheckJob: Job? = null
    @Volatile private var isRunning = false
    private var serverStartTime: Long = 0
    private var lastError: String? = null
    private var firstFailureTimeMs: Long = 0
    private val restartAttempts = mutableListOf<Long>()
    private var crashCallback: ServerCrashCallback? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(HEALTH_CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(HEALTH_CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Set callback for server crash events
     * Task 14.2 Implementation
     */
    fun setServerCrashCallback(callback: ServerCrashCallback?) {
        this.crashCallback = callback
    }

    /**
     * Start the FastAPI server process
     * @return true if server started successfully, false otherwise
     */
    @Synchronized
    fun startServer(): Boolean {
        if (isRunning) {
            Logger.logInfo("ServerProcessManager", "Server is already running")
            return true
        }

        try {
            Logger.logInfo("ServerProcessManager", "Starting FastAPI server")

            // Setup environment variables for Python
            setupEnvironment()

            // Get Python instance
            val python = Python.getInstance()
            
            // Import the main module
            pythonModule = python.getModule("main")
            
            // Start server in a background thread
            serverThread = Thread {
                try {
                    Logger.logInfo("ServerProcessManager", "Starting Python server thread")
                    
                    // Call the start_server function from main.py
                    // This will block until the server stops
                    // Inject directory paths into Python os.environ (System.setProperty is JVM-only)
                    val envDirs = mapOf(
                        "FINTRACK_APP_DIR" to (context.filesDir.absolutePath),
                        "FINTRACK_CONFIG_DIR" to (File(context.filesDir, "config").absolutePath),
                        "FINTRACK_LOGS_DIR" to (File(context.filesDir, "logs").absolutePath),
                        "FINTRACK_DB_DIR" to File(context.filesDir, "databases").absolutePath,
<<<<<<< HEAD
                        "FINTRACK_API_TOKEN" to apiToken
=======
                        // Per-launch capability token: the server rejects any
                        // /api/* request that does not present it (AUTH-04)
                        "FINTRACK_API_TOKEN" to com.fintrack.pk.utils.ApiTokenProvider.token
>>>>>>> cf5955ab49e83f742b37cfff8091bf38565c16bf
                    )
                    injectPythonEnvironment(python, envDirs)
                    pythonModule?.callAttr("start_server", SERVER_HOST, SERVER_PORT)
                    
                } catch (e: Exception) {
                    lastError = "Server thread error: ${e.message}"
                    Logger.logError("ServerProcessManager", lastError!!, e)
                    isRunning = false
                    // Use retry logic instead of immediately giving up
                    handleServerCrash()
                }
            }
            
            serverThread?.start()
            isRunning = serverThread?.isAlive == true  // Only true if thread didn't crash instantly
            serverStartTime = System.currentTimeMillis()
            lastError = null

            Logger.logInfo("ServerProcessManager", "Server thread started, waiting for health check")

            // Start health monitoring
            startHealthMonitoring()

            return true

        } catch (e: Exception) {
            lastError = "Failed to start server: ${e.message}"
            Logger.logError("ServerProcessManager", lastError!!, e)
            isRunning = false
            return false
        }
    }

    /**
     * Stop the FastAPI server process
     */
    fun stopServer() {
        try {
            Logger.logInfo("ServerProcessManager", "Stopping FastAPI server")

            // Stop health monitoring
            healthCheckJob?.cancel()
            healthCheckJob = null

            // Interrupt the server thread
            serverThread?.let { thread ->
                if (thread.isAlive) {
                    thread.interrupt()
                    
                    // Wait up to 5 seconds for graceful shutdown
                    thread.join(5000)
                    
                    if (thread.isAlive) {
                        Logger.logInfo("ServerProcessManager", "Server thread did not terminate gracefully")
                        // Note: We can't force-kill a thread in Java, but interrupting should work
                    }
                }
            }

            serverThread = null
            pythonModule = null
            serverProcess = null
            isRunning = false
            firstFailureTimeMs = 0

            Logger.logInfo("ServerProcessManager", "Server stopped successfully")

        } catch (e: Exception) {
            Logger.logError("ServerProcessManager", "Error stopping server", e)
        }
    }

    /**
     * Check if the server is currently running
     * @return true if server is running, false otherwise
     */
    fun isServerRunning(): Boolean {
        return isRunning && serverThread?.isAlive == true
    }

    /**
     * Check if the server is ready to accept HTTP requests by performing an actual health check.
     * Returns true only when the HTTP endpoint responds with a 2xx status.
     */
    fun isServerReady(): Boolean {
        return performHealthCheck()
    }

    /**
     * Get the current server status
     * @return ServerStatus object with current state
     */
    fun getServerStatus(): ServerStatus {
        val uptime = if (isRunning) {
            System.currentTimeMillis() - serverStartTime
        } else {
            0L
        }

        return ServerStatus(
            isRunning = isServerRunning(),
            port = SERVER_PORT,
            uptime = uptime,
            lastError = lastError
        )
    }

    /**
     * Restart the server
     * Stops the current server and starts a new one
     */
    suspend fun restartServer() {
        Logger.logInfo("ServerProcessManager", "Restarting server")
        stopServer()

        // Small delay before restart
        delay(1000)

        startServer()
    }

    /**
     * Setup environment variables for Python server
     */
    private fun setupEnvironment() {
        try {
            // Set up directory paths
            val appFilesDir = context.filesDir.absolutePath
            val configDir = File(context.filesDir, "config")
            val logsDir = File(context.filesDir, "logs")
            val dbDir = File(context.filesDir, "databases")
            
            // Create directories if they don't exist
            configDir.mkdirs()
            logsDir.mkdirs()
            dbDir.mkdirs()
            
            // Set environment variables that Python will read
            System.setProperty("FINTRACK_APP_DIR", appFilesDir)
            System.setProperty("FINTRACK_CONFIG_DIR", configDir.absolutePath)
            System.setProperty("FINTRACK_LOGS_DIR", logsDir.absolutePath)
            System.setProperty("FINTRACK_DB_DIR", dbDir.absolutePath)
            
            Logger.logInfo("ServerProcessManager", "Environment setup complete")
            Logger.logInfo("ServerProcessManager", "App files dir: $appFilesDir")
            Logger.logInfo("ServerProcessManager", "Config dir: ${configDir.absolutePath}")
            Logger.logInfo("ServerProcessManager", "Logs dir: ${logsDir.absolutePath}")
            Logger.logInfo("ServerProcessManager", "DB dir: ${dbDir.absolutePath}")
            
        } catch (e: Exception) {
            Logger.logError("ServerProcessManager", "Error setting up environment", e)
        }
    }

    /**
     * Inject directory paths into Python's os.environ so os.getenv() works.
     * System.setProperty() is JVM-only. Python os.environ does not see those.
     */
    private fun injectPythonEnvironment(python: Python, dirs: Map<String, String>) {
        try {
            val pyOs = python.getModule("os")
            val pyEnviron = pyOs.get("environ")
            dirs.forEach { (key, value) ->
                pyEnviron?.callAttr("__setitem__", key, value)
            }
            Logger.logInfo("ServerProcessManager", "Python os.environ injected: ${dirs.keys}")
        } catch (e: Exception) {
            Logger.logError("ServerProcessManager", "Failed to inject Python environment", e)
        }
    }

    /**
     * Start health monitoring coroutine
     * Periodically checks if server is responding
     */
    private fun startHealthMonitoring() {
        healthCheckJob = scope.launch {
            delay(2000) // Initial delay to let server start
            
            while (isActive && isRunning) {
                try {
                    val healthy = performHealthCheck()
                    
                    if (healthy) {
                        firstFailureTimeMs = 0
                    } else {
                        val now = System.currentTimeMillis()
                        if (firstFailureTimeMs == 0L) {
                            firstFailureTimeMs = now
                        }
                        val unhealthyDuration = now - firstFailureTimeMs
                        Logger.logInfo(
                            "ServerProcessManager",
                            "Health check failed (unhealthy for ${unhealthyDuration / 1000}s / ${CRASH_DETECT_DURATION_MS / 1000}s threshold)"
                        )

                        if (unhealthyDuration >= CRASH_DETECT_DURATION_MS) {
                            Logger.logError("ServerProcessManager", "Server unhealthy for ${unhealthyDuration / 1000}s, declaring crash")
                            handleServerCrash()
                        }
                    }
                    
                } catch (e: Exception) {
                    Logger.logError("ServerProcessManager", "Error during health check", e)
                }
                
                delay(HEALTH_CHECK_INTERVAL_MS)
            }
        }
    }

    /**
     * Perform a health check on the server
     * @return true if server is healthy, false otherwise
     */
    private fun performHealthCheck(): Boolean {
        return try {
            val request = Request.Builder()
                .url("http://${SERVER_HOST}:${SERVER_PORT}/health")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            val isHealthy = response.isSuccessful
            response.close()
            
            isHealthy
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Handle server crash by attempting restart with exponential backoff
     * Task 14.2 Enhancement: Notify callback when restart attempts exhausted
     */
    private fun handleServerCrash() {
        try {
            Logger.logInfo("ServerProcessManager", "Handling server crash")

            // If the server is still responding, this crash came from a stale/parallel thread.
            // Don't kill the healthy server.
            if (performHealthCheck()) {
                Logger.logInfo("ServerProcessManager", "Server is still healthy — crash was from a parallel/stale thread, ignoring")
                return
            }

            // Check if we've exceeded restart attempts in the time window
            val now = System.currentTimeMillis()
            restartAttempts.removeAll { it < now - RESTART_WINDOW_MS }
            
            if (restartAttempts.size >= MAX_RESTART_ATTEMPTS) {
                lastError = "Server crashed too many times, giving up"
                Logger.logError("ServerProcessManager", lastError!!)
                stopServer()
                
                // Task 14.2: Notify callback that server crashed and exhausted restart attempts
                crashCallback?.onServerCrashedAndExhausted()
                
                return
            }
            
            // Calculate backoff delay (exponential: 1s, 2s, 4s, 8s, max 10s)
            val backoffDelay = minOf(
                1000L * (1 shl restartAttempts.size),
                10000L
            )
            
            Logger.logInfo(
                "ServerProcessManager",
                "Attempting restart in ${backoffDelay}ms (attempt ${restartAttempts.size + 1}/${MAX_RESTART_ATTEMPTS})"
            )
            
            restartAttempts.add(now)
            
            scope.launch {
                delay(backoffDelay)
                restartServer()
            }
            
        } catch (e: Exception) {
            Logger.logError("ServerProcessManager", "Error handling server crash", e)
        }
    }

    /**
     * Get the logs directory
     */
    private fun getLogsDirectory(): File {
        return context.filesDir.resolve("logs")
    }

    /**
     * Clean up resources
     * Should be called when the manager is no longer needed
     */
    fun cleanup() {
        stopServer()
        scope.cancel()
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }
}
