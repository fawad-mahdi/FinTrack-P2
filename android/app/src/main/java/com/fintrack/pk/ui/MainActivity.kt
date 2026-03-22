package com.fintrack.pk.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ShareCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.fintrack.pk.R
import com.fintrack.pk.server.ServerProcessManager
import com.fintrack.pk.utils.Logger
import com.fintrack.pk.utils.OAuthTokenManager
import com.fintrack.pk.utils.PythonRuntimeManager
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.*
import java.io.File

/**
 * Main activity that hosts the WebView and manages the app lifecycle.
 * 
 * This activity coordinates all major components including:
 * - ServerProcessManager: Manages the embedded Python FastAPI server
 * - WebViewManager: Handles the WebView rendering of the FinTrack interface
 * - PinAuthenticationActivity: Provides PIN-based security
 * 
 * Lifecycle Management:
 * - onCreate(): Initializes the activity and sets up the UI
 * - onResume(): Called when activity becomes visible to user
 * - onPause(): Called when activity is partially obscured
 * - onDestroy(): Cleans up resources when activity is destroyed
 * 
 * System Event Handling:
 * - onLowMemory(): Handles memory pressure situations
 * - onConfigurationChanged(): Handles device configuration changes (rotation, etc.)
 * 
 * Requirements: 12.1, 12.3, 12.5
 */
class MainActivity : AppCompatActivity() {

    private lateinit var serverProcessManager: ServerProcessManager
    private lateinit var pythonRuntimeManager: PythonRuntimeManager
    private lateinit var webViewManager: WebViewManager
    private lateinit var oauthTokenManager: OAuthTokenManager
    private lateinit var prefs: SharedPreferences
    
    // UI elements
    private lateinit var appBarLayout: com.google.android.material.appbar.AppBarLayout
    private lateinit var webView: android.webkit.WebView
    private lateinit var swipeRefreshLayout: androidx.swiperefreshlayout.widget.SwipeRefreshLayout
    private lateinit var progressIndicator: com.google.android.material.progressindicator.LinearProgressIndicator
    private lateinit var errorLayout: LinearLayout
    private lateinit var errorMessage: TextView
    private lateinit var retryButton: Button
    private lateinit var viewLogsButton: Button
    
    private var pauseTimestamp: Long = 0
    private var serverStartAttempts: Int = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    companion object {
        private const val PIN_REAUTH_THRESHOLD_MS = 5 * 60 * 1000L // 5 minutes
        private const val REQUEST_CODE_PIN_SETUP = 1001
        private const val REQUEST_CODE_PIN_LOGIN = 1002
        private const val REQUEST_CODE_PIN_REAUTH = 1003
        private const val MAX_SERVER_START_ATTEMPTS = 3
        private const val PREFS_NAME = "app_prefs"
        private const val KEY_ENCRYPTED_PIN = "encrypted_pin"
    }

    /**
     * Called when a new intent is delivered to the activity.
     * Handles OAuth flow initiation from SettingsActivity.
     * 
     * Task 9.2 Implementation:
     * - Check for "initiate_oauth" extra
     * - Call initiateOAuthFlow() if present
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        
        if (intent?.getBooleanExtra("initiate_oauth", false) == true) {
            Logger.logInfo("MainActivity", "OAuth flow initiation requested from SettingsActivity")
            initiateOAuthFlow()
        }
    }

    /**
     * Called when the activity is first created.
     * Initializes the UI and prepares for component setup.
     * 
     * Implementation (Task 6.2):
     * - Check for first launch and trigger Python runtime extraction
     * - Launch PinAuthenticationActivity for authentication
     * - Initialize ServerProcessManager and start server
     * - Show loading screen while server initializes
     * 
     * Requirement: 12.1, 2.1, 5.2
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        Logger.logInfo("MainActivity", "Activity created")
        Logger.logInfo("MainActivity", "Saved instance state: ${savedInstanceState != null}")
        
        // Initialize WebView reference first (needed by initializeViews)
        webView = findViewById(R.id.webView)
        
        // Initialize managers that are needed by initializeViews
        webViewManager = WebViewManager(webView, this)
        
        // Initialize UI elements (this calls setupPullToRefresh which needs webViewManager)
        initializeViews()

        // Android 15 edge-to-edge: app bar extends behind status bar
        ViewCompat.setOnApplyWindowInsetsListener(appBarLayout) { view, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.setPadding(0, top, 0, 0)
            insets
        }
        // Navigation bar padding on the window content frame
        val rootContent = findViewById<android.view.View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(rootContent) { view, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            view.setPadding(0, 0, 0, nav)
            insets
        }

        // Initialize remaining managers
        pythonRuntimeManager = PythonRuntimeManager(this)
        serverProcessManager = ServerProcessManager(this)
        oauthTokenManager = OAuthTokenManager(this)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        // Task 14.2: Set server crash callback
        serverProcessManager.setServerCrashCallback(object : ServerProcessManager.ServerCrashCallback {
            override fun onServerCrashedAndExhausted() {
                runOnUiThread {
                    showServerCrashedDialog()
                }
            }
        })
        
        // Initialize StorageManager and rotate logs on startup
        val storageManager = com.fintrack.pk.storage.StorageManager(this)
        storageManager.rotateLogs()
        
        // Task 14.1: Check for crash log from previous session
        checkForCrashLogDialog()
        
        // Initialize WebView
        initializeWebView()
        
        // Task 6.4: Restore state if recreating after process death
        if (savedInstanceState != null) {
            Logger.logInfo("MainActivity", "Recreating activity after process death, restoring state")
            restoreAppState(savedInstanceState)
        } else {
            // Also restore from SharedPreferences (for background termination)
            Logger.logInfo("MainActivity", "Checking for persistent state in SharedPreferences")
            restoreFromSharedPreferences()
        }
        
        // Start initialization flow
        startInitializationFlow()
    }
    
    /**
     * Initialize UI elements
     */
    private fun initializeViews() {
        // webView already initialized in onCreate before this method
        appBarLayout = findViewById(R.id.appBarLayout)
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout)
        progressIndicator = findViewById(R.id.progressIndicator)
        errorLayout = findViewById(R.id.errorLayout)
        errorMessage = findViewById(R.id.errorMessage)
        retryButton = findViewById(R.id.retryButton)
        viewLogsButton = findViewById(R.id.viewLogsButton)

        // Task 7.4: Set up pull-to-refresh
        setupPullToRefresh()

        retryButton.setOnClickListener {
            errorLayout.visibility = View.GONE
            showLoading()
            serverStartAttempts = 0 // Reset attempts counter
            startInitializationFlow()
        }

        viewLogsButton.setOnClickListener {
            showServerLogs()
        }
    }
    
    /**
     * Set up pull-to-refresh gesture for reloading the WebView.
     * 
     * Task 7.4 Implementation:
     * - Configure SwipeRefreshLayout
     * - Set refresh listener to reload WebView
     * - Set refresh callback in WebViewManager
     * 
     * Requirements: 4.4
     */
    private fun setupPullToRefresh() {
        Logger.logInfo("MainActivity", "Setting up pull-to-refresh")
        
        // Configure SwipeRefreshLayout colors
        swipeRefreshLayout.setColorSchemeColors(
            MaterialColors.getColor(
                this,
                com.google.android.material.R.attr.colorPrimary,
                android.graphics.Color.BLUE
            )
        )
        
        // Set refresh listener
        swipeRefreshLayout.setOnRefreshListener {
            Logger.logInfo("MainActivity", "Pull-to-refresh triggered")
            
            // Reload the WebView
            webViewManager.reload()
            
            // Stop refreshing animation after a short delay
            // The animation will stop when the page finishes loading
            swipeRefreshLayout.postDelayed({
                swipeRefreshLayout.isRefreshing = false
            }, 1000)
        }
        
        // Enable pull-to-refresh in WebViewManager
        webViewManager.enablePullToRefresh()
        
        Logger.logInfo("MainActivity", "Pull-to-refresh configured")
    }
    
    /**
     * Initialize WebView with callbacks for loading and error handling
     * Task 7.3 Implementation
     */
    private fun initializeWebView() {
        Logger.logInfo("MainActivity", "Initializing WebView")
        
        // Initialize WebView configuration
        webViewManager.initialize()
        
        // Set up loading callback
        webViewManager.setLoadingCallback(object : WebViewManager.LoadingCallback {
            override fun onPageStarted(url: String) {
                Logger.logInfo("MainActivity", "WebView page started: $url")
                runOnUiThread {
                    showLoading()
                }
            }

            override fun onPageFinished(url: String) {
                Logger.logInfo("MainActivity", "WebView page finished: $url")
                runOnUiThread {
                    hideLoading()
                }
            }
        })
        
        // Set up error callback
        webViewManager.setErrorCallback(object : WebViewManager.ErrorCallback {
            override fun onPageLoadError(errorCode: Int, description: String, failingUrl: String) {
                Logger.logError("MainActivity", "WebView page load error: $description")
                runOnUiThread {
                    showError("Failed to load page: $description")
                }
            }
            
            override fun onConnectionError() {
                Logger.logError("MainActivity", "WebView connection error")
                runOnUiThread {
                    showError("Cannot connect to server. Please check if the server is running.")
                }
            }
        })
        
        // Task 10.2: Set up sync callback
        webViewManager.setSyncCallback(object : WebViewManager.SyncCallback {
            override fun onSyncRequested() {
                Logger.logInfo("MainActivity", "Sync requested from WebView")
                prepareSyncOperation()
            }
        })
        
        Logger.logInfo("MainActivity", "WebView initialized with callbacks")
    }
    
    /**
     * Start the app initialization flow
     * Steps:
     * 1. Check first launch and extract Python runtime if needed
     * 2. Verify PIN setup and launch authentication
     * 3. Initialize and start server
     * 4. Load WebView
     */
    private fun startInitializationFlow() {
        scope.launch {
            try {
                // Step 1: Check first launch and extract Python runtime
                if (pythonRuntimeManager.isFirstLaunch()) {
                    Logger.logInfo("MainActivity", "First launch detected, extracting Python runtime")
                    updateLoadingText("Extracting runtime...")
                    
                    val extracted = withContext(Dispatchers.IO) {
                        pythonRuntimeManager.extractRuntime()
                    }
                    
                    if (!extracted) {
                        showError("Failed to extract Python runtime. Please reinstall the app.")
                        return@launch
                    }
                    
                    // Verify extraction
                    val verified = withContext(Dispatchers.IO) {
                        pythonRuntimeManager.verifyExtraction()
                    }
                    
                    if (!verified) {
                        showError("Python runtime verification failed. Please reinstall the app.")
                        return@launch
                    }
                    
                    Logger.logInfo("MainActivity", "Python runtime extracted and verified successfully")
                }
                
                // Step 2: Skip native PIN — the web frontend handles authentication
                Logger.logInfo("MainActivity", "Skipping native PIN, web frontend handles auth")
                initializeAndStartServer()
                
            } catch (e: Exception) {
                Logger.logError("MainActivity", "Error during initialization", e)
                showError("Initialization failed: ${e.message}")
            }
        }
    }
    
    /**
     * Launch PIN authentication activity
     */
    private fun launchPinAuthentication(mode: String, requestCode: Int) {
        val intent = if (mode == PinAuthenticationActivity.MODE_SETUP) {
            PinAuthenticationActivity.createSetupIntent(this)
        } else {
            PinAuthenticationActivity.createLoginIntent(this)
        }
        startActivityForResult(intent, requestCode)
    }
    
    /**
     * Handle result from PIN authentication
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        when (requestCode) {
            REQUEST_CODE_PIN_SETUP, REQUEST_CODE_PIN_LOGIN -> {
                if (resultCode == Activity.RESULT_OK) {
                    Logger.logInfo("MainActivity", "PIN authentication successful")
                    // Continue with server initialization
                    initializeAndStartServer()
                } else {
                    Logger.logInfo("MainActivity", "PIN authentication cancelled")
                    // User cancelled authentication, exit app
                    finish()
                }
            }
            REQUEST_CODE_PIN_REAUTH -> {
                if (resultCode == Activity.RESULT_OK) {
                    Logger.logInfo("MainActivity", "PIN re-authentication successful")
                    // Reset pause timestamp to prevent immediate re-auth
                    pauseTimestamp = 0
                    // Continue with normal resume flow
                    // Verify server is still running
                    if (!serverProcessManager.isServerRunning()) {
                        Logger.logWarning("MainActivity", "Server not running after re-auth, attempting restart")
                        scope.launch {
                            val restarted = withContext(Dispatchers.IO) {
                                serverProcessManager.startServer()
                            }
                            if (restarted) {
                                Logger.logInfo("MainActivity", "Server restarted successfully")
                            } else {
                                Logger.logError("MainActivity", "Failed to restart server after re-auth")
                                showError("Server failed to restart. Please restart the app.")
                            }
                        }
                    }
                } else {
                    Logger.logInfo("MainActivity", "PIN re-authentication failed or cancelled, exiting app")
                    // User failed re-authentication, exit app for security
                    finish()
                }
            }
        }
    }
    
    /**
     * Initialize and start the server
     */
    private fun initializeAndStartServer() {
        scope.launch {
            try {
                updateLoadingText("Starting server...")
                Logger.logInfo("MainActivity", "Initializing server (attempt ${serverStartAttempts + 1}/$MAX_SERVER_START_ATTEMPTS)")
                
                serverStartAttempts++
                
                val started = withContext(Dispatchers.IO) {
                    serverProcessManager.startServer()
                }
                
                if (!started) {
                    val status = serverProcessManager.getServerStatus()
                    val errorMsg = status.lastError ?: "Unknown error"
                    Logger.logError("MainActivity", "Server failed to start: $errorMsg")
                    
                    // Check if we should retry
                    if (serverStartAttempts < MAX_SERVER_START_ATTEMPTS) {
                        Logger.logInfo("MainActivity", "Retrying server start (attempt ${serverStartAttempts + 1}/$MAX_SERVER_START_ATTEMPTS)")
                        updateLoadingText("Retrying server start...")
                        delay(2000) // Wait 2 seconds before retry
                        initializeAndStartServer() // Retry
                    } else {
                        Logger.logError("MainActivity", "Server failed to start after $MAX_SERVER_START_ATTEMPTS attempts")
                        showErrorWithLogs("Server failed to start after $MAX_SERVER_START_ATTEMPTS retries: $errorMsg")
                    }
                    return@launch
                }
                
                Logger.logInfo("MainActivity", "Server started, waiting for health check")
                updateLoadingText("Waiting for server...")
                
                // Wait for server to become healthy
                val healthy = waitForServerHealth()
                
                if (healthy) {
                    Logger.logInfo("MainActivity", "Server is healthy, loading interface")
                    updateLoadingText("Loading app...")
                    
                    // Check OAuth status and show banner if not configured
                    checkOAuthStatus()
                    
                    // Task 7.3: Load WebView
                    webViewManager.loadApp()
                    
                    Logger.logInfo("MainActivity", "WebView loading initiated")
                } else {
                    Logger.logError("MainActivity", "Server health check failed")
                    
                    // Check if we should retry
                    if (serverStartAttempts < MAX_SERVER_START_ATTEMPTS) {
                        Logger.logInfo("MainActivity", "Retrying server start due to health check failure")
                        serverProcessManager.stopServer()
                        delay(2000)
                        initializeAndStartServer() // Retry
                    } else {
                        showErrorWithLogs("Server failed health check after $MAX_SERVER_START_ATTEMPTS attempts")
                    }
                }
                
            } catch (e: Exception) {
                Logger.logError("MainActivity", "Error starting server", e)
                
                // Check if we should retry
                if (serverStartAttempts < MAX_SERVER_START_ATTEMPTS) {
                    Logger.logInfo("MainActivity", "Retrying server start after exception")
                    delay(2000)
                    initializeAndStartServer() // Retry
                } else {
                    showErrorWithLogs("Failed to start server after $MAX_SERVER_START_ATTEMPTS attempts: ${e.message}")
                }
            }
        }
    }
    
    /**
     * Wait for server to become healthy
     * @return true if server is healthy, false if timeout
     */
    private suspend fun waitForServerHealth(): Boolean {
        val maxAttempts = 30 // 30 seconds timeout
        var attempts = 0

        while (attempts < maxAttempts) {
            delay(1000)
            attempts++

            val ready = withContext(Dispatchers.IO) { serverProcessManager.isServerReady() }
            if (ready) {
                // Server is accepting HTTP requests
                return true
            }

            Logger.logInfo("MainActivity", "Waiting for server health check (attempt $attempts/$maxAttempts)")
        }

        return false
    }
    
    /**
     * Show loading indicator
     */
    private fun showLoading(message: String? = null) {
        progressIndicator.visibility = View.VISIBLE
        webView.visibility = View.INVISIBLE
    }

    /**
     * Hide loading indicator
     */
    private fun hideLoading() {
        progressIndicator.visibility = View.GONE
        webView.visibility = View.VISIBLE
    }

    /**
     * Update loading text (no-op — loading is now indicated by LinearProgressIndicator)
     */
    private fun updateLoadingText(text: String) {
        Logger.logInfo("MainActivity", "Loading step: $text")
    }

    /**
     * Show error screen
     */
    private fun showError(message: String) {
        Logger.logError("MainActivity", "Showing error: $message")
        progressIndicator.visibility = View.GONE
        errorLayout.visibility = View.VISIBLE
        errorMessage.text = message
        viewLogsButton.visibility = View.GONE // Hide logs button for simple errors
    }

    /**
     * Show error screen with "View Logs" option
     * Used for server startup failures where logs would be helpful
     */
    private fun showErrorWithLogs(message: String) {
        Logger.logError("MainActivity", "Showing error with logs option: $message")
        progressIndicator.visibility = View.GONE
        errorLayout.visibility = View.VISIBLE
        errorMessage.text = message
        viewLogsButton.visibility = View.VISIBLE // Show logs button for server errors
    }

    /**
     * Show OAuth banner using Snackbar
     */
    private fun showOAuthBanner(message: String) {
        val coordinatorRoot = findViewById<androidx.coordinatorlayout.widget.CoordinatorLayout>(R.id.coordinatorRoot)
        val snackbar = Snackbar.make(coordinatorRoot, message, Snackbar.LENGTH_INDEFINITE)
        snackbar.setAction("OK") { snackbar.dismiss() }
        snackbar.show()
    }

    /**
     * Show network status banner using Snackbar
     */
    private fun showNetworkBanner(message: String) {
        val coordinatorRoot = findViewById<androidx.coordinatorlayout.widget.CoordinatorLayout>(R.id.coordinatorRoot)
        Snackbar.make(coordinatorRoot, message, Snackbar.LENGTH_LONG).show()
    }
    
    /**
     * Check OAuth status and show banner if not configured
     * Task 10.1 Implementation
     */
    private fun checkOAuthStatus() {
        if (!oauthTokenManager.hasToken()) {
            Logger.logInfo("MainActivity", "OAuth not configured, showing banner")
            runOnUiThread {
                showOAuthBanner("Connect Gmail to start syncing")
            }
        } else {
            Logger.logInfo("MainActivity", "OAuth configured")
        }
    }
    
    /**
     * Show server logs in a dialog or new activity
     * Task 10.1 Implementation
     */
    private fun showServerLogs() {
        scope.launch {
            try {
                Logger.logInfo("MainActivity", "Opening server logs")
                
                // Read server logs from file
                val logsDir = File(filesDir, "logs")
                val serverLogsFile = File(logsDir, "server_logs.txt")
                val appLogsFile = File(logsDir, "app_logs.txt")
                
                val logs = StringBuilder()
                
                if (serverLogsFile.exists()) {
                    logs.append("=== Server Logs ===\n\n")
                    // Read last 100 lines to avoid overwhelming the dialog
                    val serverLines = serverLogsFile.readLines().takeLast(100)
                    logs.append(serverLines.joinToString("\n"))
                    logs.append("\n\n")
                }
                
                if (appLogsFile.exists()) {
                    logs.append("=== App Logs ===\n\n")
                    // Read last 100 lines
                    val appLines = appLogsFile.readLines().takeLast(100)
                    logs.append(appLines.joinToString("\n"))
                }
                
                if (logs.isEmpty()) {
                    logs.append("No logs available")
                }
                
                // Show logs in a dialog
                runOnUiThread {
                    val dialog = androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("Server Logs")
                        .setMessage(logs.toString())
                        .setPositiveButton("Close") { dialog, _ -> dialog.dismiss() }
                        .setNeutralButton("Export") { _, _ ->
                            exportLogs()
                        }
                        .create()
                    
                    dialog.show()
                    
                    // Make the message scrollable
                    dialog.findViewById<TextView>(android.R.id.message)?.apply {
                        setTextIsSelectable(true)
                        maxLines = 20
                    }
                }
                
            } catch (e: Exception) {
                Logger.logError("MainActivity", "Failed to show logs", e)
                runOnUiThread {
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        "Failed to load logs: ${e.message}",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
    
    /**
     * Export logs using FileProvider + ShareCompat so the file is accessible to other apps.
     */
    private fun exportLogs() {
        try {
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
            val exportFile = File(cacheDir, "fintrack_logs_$timestamp.txt")

            val logsDir = File(filesDir, "logs")
            val appLogs = File(logsDir, "app_logs.txt").takeIf { it.exists() }?.readText() ?: "(empty)"
            val serverLogs = File(logsDir, "server_logs.txt").takeIf { it.exists() }?.readText() ?: "(empty)"
            exportFile.writeText("=== APP LOGS ===\n$appLogs\n\n=== SERVER LOGS ===\n$serverLogs")

            ShareCompat.IntentBuilder(this)
                .setType("text/plain")
                .setStream(FileProvider.getUriForFile(this, "$packageName.fileprovider", exportFile))
                .setChooserTitle(getString(R.string.debug_export_chooser))
                .startChooser()

        } catch (e: Exception) {
            Logger.logError("MainActivity", "Failed to export logs", e)
            android.widget.Toast.makeText(
                this,
                "Failed to export logs: ${e.message}",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Called when the activity becomes visible to the user.
     * Handles resuming from background and PIN re-authentication.
     * 
     * Implementation (Task 6.3):
     * - Check time spent in background
     * - Require PIN re-authentication if > 5 minutes
     * - Resume NetworkMonitor (placeholder for Task 9)
     * - Verify server is still running
     * 
     * Task 9.3 Implementation:
     * - Check and refresh OAuth token if needed
     * 
     * Requirement: 12.1, 12.2, 6.4
     */
    override fun onResume() {
        super.onResume()
        Logger.logInfo("MainActivity", "Activity resumed")
        
        // Check if we need to re-authenticate based on time in background
        if (pauseTimestamp > 0) {
            val timeInBackground = System.currentTimeMillis() - pauseTimestamp
            Logger.logInfo("MainActivity", "Time in background: ${timeInBackground}ms (${timeInBackground / 1000}s)")
            
            // Native PIN re-auth removed — web frontend handles authentication.
            // After long background, the WebView will show the PIN screen automatically.
        }
        
        // Complete any pending OAuth exchange (Kotlin does the HTTPS call)
        scope.launch {
            completePendingOAuthExchange()
            checkAndRefreshToken()
        }
        
        // Verify server is still running and restart if needed
        if (!serverProcessManager.isServerRunning()) {
            Logger.logWarning("MainActivity", "Server not running on resume, attempting restart")
            scope.launch {
                val restarted = withContext(Dispatchers.IO) {
                    serverProcessManager.startServer()
                }
                if (restarted) {
                    Logger.logInfo("MainActivity", "Server restarted successfully")
                } else {
                    Logger.logError("MainActivity", "Failed to restart server on resume")
                    showError("Server failed to restart. Please restart the app.")
                }
            }
        } else {
            Logger.logInfo("MainActivity", "Server is running normally")
        }
        
        // TODO: Task 9 - Resume network monitoring
        // networkMonitor.startMonitoring()
    }

    /**
     * Called when the activity is partially obscured or going to background.
     * Saves state and records timestamp for background duration tracking.
     * 
     * The server continues running in the background to maintain state.
     * 
     * Implementation (Task 6.3):
     * - Save WebView state (placeholder for Task 7)
     * - Stop NetworkMonitor to save battery (placeholder for Task 9)
     * - Keep ServerProcessManager running
     * 
     * Requirement: 12.1, 12.4
     */
    override fun onPause() {
        super.onPause()
        pauseTimestamp = System.currentTimeMillis()
        Logger.logInfo("MainActivity", "Activity paused at timestamp: $pauseTimestamp")
        
        // Save app state in case of termination
        saveAppState()
        
        // Task 7.3: Save WebView state for restoration
        val webViewState = webViewManager.saveState()
        Logger.logInfo("MainActivity", "WebView state saved")
        
        // TODO: Task 9 - Stop network monitoring to save battery
        // networkMonitor.stopMonitoring()
        
        // Note: Server continues running in background (Requirement 12.1)
        Logger.logInfo("MainActivity", "Server continues running in background")
    }

    /**
     * Called when the activity is being destroyed.
     * Cleans up all resources including stopping the server.
     * 
     * Implementation (Task 6.2):
     * - Stop ServerProcessManager and terminate Python server
     * - Clean up coroutines
     * 
     * Requirement: 12.1
     */
    override fun onDestroy() {
        super.onDestroy()
        Logger.logInfo("MainActivity", "Activity destroyed")
        
        // Task 7.3: Clean up WebView resources
        Logger.logInfo("MainActivity", "Cleaning up WebView")
        webViewManager.cleanup()
        
        // Stop server
        Logger.logInfo("MainActivity", "Stopping server")
        serverProcessManager.stopServer()
        serverProcessManager.cleanup()
        
        // Cancel coroutines
        scope.cancel()
        
        Logger.logInfo("MainActivity", "All resources cleaned up")
    }

    /**
     * Called when the system is running low on memory.
     * Implements memory pressure handling to prevent app termination.
     * 
     * Actions taken:
     * - Clear WebView cache to free memory (placeholder for Task 7)
     * - Log memory warning for diagnostics
     * - Save critical state in case of termination
     * - Trigger garbage collection
     * 
     * Implementation (Task 6.3):
     * - Clear WebView cache (placeholder for Task 7)
     * - Trigger garbage collection
     * - Save critical state
     * 
     * Requirement: 12.3
     */
    override fun onLowMemory() {
        super.onLowMemory()
        Logger.logWarning("MainActivity", "Low memory warning received")
        
        // Task 7.3: Clear WebView cache to free memory
        Logger.logInfo("MainActivity", "Clearing WebView cache to free memory")
        webViewManager.clearCache()
        
        // Save critical state in case of termination
        Logger.logInfo("MainActivity", "Saving critical state due to memory pressure")
        saveAppState()
        
        // Suggest garbage collection (not guaranteed)
        Logger.logInfo("MainActivity", "Triggering garbage collection")
        System.gc()
        
        Logger.logInfo("MainActivity", "Low memory handling complete")
    }

    /**
     * Called when device configuration changes (rotation, language, etc.).
     * Handles configuration changes gracefully without restarting the activity.
     * 
     * The activity is configured in AndroidManifest.xml to handle configuration
     * changes manually, preventing unnecessary restarts and server interruptions.
     * 
     * Task 6.4 Implementation:
     * - Log configuration changes for diagnostics
     * - Server continues running without interruption
     * - Placeholder for WebView layout updates (Task 7.3)
     * 
     * Requirement: 12.5
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        
        val orientation = when (newConfig.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> "landscape"
            Configuration.ORIENTATION_PORTRAIT -> "portrait"
            else -> "undefined"
        }
        
        Logger.logInfo("MainActivity", "Configuration changed - Orientation: $orientation")
        Logger.logInfo("MainActivity", "Screen size: ${newConfig.screenWidthDp}x${newConfig.screenHeightDp}dp")
        
        // Task 6.4: Server continues running without interruption (Requirement 12.5)
        Logger.logInfo("MainActivity", "Server continues running, no restart needed")
        
        // Task 7.4: Handle configuration change in WebViewManager
        webViewManager.handleConfigurationChange(newConfig)
        Logger.logInfo("MainActivity", "WebView configuration change handled")
    }

    /**
     * Save the current app state for restoration after process death.
     * 
     * Task 6.4 Implementation:
     * - Save server running status to Bundle
     * - Save timestamp for diagnostics
     * - Add placeholders for WebView state (Task 7.3)
     * 
     * Requirement: 12.3, 12.4
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        Logger.logInfo("MainActivity", "Saving instance state")
        
        // Task 6.4: Save server status to Bundle
        val serverRunning = serverProcessManager.isServerRunning()
        outState.putBoolean("server_running", serverRunning)
        Logger.logInfo("MainActivity", "Saved server running status: $serverRunning")
        
        // Save timestamp for diagnostics
        outState.putLong("save_timestamp", System.currentTimeMillis())
        Logger.logInfo("MainActivity", "Saved timestamp: ${System.currentTimeMillis()}")
        
        // Task 7.3: Save WebView state (URL and scroll position)
        val webViewState = webViewManager.saveState()
        outState.putBundle("webview_state", webViewState)
        Logger.logInfo("MainActivity", "Saved WebView state")
        
        Logger.logInfo("MainActivity", "Instance state saved successfully")
    }

    /**
     * Restore app state after process death and recreation.
     * 
     * Task 6.4 Implementation:
     * - Restore server status from Bundle
     * - If server was running, restart it
     * - Add placeholders for WebView URL and scroll position (Task 7.3)
     * 
     * Requirement: 12.3, 12.4
     */
    private fun restoreAppState(savedInstanceState: Bundle) {
        Logger.logInfo("MainActivity", "Restoring instance state from Bundle")
        
        // Task 6.4: Restore server status from Bundle
        val wasServerRunning = savedInstanceState.getBoolean("server_running", false)
        val saveTimestamp = savedInstanceState.getLong("save_timestamp", 0)
        
        Logger.logInfo("MainActivity", "Server was running: $wasServerRunning")
        Logger.logInfo("MainActivity", "State saved at timestamp: $saveTimestamp")
        
        if (saveTimestamp > 0) {
            val timeSinceSave = System.currentTimeMillis() - saveTimestamp
            Logger.logInfo("MainActivity", "Time since state save: ${timeSinceSave}ms (${timeSinceSave / 1000}s)")
        }
        
        // If server was running, it will be restarted in startInitializationFlow()
        // after PIN authentication
        if (wasServerRunning) {
            Logger.logInfo("MainActivity", "Server will be restarted after authentication")
        }
        
        // Task 7.3: Restore WebView state (URL and scroll position)
        val webViewState = savedInstanceState.getBundle("webview_state")
        if (webViewState != null) {
            Logger.logInfo("MainActivity", "Restoring WebView state")
            webViewManager.restoreState(webViewState)
        } else {
            Logger.logInfo("MainActivity", "No WebView state to restore")
        }
        
        Logger.logInfo("MainActivity", "Instance state restoration complete")
    }

    /**
     * Restore app state from SharedPreferences.
     * Called when onCreate() is called without savedInstanceState (normal app launch).
     * 
     * Task 6.4 Implementation:
     * - Check if server was running before termination
     * - Log last pause timestamp for diagnostics
     * - Prepare for server restart if needed
     * 
     * Requirement: 12.3, 12.4
     */
    private fun restoreFromSharedPreferences() {
        try {
            val wasServerRunning = prefs.getBoolean("server_was_running", false)
            val lastPauseTimestamp = prefs.getLong("last_pause_timestamp", 0)
            
            if (wasServerRunning) {
                Logger.logInfo("MainActivity", "Server was running before termination")
            }
            
            if (lastPauseTimestamp > 0) {
                val timeSinceLastPause = System.currentTimeMillis() - lastPauseTimestamp
                Logger.logInfo("MainActivity", "Last pause was ${timeSinceLastPause}ms ago (${timeSinceLastPause / 1000}s)")
            }
            
            // Server will be restarted in startInitializationFlow() after authentication
            
            // TODO: Task 7.3 - Restore WebView state from SharedPreferences if available
            // val webViewState = prefs.getString("webview_state", null)
            // webViewState?.let {
            //     Logger.logInfo("MainActivity", "Found saved WebView state")
            // }
            
        } catch (e: Exception) {
            Logger.logError("MainActivity", "Failed to restore state from SharedPreferences", e)
        }
    }

    /**
     * Save critical app state to SharedPreferences.
     * Called during onPause() and onLowMemory() to preserve state.
     * 
     * Implementation (Task 6.3):
     * - Save server running status
     * - Save timestamp for diagnostics
     * - Future: Save WebView state (Task 7)
     * 
     * Requirement: 12.3, 12.4
     */
    private fun saveAppState() {
        try {
            Logger.logInfo("MainActivity", "Saving app state")
            
            val editor = prefs.edit()
            editor.putBoolean("server_was_running", serverProcessManager.isServerRunning())
            editor.putLong("last_pause_timestamp", pauseTimestamp)
            editor.apply()
            
            Logger.logInfo("MainActivity", "App state saved successfully")
            
            // TODO: Task 7.3 - Save WebView state
            // val webViewState = webViewManager.saveState()
            // editor.putString("webview_state", webViewState)
            
        } catch (e: Exception) {
            Logger.logError("MainActivity", "Failed to save app state", e)
        }
    }

    /**
     * Require PIN re-authentication after extended background time.
     * 
     * Implementation (Task 6.3):
     * - Launch PinAuthenticationActivity for re-authentication
     * - Wait for authentication result
     * - Exit app if authentication fails
     * 
     * Requirement: 12.2
     */
    private fun requirePinAuthentication() {
        Logger.logInfo("MainActivity", "Launching PIN re-authentication")
        val intent = PinAuthenticationActivity.createLoginIntent(this)
        startActivityForResult(intent, REQUEST_CODE_PIN_REAUTH)
    }
    
    /**
     * Complete a pending OAuth token exchange on the Kotlin side.
     *
     * The Python server cannot make outbound HTTPS calls on Android 14+
     * (Chaquopy threads lack proper network binding, DNS fails).  Instead
     * the /oauth/callback route saves the auth code + PKCE verifier to a
     * file, and this method picks it up and does the actual exchange via
     * standard Java HttpURLConnection on Dispatchers.IO (which works).
     */
    private suspend fun completePendingOAuthExchange() = withContext(Dispatchers.IO) {
        try {
            // Step 1: Ask Python server for pending exchange data
            val pendingUrl = java.net.URL("http://127.0.0.1:8000/api/oauth/pending")
            val pendingConn = pendingUrl.openConnection() as java.net.HttpURLConnection
            pendingConn.connectTimeout = 3000
            pendingConn.readTimeout = 3000
            val pendingBody = try {
                pendingConn.inputStream.bufferedReader().readText()
            } catch (e: Exception) {
                Logger.logInfo("MainActivity", "No server for pending OAuth check")
                return@withContext
            } finally {
                pendingConn.disconnect()
            }

            val pendingJson = com.google.gson.JsonParser.parseString(pendingBody).asJsonObject
            if (pendingJson.get("pending")?.asBoolean != true) {
                return@withContext
            }

            Logger.logInfo("MainActivity", "Found pending OAuth exchange, completing via Kotlin")

            val code = pendingJson.get("code").asString
            val codeVerifier = pendingJson.get("code_verifier").asString
            val redirectUri = pendingJson.get("redirect_uri").asString
            val clientId = pendingJson.get("client_id").asString
            val clientSecret = pendingJson.get("client_secret").asString

            // Step 2: Exchange auth code for tokens via HTTPS (Kotlin-side DNS works)
            val formData = "code=${java.net.URLEncoder.encode(code, "UTF-8")}" +
                "&client_id=${java.net.URLEncoder.encode(clientId, "UTF-8")}" +
                "&client_secret=${java.net.URLEncoder.encode(clientSecret, "UTF-8")}" +
                "&redirect_uri=${java.net.URLEncoder.encode(redirectUri, "UTF-8")}" +
                "&grant_type=authorization_code" +
                "&code_verifier=${java.net.URLEncoder.encode(codeVerifier, "UTF-8")}"

            val tokenUrl = java.net.URL("https://oauth2.googleapis.com/token")
            val tokenConn = tokenUrl.openConnection() as javax.net.ssl.HttpsURLConnection
            tokenConn.requestMethod = "POST"
            tokenConn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            tokenConn.doOutput = true
            tokenConn.connectTimeout = 15000
            tokenConn.readTimeout = 15000

            tokenConn.outputStream.use { it.write(formData.toByteArray(Charsets.UTF_8)) }

            val responseCode = tokenConn.responseCode
            val responseBody = if (responseCode < 400) {
                tokenConn.inputStream.bufferedReader().readText()
            } else {
                val err = tokenConn.errorStream?.bufferedReader()?.readText() ?: "unknown"
                tokenConn.disconnect()
                Logger.logError("MainActivity", "Token exchange failed ($responseCode): $err")
                runOnUiThread {
                    android.widget.Toast.makeText(this@MainActivity,
                        "Token exchange failed: $err", android.widget.Toast.LENGTH_LONG).show()
                }
                return@withContext
            }
            tokenConn.disconnect()

            val tokens = com.google.gson.JsonParser.parseString(responseBody).asJsonObject
            Logger.logInfo("MainActivity", "Token exchange successful")

            // Step 3: Save token.json in google-auth compatible format
            val accessToken = tokens.get("access_token")?.asString ?: ""
            val refreshToken = tokens.get("refresh_token")?.asString ?: ""
            val expiresIn = tokens.get("expires_in")?.asLong ?: 3600L
            val expiry = java.time.Instant.now().plusSeconds(expiresIn)

            val tokenJson = com.google.gson.JsonObject().apply {
                addProperty("token", accessToken)
                addProperty("refresh_token", refreshToken)
                addProperty("token_uri", "https://oauth2.googleapis.com/token")
                addProperty("client_id", clientId)
                addProperty("client_secret", clientSecret)
                add("scopes", com.google.gson.JsonArray().apply {
                    add("https://www.googleapis.com/auth/gmail.readonly")
                })
                addProperty("expiry", expiry.toString().replace(".000000000Z", "Z"))
            }

            val configDir = File(filesDir, "config")
            configDir.mkdirs()
            File(configDir, "token.json").writeText(
                com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(tokenJson)
            )
            Logger.logInfo("MainActivity", "token.json saved by Kotlin")

            // Step 4: Clear the pending exchange
            val clearUrl = java.net.URL("http://127.0.0.1:8000/api/oauth/pending")
            val clearConn = clearUrl.openConnection() as java.net.HttpURLConnection
            clearConn.requestMethod = "DELETE"
            clearConn.connectTimeout = 3000
            try { clearConn.responseCode } catch (_: Exception) {}
            clearConn.disconnect()

            runOnUiThread {
                android.widget.Toast.makeText(this@MainActivity,
                    "Gmail connected successfully!", android.widget.Toast.LENGTH_SHORT).show()
            }

        } catch (e: Exception) {
            Logger.logError("MainActivity", "Pending OAuth exchange error: ${e.message}")
        }
    }

    /**
     * Check and refresh OAuth token if needed.
     *
     * Task 9.3 Implementation:
     * - Check if token exists
     * - Check if token is expired
     * - Refresh token if expired using OAuthTokenManager
     * - Log results for diagnostics
     * 
     * Task 9.4 Enhancement:
     * - Handle token refresh failures (e.g., user revoked access)
     * - Clear token.json if refresh fails
     * - Show message prompting re-authentication
     * 
     * This ensures the Python backend always has a valid token for Gmail sync.
     * 
     * Requirements: 6.2, 6.4
     */
    private suspend fun checkAndRefreshToken() {
        try {
            Logger.logInfo("MainActivity", "Checking OAuth token status")
            
            // Check if token exists
            if (!oauthTokenManager.hasToken()) {
                Logger.logInfo("MainActivity", "No OAuth token found, sync will require authentication")
                return
            }
            
            // Check if token is expired
            if (oauthTokenManager.isTokenExpired()) {
                Logger.logInfo("MainActivity", "OAuth token expired, attempting refresh")
                
                val refreshed = oauthTokenManager.refreshToken()
                
                if (refreshed) {
                    Logger.logInfo("MainActivity", "OAuth token refreshed successfully")
                } else {
                    // Task 9.4: Handle token refresh failure
                    Logger.logError("MainActivity", "Failed to refresh OAuth token - user may have revoked access")
                    
                    // Clear the invalid token
                    val deleted = oauthTokenManager.deleteToken()
                    if (deleted) {
                        Logger.logInfo("MainActivity", "Cleared invalid token.json")
                    }
                    
                    // Show message to user on UI thread
                    runOnUiThread {
                        android.widget.Toast.makeText(
                            this@MainActivity,
                            "Gmail access expired. Please reconnect from Settings.",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } else {
                Logger.logInfo("MainActivity", "OAuth token is valid, no refresh needed")
            }
            
        } catch (e: Exception) {
            Logger.logError("MainActivity", "Error checking/refreshing token: ${e.message}")
            // Don't show error to user - let the sync operation handle it
        }
    }
    
    /**
     * Initiate OAuth authorization flow for Gmail access
     * 
     * Task 9.2 Implementation:
     * - Load OAuth credentials from app-private storage
     * - Build AuthorizationRequest with gmail.readonly scope
     * - Launch auth flow using Custom Chrome Tab
     *
     * Requirements: 6.1, 6.3, 6.5
     */

    /**
     * Start OAuth flow via the Python server's /api/oauth/url endpoint.
     *
     * Uses loopback redirect (http://127.0.0.1:8000/oauth/callback) which
     * works with Google's "installed" (Desktop) OAuth client type.
     * The server handles the token exchange automatically.
     */
    private fun startServerOAuthFlow() {
        scope.launch {
            try {
                Logger.logInfo("MainActivity", "Starting server-based OAuth flow")

                val url = java.net.URL("http://127.0.0.1:8000/api/oauth/url")
                val response = withContext(Dispatchers.IO) {
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    try {
                        val body = conn.inputStream.bufferedReader().readText()
                        com.google.gson.JsonParser.parseString(body).asJsonObject
                    } finally {
                        conn.disconnect()
                    }
                }

                val authUrl = response.get("url")?.asString
                if (authUrl.isNullOrEmpty()) {
                    showError("Failed to get OAuth URL from server")
                    return@launch
                }

                Logger.logInfo("MainActivity", "Opening OAuth URL in browser")

                // Open in system browser (Chrome) — the redirect back to
                // http://127.0.0.1:8000/oauth/callback will be handled by
                // the Python server running on the device.
                runOnUiThread {
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(authUrl)
                    )
                    startActivity(intent)
                }

            } catch (e: Exception) {
                Logger.logError("MainActivity", "Server OAuth flow failed: ${e.message}")
                showError("Failed to start authentication: ${e.message}")
            }
        }
    }

    fun initiateOAuthFlow() {
        scope.launch {
            try {
                Logger.logInfo("MainActivity", "Initiating OAuth flow")
                
                // Load credentials
                val credentials = withContext(Dispatchers.IO) {
                    loadOAuthCredentials()
                }
                
                if (credentials == null) {
                    Logger.logError("MainActivity", "Failed to load OAuth credentials")
                    showError("OAuth credentials not found. Please configure credentials.json")
                    return@launch
                }
                
                Logger.logInfo("MainActivity", "OAuth credentials loaded successfully")
                
                // Create AuthorizationServiceConfiguration
                val serviceConfig = net.openid.appauth.AuthorizationServiceConfiguration(
                    android.net.Uri.parse(credentials.authUri),
                    android.net.Uri.parse(credentials.tokenUri)
                )
                
                // Build AuthorizationRequest
                val authRequest = net.openid.appauth.AuthorizationRequest.Builder(
                    serviceConfig,
                    credentials.clientId,
                    net.openid.appauth.ResponseTypeValues.CODE,
                    android.net.Uri.parse(com.fintrack.pk.utils.Constants.OAUTH_REDIRECT_URI)
                )
                    .setScope("https://www.googleapis.com/auth/gmail.readonly")
                    .build()
                
                Logger.logInfo("MainActivity", "Authorization request built")
                Logger.logInfo("MainActivity", "Redirect URI: ${com.fintrack.pk.utils.Constants.OAUTH_REDIRECT_URI}")
                Logger.logInfo("MainActivity", "Scope: https://www.googleapis.com/auth/gmail.readonly")
                
                // Launch auth flow using Custom Chrome Tab
                val authService = net.openid.appauth.AuthorizationService(this@MainActivity)
                val authIntent = authService.getAuthorizationRequestIntent(authRequest)
                
                Logger.logInfo("MainActivity", "Launching Custom Chrome Tab for OAuth")
                startActivity(authIntent)
                
            } catch (e: Exception) {
                Logger.logError("MainActivity", "Failed to initiate OAuth flow: ${e.message}")
                showError("Failed to start OAuth flow: ${e.message}")
            }
        }
    }
    
    /**
     * Load OAuth credentials from app-private storage
     * 
     * @return OAuthCredentials if successful, null otherwise
     */
    private fun loadOAuthCredentials(): OAuthCredentials? {
        return try {
            val configDir = File(filesDir, com.fintrack.pk.utils.Constants.CONFIG_DIR)
            val credentialsFile = File(configDir, com.fintrack.pk.utils.Constants.CREDENTIALS_FILE)
            
            if (!credentialsFile.exists()) {
                Logger.logError("MainActivity", "credentials.json not found in app-private storage")
                return null
            }
            
            val json = credentialsFile.readText()
            val gson = com.google.gson.Gson()
            val wrapper = gson.fromJson(json, CredentialsWrapper::class.java)
            wrapper.installed
        } catch (e: Exception) {
            Logger.logError("MainActivity", "Failed to load credentials: ${e.message}")
            null
        }
    }
    
    /**
     * Data classes for parsing credentials.json
     */
    data class CredentialsWrapper(
        val installed: OAuthCredentials
    )

    data class OAuthCredentials(
        @com.google.gson.annotations.SerializedName("client_id")
        val clientId: String,
        
        @com.google.gson.annotations.SerializedName("project_id")
        val projectId: String,
        
        @com.google.gson.annotations.SerializedName("auth_uri")
        val authUri: String,
        
        @com.google.gson.annotations.SerializedName("token_uri")
        val tokenUri: String,
        
        @com.google.gson.annotations.SerializedName("auth_provider_x509_cert_url")
        val authProviderCertUrl: String,
        
        @com.google.gson.annotations.SerializedName("client_secret")
        val clientSecret: String,
        
        @com.google.gson.annotations.SerializedName("redirect_uris")
        val redirectUris: List<String>
    )
    
    /**
     * Prepare for sync operation by checking OAuth status and connectivity.
     * 
     * Task 10.2 Implementation:
     * - Check internet connectivity with simple HEAD request to https://www.googleapis.com
     * - Check if token.json exists and is not expired
     * - If no valid token: trigger OAuth flow, then let user manually retry sync
     * - If valid token exists: allow sync to proceed through Python backend
     * 
     * This replaces the complex NetworkMonitor component with a simple connectivity check.
     * 
     * Requirements: 6.1, 6.2, 11.1
     */
    private fun prepareSyncOperation() {
        scope.launch {
            try {
                Logger.logInfo("MainActivity", "Preparing sync operation")
                
                // Step 1: Check internet connectivity
                updateLoadingText("Checking connectivity...")
                val hasConnectivity = checkConnectivity()
                
                if (!hasConnectivity) {
                    Logger.logWarning("MainActivity", "No internet connection for sync")
                    runOnUiThread {
                        android.widget.Toast.makeText(
                            this@MainActivity,
                            "No internet connection",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                    return@launch
                }
                
                Logger.logInfo("MainActivity", "Connectivity check passed")
                
                // Step 2: Check OAuth token status
                if (!oauthTokenManager.hasToken()) {
                    Logger.logInfo("MainActivity", "No OAuth token found, triggering server-based OAuth flow")
                    runOnUiThread {
                        android.widget.Toast.makeText(
                            this@MainActivity,
                            "Gmail not connected. Starting authentication...",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }

                    // Get OAuth URL from server and open in browser
                    startServerOAuthFlow()
                    return@launch
                }
                
                // Step 3: Check if token is expired and refresh if needed
                if (oauthTokenManager.isTokenExpired()) {
                    Logger.logInfo("MainActivity", "Token expired, attempting refresh before sync")
                    updateLoadingText("Refreshing token...")
                    
                    val refreshed = oauthTokenManager.refreshToken()
                    
                    if (!refreshed) {
                        Logger.logError("MainActivity", "Token refresh failed, triggering OAuth flow")
                        runOnUiThread {
                            android.widget.Toast.makeText(
                                this@MainActivity,
                                "Gmail access expired. Please reconnect...",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                        
                        // Clear invalid token
                        oauthTokenManager.deleteToken()

                        // Trigger server-based OAuth flow
                        startServerOAuthFlow()
                        return@launch
                    }
                    
                    Logger.logInfo("MainActivity", "Token refreshed successfully")
                }
                
                // Step 4: Token is valid, sync can proceed
                Logger.logInfo("MainActivity", "OAuth token is valid, sync can proceed")
                runOnUiThread {
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        "Sync ready - proceeding through backend",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                
                // The actual sync will be handled by the Python backend
                // The WebView frontend will make the API call to /api/sync
                
            } catch (e: Exception) {
                Logger.logError("MainActivity", "Error preparing sync operation: ${e.message}")
                runOnUiThread {
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        "Sync preparation failed: ${e.message}",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
    
    /**
     * Check internet connectivity with a simple HEAD request to https://www.googleapis.com
     * 
     * Task 10.2 Implementation:
     * - Perform HEAD request to https://www.googleapis.com
     * - Return true if successful, false otherwise
     * - Use short timeout (5 seconds) to avoid blocking
     * 
     * This replaces the NetworkMonitor component with a simple connectivity check.
     * 
     * Requirements: 11.1
     */
    private suspend fun checkConnectivity(): Boolean = withContext(Dispatchers.IO) {
        try {
            Logger.logInfo("MainActivity", "Checking connectivity to https://www.googleapis.com")
            
            val url = java.net.URL("https://www.googleapis.com")
            val connection = url.openConnection() as java.net.HttpURLConnection
            
            try {
                connection.requestMethod = "HEAD"
                connection.connectTimeout = 5000 // 5 seconds
                connection.readTimeout = 5000
                connection.instanceFollowRedirects = false
                
                connection.connect()
                
                val responseCode = connection.responseCode
                Logger.logInfo("MainActivity", "Connectivity check response code: $responseCode")
                
                // Any HTTP response (even 4xx) proves we have internet connectivity.
                // Only network-level failures (timeouts, DNS, etc.) mean no connection.
                val isConnected = responseCode > 0
                Logger.logInfo("MainActivity", "Connectivity check result: $isConnected")
                
                return@withContext isConnected
                
            } finally {
                connection.disconnect()
            }
            
        } catch (e: java.net.UnknownHostException) {
            Logger.logWarning("MainActivity", "Connectivity check failed: Unknown host")
            return@withContext false
        } catch (e: java.net.SocketTimeoutException) {
            Logger.logWarning("MainActivity", "Connectivity check failed: Timeout")
            return@withContext false
        } catch (e: java.io.IOException) {
            Logger.logWarning("MainActivity", "Connectivity check failed: ${e.message}")
            return@withContext false
        } catch (e: Exception) {
            Logger.logError("MainActivity", "Connectivity check error: ${e.message}")
            return@withContext false
        }
    }
    
    /**
     * Create options menu with settings icon
     * Task 13.1 Implementation
     */
    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }
    
    /**
     * Handle menu item selection
     * Task 13.1 Implementation
     */
    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                Logger.logInfo("MainActivity", "Settings menu item clicked")
                val intent = Intent(this, SettingsActivity::class.java)
                startActivity(intent)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    /**
     * Check for crash log from previous session and offer to share/export it
     * Task 14.1 Implementation
     * 
     * Requirements: 13.1, 13.4
     */
    private fun checkForCrashLogDialog() {
        val hasCrashLog = prefs.getBoolean("has_crash_log", false)
        
        if (hasCrashLog) {
            Logger.logInfo("MainActivity", "Crash log detected from previous session")
            
            // Clear the flag
            prefs.edit().putBoolean("has_crash_log", false).apply()
            
            // Get crash log file
            val logsDir = File(filesDir, "logs")
            val crashLogFile = File(logsDir, "crash_log.txt")
            
            if (crashLogFile.exists()) {
                // Show dialog to user
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("App Crashed Previously")
                    .setMessage("The app crashed in the previous session. Would you like to export the crash log for troubleshooting?")
                    .setPositiveButton("Export") { _, _ ->
                        exportCrashLog(crashLogFile)
                    }
                    .setNegativeButton("Dismiss") { _, _ ->
                        // Delete crash log
                        deleteCrashLog(crashLogFile)
                    }
                    .setCancelable(false)
                    .show()
            }
        }
    }
    
    /**
     * Export crash log to external storage
     * Task 14.1 Implementation
     */
    private fun exportCrashLog(crashLogFile: File) {
        scope.launch {
            try {
                Logger.logInfo("MainActivity", "Exporting crash log")
                
                // Read crash log content
                val crashContent = withContext(Dispatchers.IO) {
                    crashLogFile.readText()
                }
                
                // Create a shareable file
                val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                    .format(java.util.Date())
                val exportFileName = "fintrack_crash_${timestamp}.txt"
                
                // Use a temporary file in cache directory for sharing
                val cacheDir = cacheDir
                val exportFile = File(cacheDir, exportFileName)
                exportFile.writeText(crashContent)
                
                // Create share intent
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "FinTrack Crash Log")
                    putExtra(Intent.EXTRA_TEXT, crashContent)
                    
                    // Use FileProvider for sharing the file
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        this@MainActivity,
                        "${packageName}.fileprovider",
                        exportFile
                    )
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                
                startActivity(Intent.createChooser(shareIntent, "Share Crash Log"))
                
                // Delete the original crash log after sharing
                deleteCrashLog(crashLogFile)
                
                Logger.logInfo("MainActivity", "Crash log exported successfully")
                
            } catch (e: Exception) {
                Logger.logError("MainActivity", "Failed to export crash log", e)
                runOnUiThread {
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        "Failed to export crash log: ${e.message}",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                
                // Still delete the crash log even if export failed
                deleteCrashLog(crashLogFile)
            }
        }
    }
    
    /**
     * Delete crash log file
     * Task 14.1 Implementation
     */
    private fun deleteCrashLog(crashLogFile: File) {
        try {
            if (crashLogFile.exists()) {
                crashLogFile.delete()
                Logger.logInfo("MainActivity", "Crash log deleted")
            }
        } catch (e: Exception) {
            Logger.logError("MainActivity", "Failed to delete crash log", e)
        }
    }
    
    /**
     * Show dialog when server crashes and exhausts restart attempts
     * Task 14.2 Implementation
     * 
     * Dialog options:
     * - Restart App: Restart the entire application
     * - Export Logs: Export server logs for troubleshooting
     * 
     * Requirements: 2.4, 2.5, 13.1
     */
    private fun showServerCrashedDialog() {
        Logger.logError("MainActivity", "Showing server crashed dialog")
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Server Crashed")
            .setMessage("The backend server crashed and could not be restarted. You can restart the app or export logs for troubleshooting.")
            .setPositiveButton("Restart App") { _, _ ->
                Logger.logInfo("MainActivity", "User chose to restart app")
                restartApp()
            }
            .setNegativeButton("Export Logs") { _, _ ->
                Logger.logInfo("MainActivity", "User chose to export logs")
                exportServerLogsAfterCrash()
            }
            .setCancelable(false)
            .show()
    }
    
    /**
     * Restart the application
     * Task 14.2 Implementation
     */
    private fun restartApp() {
        try {
            Logger.logInfo("MainActivity", "Restarting application")
            
            // Create intent to restart the app
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            
            // Finish current activity
            finish()
            
            // Start new instance
            startActivity(intent)
            
            // Exit the process to ensure clean restart
            android.os.Process.killProcess(android.os.Process.myPid())
            
        } catch (e: Exception) {
            Logger.logError("MainActivity", "Failed to restart app", e)
            android.widget.Toast.makeText(
                this,
                "Failed to restart app: ${e.message}",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }
    
    /**
     * Export server logs after crash
     * Task 14.2 Implementation
     */
    private fun exportServerLogsAfterCrash() {
        scope.launch {
            try {
                Logger.logInfo("MainActivity", "Exporting server logs after crash")
                
                val logsDir = File(filesDir, "logs")
                val serverLogsFile = File(logsDir, "server_logs.txt")
                val appLogsFile = File(logsDir, "app_logs.txt")
                
                // Combine logs
                val combinedLogs = buildString {
                    appendLine("===== FinTrack Server Crash Logs =====")
                    appendLine("Timestamp: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
                    appendLine()
                    
                    if (serverLogsFile.exists()) {
                        appendLine("----- Server Logs -----")
                        appendLine(serverLogsFile.readText())
                        appendLine()
                    }
                    
                    if (appLogsFile.exists()) {
                        appendLine("----- App Logs -----")
                        appendLine(appLogsFile.readText())
                        appendLine()
                    }
                    
                    appendLine("===== End of Logs =====")
                }
                
                // Create shareable file
                val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                    .format(java.util.Date())
                val exportFileName = "fintrack_server_crash_${timestamp}.txt"
                
                val cacheDir = cacheDir
                val exportFile = File(cacheDir, exportFileName)
                exportFile.writeText(combinedLogs)
                
                // Create share intent
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "FinTrack Server Crash Logs")
                    putExtra(Intent.EXTRA_TEXT, combinedLogs)
                    
                    // Use FileProvider for sharing the file
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        this@MainActivity,
                        "${packageName}.fileprovider",
                        exportFile
                    )
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                
                startActivity(Intent.createChooser(shareIntent, "Share Server Crash Logs"))
                
                Logger.logInfo("MainActivity", "Server crash logs exported successfully")
                
                // After exporting, offer to restart
                runOnUiThread {
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        "Logs exported. Please restart the app.",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
                
            } catch (e: Exception) {
                Logger.logError("MainActivity", "Failed to export server crash logs", e)
                runOnUiThread {
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        "Failed to export logs: ${e.message}",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
}
