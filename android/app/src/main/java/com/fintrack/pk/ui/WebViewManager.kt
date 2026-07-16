package com.fintrack.pk.ui

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.ViewTreeObserver
import android.webkit.WebView
import com.fintrack.pk.utils.Constants
import com.fintrack.pk.utils.Logger

/**
 * WebViewManager manages the WebView component for displaying the FinTrack web interface.
 * 
 * This class handles:
 * - WebView initialization and configuration
 * - Loading the local server URL (http://127.0.0.1:8000)
 * - Loading screen management during page loads
 * - WebView state persistence (URL, scroll position)
 * - Cache management
 * 
 * The WebViewManager is used by MainActivity to provide a seamless web interface
 * experience within the native Android app.
 * 
 * Requirements: 4.1, 4.2
 * 
 * @param webView The WebView instance to manage
 * @param context The application context for accessing resources
 */
class WebViewManager(
    private val webView: WebView,
    private val context: Context
) {
    
    companion object {
        private const val COMPONENT_NAME = "WebViewManager"
        private const val STATE_KEY_URL = "webview_url"
        private const val STATE_KEY_SCROLL_Y = "webview_scroll_y"
        
        // Viewport configuration for mobile rendering
        private const val VIEWPORT_META_TAG = """
            var meta = document.createElement('meta');
            meta.name = 'viewport';
            meta.content = 'width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no';
            var head = document.getElementsByTagName('head')[0];
            if (head) {
                head.appendChild(meta);
            }
        """
    }
    
    // Callback interface for error handling
    interface ErrorCallback {
        fun onPageLoadError(errorCode: Int, description: String, failingUrl: String)
        fun onConnectionError()
    }
    
    // Callback interface for loading state
    interface LoadingCallback {
        fun onPageStarted(url: String)
        fun onPageFinished(url: String)
    }
    
    // Callback interface for pull-to-refresh
    interface RefreshCallback {
        fun onRefreshRequested()
    }
    
    // Callback interface for sync operations (Task 10.2)
    interface SyncCallback {
        fun onSyncRequested()
    }
    
    private var errorCallback: ErrorCallback? = null
    private var loadingCallback: LoadingCallback? = null
    private var refreshCallback: RefreshCallback? = null
    private var syncCallback: SyncCallback? = null

    // Per-launch API capability token handed to the frontend via the JS
    // bridge; the frontend sends it as X-API-Token on every API request.
    @Volatile
    private var apiToken: String = ""
    
    // Keyboard visibility tracking
    private var isKeyboardVisible = false
    private var keyboardVisibilityListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    
    /**
     * Set the error callback for handling page load errors
     */
    fun setErrorCallback(callback: ErrorCallback) {
        this.errorCallback = callback
        Logger.logInfo(COMPONENT_NAME, "Error callback registered")
    }
    
    /**
     * Set the loading callback for handling page load events
     */
    fun setLoadingCallback(callback: LoadingCallback) {
        this.loadingCallback = callback
        Logger.logInfo(COMPONENT_NAME, "Loading callback registered")
    }
    
    /**
     * Set the refresh callback for handling pull-to-refresh events
     */
    fun setRefreshCallback(callback: RefreshCallback) {
        this.refreshCallback = callback
        Logger.logInfo(COMPONENT_NAME, "Refresh callback registered")
    }
    
    /**
     * Set the sync callback for handling sync operations from WebView.
     *
     * Task 10.2 Implementation:
     * - Register callback for sync requests from JavaScript
     * - Log registration for diagnostics
     *
     * Requirements: 6.1, 6.2, 11.1
     */
    fun setSyncCallback(callback: SyncCallback) {
        this.syncCallback = callback
        Logger.logInfo(COMPONENT_NAME, "Sync callback registered")
    }

    /**
     * Provide the per-launch API capability token that the frontend must
     * send as the X-API-Token header. Must be set before [loadApp].
     * Only content served from the local loopback server can reach the JS
     * bridge (external URLs open in the system browser), so the token is
     * not exposed to third-party pages.
     */
    fun setApiToken(token: String) {
        this.apiToken = token
        Logger.logInfo(COMPONENT_NAME, "API token configured for JS bridge")
    }
    
    /**
     * Initialize the WebView with basic configuration.
     * 
     * This method sets up the WebView for basic operation. Additional configuration
     * such as JavaScript enablement, DOM storage, and security settings will be
     * implemented in Task 7.2.
     * 
     * Implementation (Task 7.1):
     * - Log initialization
     * - Prepare WebView for configuration (Task 7.2)
     * 
     * Requirements: 4.1
     */
    fun initialize() {
        Logger.logInfo(COMPONENT_NAME, "Initializing WebView")
        
        try {
            // Get WebView settings
            val settings = webView.settings
            
            // Enable JavaScript execution (required for web app functionality)
            settings.javaScriptEnabled = true
            Logger.logInfo(COMPONENT_NAME, "JavaScript enabled")
            
            // Enable DOM storage for web app state persistence
            settings.domStorageEnabled = true
            Logger.logInfo(COMPONENT_NAME, "DOM storage enabled")

            // Never cache HTTP responses: the app is served from localhost, so
            // caching gains nothing but serves stale JS/CSS after app updates
            // (Chaquopy-extracted files can keep unchanged Last-Modified stamps).
            settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            Logger.logInfo(COMPONENT_NAME, "HTTP cache disabled (LOAD_NO_CACHE)")
            
            // Disable file access for security (prevent unauthorized file access)
            settings.allowFileAccess = false
            Logger.logInfo(COMPONENT_NAME, "File access disabled")
            
            settings.allowContentAccess = false
            Logger.logInfo(COMPONENT_NAME, "Content access disabled")
            
            // Set mixed content mode to HTTPS only (requires API 21+)
            // This prevents loading HTTP content in HTTPS pages
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                Logger.logInfo(COMPONENT_NAME, "Mixed content mode set to HTTPS only")
            } else {
                Logger.logWarning(COMPONENT_NAME, "Mixed content mode not available on API < 21")
            }
            
            // Configure custom user agent to identify the Android app
            val originalUserAgent = settings.userAgentString
            val customUserAgent = "$originalUserAgent FinTrackAndroid/1.0"
            settings.userAgentString = customUserAgent
            Logger.logInfo(COMPONENT_NAME, "User agent configured: $customUserAgent")
            
            // Task 7.4: Configure responsive layout settings
            configureResponsiveLayout()
            
            // Task 7.3: Set up custom WebViewClient for page load events and error handling
            setupWebViewClient()
            
            // Task 10.2: Set up JavaScript bridge for sync operations
            setupJavaScriptBridge()
            
            Logger.logInfo(COMPONENT_NAME, "WebView initialized successfully")
        } catch (e: Exception) {
            Logger.logError(COMPONENT_NAME, "Failed to initialize WebView", e)
            throw e
        }
    }
    
    /**
     * Configure responsive layout settings for mobile rendering.
     * 
     * This method sets up the WebView for optimal mobile display including:
     * - Viewport configuration for proper scaling
     * - Layout algorithm for mobile-friendly rendering
     * - Zoom controls configuration
     * 
     * Implementation (Task 7.4):
     * - Configure viewport settings
     * - Set layout algorithm
     * - Configure zoom controls
     * - Set up keyboard visibility listener
     * 
     * Requirements: 4.4
     */
    private fun configureResponsiveLayout() {
        Logger.logInfo(COMPONENT_NAME, "Configuring responsive layout")
        
        try {
            val settings = webView.settings
            
            // Enable viewport meta tag support
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            Logger.logInfo(COMPONENT_NAME, "Viewport support enabled")
            
            // Set layout algorithm for mobile-friendly rendering
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
                settings.layoutAlgorithm = android.webkit.WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
                Logger.logInfo(COMPONENT_NAME, "Layout algorithm set to TEXT_AUTOSIZING")
            } else {
                @Suppress("DEPRECATION")
                settings.layoutAlgorithm = android.webkit.WebSettings.LayoutAlgorithm.NORMAL
                Logger.logInfo(COMPONENT_NAME, "Layout algorithm set to NORMAL (API < 19)")
            }
            
            // Disable built-in zoom controls (we'll use viewport meta tag instead)
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.setSupportZoom(false)
            Logger.logInfo(COMPONENT_NAME, "Zoom controls disabled")
            
            // Set up keyboard visibility listener
            setupKeyboardVisibilityListener()
            
            Logger.logInfo(COMPONENT_NAME, "Responsive layout configured successfully")
        } catch (e: Exception) {
            Logger.logError(COMPONENT_NAME, "Failed to configure responsive layout", e)
        }
    }
    
    /**
     * Set up keyboard visibility listener to handle input focus.
     * 
     * This method monitors layout changes to detect when the soft keyboard
     * is shown or hidden, allowing the WebView to adjust its layout accordingly.
     * 
     * Implementation (Task 7.4):
     * - Create OnGlobalLayoutListener
     * - Calculate visible height changes
     * - Detect keyboard visibility
     * - Log keyboard state changes
     * 
     * Requirements: 4.4
     */
    private fun setupKeyboardVisibilityListener() {
        Logger.logInfo(COMPONENT_NAME, "Setting up keyboard visibility listener")
        
        try {
            // Remove existing listener if any
            keyboardVisibilityListener?.let {
                webView.viewTreeObserver.removeOnGlobalLayoutListener(it)
            }
            
            // Create new listener
            keyboardVisibilityListener = ViewTreeObserver.OnGlobalLayoutListener {
                val rect = android.graphics.Rect()
                webView.getWindowVisibleDisplayFrame(rect)
                
                val screenHeight = webView.rootView.height
                val keypadHeight = screenHeight - rect.bottom
                
                // If more than 15% of the screen is covered, keyboard is visible
                val isKeyboardNowVisible = keypadHeight > screenHeight * 0.15
                
                if (isKeyboardNowVisible != isKeyboardVisible) {
                    isKeyboardVisible = isKeyboardNowVisible
                    
                    if (isKeyboardVisible) {
                        Logger.logInfo(COMPONENT_NAME, "Keyboard shown (height: $keypadHeight px)")
                        onKeyboardShown(keypadHeight)
                    } else {
                        Logger.logInfo(COMPONENT_NAME, "Keyboard hidden")
                        onKeyboardHidden()
                    }
                }
            }
            
            // Attach listener to view tree observer
            webView.viewTreeObserver.addOnGlobalLayoutListener(keyboardVisibilityListener)
            Logger.logInfo(COMPONENT_NAME, "Keyboard visibility listener attached")
            
        } catch (e: Exception) {
            Logger.logError(COMPONENT_NAME, "Failed to set up keyboard visibility listener", e)
        }
    }
    
    /**
     * Called when the soft keyboard is shown.
     * 
     * This method adjusts the WebView layout to ensure input fields remain visible
     * when the keyboard is displayed.
     * 
     * @param keyboardHeight The height of the keyboard in pixels
     * 
     * Requirements: 4.4
     */
    private fun onKeyboardShown(keyboardHeight: Int) {
        Logger.logDebug(COMPONENT_NAME, "Handling keyboard shown event")
        
        try {
            // The WebView will automatically adjust its layout when the keyboard appears
            // due to the windowSoftInputMode setting in AndroidManifest.xml
            // We just need to ensure the focused element is visible
            
            // Request layout to ensure proper adjustment
            webView.requestLayout()
            
            Logger.logDebug(COMPONENT_NAME, "WebView layout adjusted for keyboard")
        } catch (e: Exception) {
            Logger.logError(COMPONENT_NAME, "Failed to handle keyboard shown event", e)
        }
    }
    
    /**
     * Called when the soft keyboard is hidden.
     * 
     * This method restores the WebView layout to its normal state after the
     * keyboard is dismissed.
     * 
     * Requirements: 4.4
     */
    private fun onKeyboardHidden() {
        Logger.logDebug(COMPONENT_NAME, "Handling keyboard hidden event")
        
        try {
            // Request layout to restore normal view
            webView.requestLayout()
            
            Logger.logDebug(COMPONENT_NAME, "WebView layout restored after keyboard hidden")
        } catch (e: Exception) {
            Logger.logError(COMPONENT_NAME, "Failed to handle keyboard hidden event", e)
        }
    }
    
    /**
     * Handle configuration changes such as screen orientation.
     * 
     * This method is called when the device configuration changes (e.g., rotation)
     * to ensure the WebView adapts properly to the new layout.
     * 
     * Implementation (Task 7.4):
     * - Log configuration change
     * - Adjust WebView layout if needed
     * - Reload page if necessary (based on configuration)
     * 
     * @param newConfig The new device configuration
     * 
     * Requirements: 4.4
     */
    fun handleConfigurationChange(newConfig: Configuration) {
        Logger.logInfo(COMPONENT_NAME, "Handling configuration change")
        
        try {
            // Log orientation change
            val orientation = when (newConfig.orientation) {
                Configuration.ORIENTATION_PORTRAIT -> "Portrait"
                Configuration.ORIENTATION_LANDSCAPE -> "Landscape"
                else -> "Undefined"
            }
            Logger.logInfo(COMPONENT_NAME, "New orientation: $orientation")
            
            // Log screen size change
            val screenSize = when (newConfig.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK) {
                Configuration.SCREENLAYOUT_SIZE_SMALL -> "Small"
                Configuration.SCREENLAYOUT_SIZE_NORMAL -> "Normal"
                Configuration.SCREENLAYOUT_SIZE_LARGE -> "Large"
                Configuration.SCREENLAYOUT_SIZE_XLARGE -> "XLarge"
                else -> "Undefined"
            }
            Logger.logInfo(COMPONENT_NAME, "Screen size: $screenSize")
            
            // Request layout to adjust to new configuration
            webView.requestLayout()
            
            // The WebView will automatically handle the configuration change
            // No need to reload the page as the web app should be responsive
            Logger.logInfo(COMPONENT_NAME, "Configuration change handled successfully")
            
        } catch (e: Exception) {
            Logger.logError(COMPONENT_NAME, "Failed to handle configuration change", e)
        }
    }
    
    /**
     * Enable pull-to-refresh gesture for reloading the page.
     * 
     * Note: This method is a placeholder. The actual pull-to-refresh functionality
     * should be implemented using SwipeRefreshLayout in the activity layout.
     * This method enables the callback mechanism for refresh events.
     * 
     * Implementation (Task 7.4):
     * - Enable refresh callback
     * - Log enablement
     * 
     * Requirements: 4.4
     */
    fun enablePullToRefresh() {
        Logger.logInfo(COMPONENT_NAME, "Pull-to-refresh enabled")
        
        // The actual pull-to-refresh gesture is handled by SwipeRefreshLayout
        // in the activity layout. This method just logs the enablement.
        // The refresh action will be triggered via the refreshCallback.
    }
    
    /**
     * Disable pull-to-refresh gesture.
     * 
     * Note: This method is a placeholder. The actual pull-to-refresh functionality
     * should be implemented using SwipeRefreshLayout in the activity layout.
     * This method disables the callback mechanism for refresh events.
     * 
     * Implementation (Task 7.4):
     * - Disable refresh callback
     * - Log disablement
     * 
     * Requirements: 4.4
     */
    fun disablePullToRefresh() {
        Logger.logInfo(COMPONENT_NAME, "Pull-to-refresh disabled")
        
        // The actual pull-to-refresh gesture is handled by SwipeRefreshLayout
        // in the activity layout. This method just logs the disablement.
    }
    
    /**
     * Reload the current page.
     * 
     * This method is called when the user triggers a refresh action
     * (e.g., via pull-to-refresh gesture).
     * 
     * Implementation (Task 7.4):
     * - Reload WebView
     * - Log reload action
     * 
     * Requirements: 4.4
     */
    fun reload() {
        Logger.logInfo(COMPONENT_NAME, "Reloading page")
        
        try {
            webView.reload()
            Logger.logInfo(COMPONENT_NAME, "Page reload initiated")
        } catch (e: Exception) {
            Logger.logError(COMPONENT_NAME, "Failed to reload page", e)
        }
    }
    
    /**
     * Set up custom WebViewClient to handle page load events and errors.
     * 
     * Implementation (Task 7.3):
     * - Create custom WebViewClient inner class
     * - Override onPageStarted() to show loading screen
     * - Override onPageFinished() to hide loading screen
     * - Override onReceivedError() to handle connection errors
     * - Override shouldOverrideUrlLoading() to handle navigation
     * 
     * Requirements: 4.2, 4.5
     */
    private fun setupWebViewClient() {
        Logger.logInfo(COMPONENT_NAME, "Setting up WebViewClient")

        // WebChromeClient handles JS dialogs (alert, confirm, prompt)
        webView.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onJsConfirm(
                view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?
            ): Boolean {
                val builder = android.app.AlertDialog.Builder(context)
                builder.setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result?.confirm() }
                    .setNegativeButton("Cancel") { _, _ -> result?.cancel() }
                    .setOnCancelListener { result?.cancel() }
                    .create()
                    .show()
                return true
            }

            override fun onJsAlert(
                view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?
            ): Boolean {
                val builder = android.app.AlertDialog.Builder(context)
                builder.setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result?.confirm() }
                    .setOnCancelListener { result?.confirm() }
                    .create()
                    .show()
                return true
            }
        }

        webView.webViewClient = object : android.webkit.WebViewClient() {
            
            /**
             * Called when a page starts loading.
             * Shows the loading screen and notifies the callback.
             */
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                Logger.logInfo(COMPONENT_NAME, "Page started loading: $url")
                
                // Show loading screen
                showLoadingScreen()
                
                // Notify callback
                url?.let { loadingCallback?.onPageStarted(it) }
            }
            
            /**
             * Called when a page finishes loading.
             * Hides the loading screen and notifies the callback.
             * Also injects viewport meta tag for mobile rendering.
             */
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Logger.logInfo(COMPONENT_NAME, "Page finished loading: $url")
                
                // Inject viewport meta tag for mobile rendering (Task 7.4)
                view?.evaluateJavascript(VIEWPORT_META_TAG) { result ->
                    Logger.logInfo(COMPONENT_NAME, "Viewport meta tag injected")
                }
                
                // Hide loading screen
                hideLoadingScreen()
                
                // Notify callback
                url?.let { loadingCallback?.onPageFinished(it) }
            }
            
            /**
             * Called when a page load error occurs.
             * Handles connection errors and other page load failures.
             */
            override fun onReceivedError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    val errorCode = error?.errorCode ?: -1
                    val description = error?.description?.toString() ?: "Unknown error"
                    val failingUrl = request?.url?.toString() ?: "Unknown URL"
                    
                    Logger.logError(
                        COMPONENT_NAME,
                        "Page load error - Code: $errorCode, Description: $description, URL: $failingUrl"
                    )
                    
                    // Only handle errors for the main frame (not for resources like images)
                    if (request?.isForMainFrame == true) {
                        // Check if it's a connection error
                        if (isConnectionError(errorCode)) {
                            Logger.logError(COMPONENT_NAME, "Connection error detected")
                            errorCallback?.onConnectionError()
                        } else {
                            errorCallback?.onPageLoadError(errorCode, description, failingUrl)
                        }
                    }
                } else {
                    // For older Android versions, just log the error
                    Logger.logError(COMPONENT_NAME, "Page load error (API < 23)")
                    errorCallback?.onConnectionError()
                }
            }
            
            /**
             * Called when a page load error occurs (deprecated method for API < 23).
             */
            @Suppress("DEPRECATION")
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                
                Logger.logError(
                    COMPONENT_NAME,
                    "Page load error (deprecated) - Code: $errorCode, Description: $description, URL: $failingUrl"
                )
                
                // Check if it's a connection error
                if (isConnectionError(errorCode)) {
                    Logger.logError(COMPONENT_NAME, "Connection error detected")
                    errorCallback?.onConnectionError()
                } else {
                    errorCallback?.onPageLoadError(
                        errorCode,
                        description ?: "Unknown error",
                        failingUrl ?: "Unknown URL"
                    )
                }
            }
            
            /**
             * Called when the WebView is about to load a URL.
             * Returns false to allow the WebView to handle the URL.
             */
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: android.webkit.WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                Logger.logInfo(COMPONENT_NAME, "shouldOverrideUrlLoading: $url")

                // Google blocks OAuth in WebViews (disallowed_useragent).
                // Open external URLs in the system browser instead.
                if (!url.startsWith("http://127.0.0.1") && !url.startsWith("http://localhost")) {
                    Logger.logInfo(COMPONENT_NAME, "Opening external URL in system browser: $url")
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    context.startActivity(intent)
                    return true // We handled it — don't load in WebView
                }

                return false // Let WebView handle localhost URLs
            }

            /**
             * Called when the WebView is about to load a URL (deprecated method for API < 24).
             */
            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                Logger.logInfo(COMPONENT_NAME, "shouldOverrideUrlLoading (deprecated): $url")
                if (url != null && !url.startsWith("http://127.0.0.1") && !url.startsWith("http://localhost")) {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    context.startActivity(intent)
                    return true
                }
                return false
            }
        }
        
        Logger.logInfo(COMPONENT_NAME, "WebViewClient configured successfully")
    }
    
    /**
     * Check if the error code represents a connection error.
     * 
     * @param errorCode The WebView error code
     * @return true if it's a connection error, false otherwise
     */
    private fun isConnectionError(errorCode: Int): Boolean {
        return when (errorCode) {
            android.webkit.WebViewClient.ERROR_HOST_LOOKUP -> true
            android.webkit.WebViewClient.ERROR_CONNECT -> true
            android.webkit.WebViewClient.ERROR_TIMEOUT -> true
            android.webkit.WebViewClient.ERROR_FAILED_SSL_HANDSHAKE -> true
            android.webkit.WebViewClient.ERROR_BAD_URL -> true
            else -> false
        }
    }
    
    /**
     * Load the FinTrack application from the local server.
     * 
     * This method loads the web interface from the embedded FastAPI server
     * running on localhost. The server URL is constructed using constants
     * defined in the Constants object.
     * 
     * Implementation (Task 7.1):
     * - Construct server URL from constants
     * - Load URL in WebView
     * - Log loading action
     * 
     * @param serverUrl The server URL to load (defaults to http://127.0.0.1:8000)
     * 
     * Requirements: 4.2
     */
    fun loadApp(
        // ?boot= hands the per-process API token to the frontend; api.js
        // stores it in sessionStorage, strips it from the URL, and attaches
        // it as X-FinTrack-Token on every /api/* request.
        serverUrl: String = "http://${Constants.SERVER_HOST}:${Constants.SERVER_PORT}/?boot=" +
            com.fintrack.pk.server.ServerProcessManager.apiToken
    ) {
        Logger.logInfo(COMPONENT_NAME, "Loading app from: $serverUrl")
        
        try {
            webView.loadUrl(serverUrl)
            Logger.logInfo(COMPONENT_NAME, "App loading initiated")
            
        } catch (e: Exception) {
            Logger.logError(COMPONENT_NAME, "Failed to load app", e)
            throw e
        }
    }
    
    /**
     * Show the loading screen while the WebView is loading content.
     * 
     * This method is called by the WebViewClient when a page starts loading.
     * The actual UI update is handled by the callback (MainActivity).
     * 
     * Implementation (Task 7.3):
     * - Log action
     * - Notify loading callback (if set)
     * 
     * Requirements: 4.5
     */
    fun showLoadingScreen() {
        Logger.logInfo(COMPONENT_NAME, "Showing loading screen")
        
        // The actual UI update is handled by the callback (MainActivity)
        // This allows MainActivity to control the loading screen visibility
    }
    
    /**
     * Hide the loading screen after the WebView has finished loading.
     * 
     * This method is called by the WebViewClient when a page finishes loading.
     * The actual UI update is handled by the callback (MainActivity).
     * 
     * Implementation (Task 7.3):
     * - Log action
     * - Notify loading callback (if set)
     * 
     * Requirements: 4.5
     */
    fun hideLoadingScreen() {
        Logger.logInfo(COMPONENT_NAME, "Hiding loading screen")
        
        // The actual UI update is handled by the callback (MainActivity)
        // This allows MainActivity to control the loading screen visibility
    }
    
    /**
     * Save the current WebView state for restoration after process death.
     * 
     * This method captures the current URL and scroll position so that the
     * user's browsing state can be restored when the app is recreated.
     * 
     * Implementation (Task 7.1):
     * - Get current URL from WebView
     * - Get current scroll position
     * - Store in Bundle for persistence
     * - Log saved state
     * 
     * @return Bundle containing the WebView state
     * 
     * Requirements: 12.4
     */
    fun saveState(): Bundle {
        Logger.logInfo(COMPONENT_NAME, "Saving WebView state")
        
        val state = Bundle()
        
        try {
            // Save current URL
            val currentUrl = webView.url
            if (currentUrl != null) {
                state.putString(STATE_KEY_URL, currentUrl)
                Logger.logInfo(COMPONENT_NAME, "Saved URL: $currentUrl")
            } else {
                Logger.logWarning(COMPONENT_NAME, "No URL to save")
            }
            
            // Save scroll position
            val scrollY = webView.scrollY
            state.putInt(STATE_KEY_SCROLL_Y, scrollY)
            Logger.logInfo(COMPONENT_NAME, "Saved scroll position: $scrollY")
            
            Logger.logInfo(COMPONENT_NAME, "WebView state saved successfully")
            
        } catch (e: Exception) {
            Logger.logError(COMPONENT_NAME, "Failed to save WebView state", e)
        }
        
        return state
    }
    
    /**
     * Restore the WebView state from a previously saved Bundle.
     * 
     * This method restores the URL and scroll position that were saved
     * before the app was terminated or recreated.
     * 
     * Implementation (Task 7.1):
     * - Extract URL from Bundle
     * - Extract scroll position from Bundle
     * - Load URL if available
     * - Restore scroll position after page load
     * - Log restored state
     * 
     * @param state Bundle containing the saved WebView state
     * 
     * Requirements: 12.4
     */
    fun restoreState(state: Bundle) {
        Logger.logInfo(COMPONENT_NAME, "Restoring WebView state")
        
        try {
            // Restore URL
            val savedUrl = state.getString(STATE_KEY_URL)
            if (savedUrl != null) {
                Logger.logInfo(COMPONENT_NAME, "Restoring URL: $savedUrl")
                webView.loadUrl(savedUrl)
            } else {
                Logger.logWarning(COMPONENT_NAME, "No saved URL to restore")
            }
            
            // Restore scroll position
            val scrollY = state.getInt(STATE_KEY_SCROLL_Y, 0)
            if (scrollY > 0) {
                Logger.logInfo(COMPONENT_NAME, "Restoring scroll position: $scrollY")
                // Note: Scroll position should be restored after page load completes
                // This will be properly implemented in Task 7.3 with WebViewClient
                webView.postDelayed({
                    webView.scrollTo(0, scrollY)
                    Logger.logInfo(COMPONENT_NAME, "Scroll position restored")
                }, 500) // Delay to allow page to load
            }
            
            Logger.logInfo(COMPONENT_NAME, "WebView state restored successfully")
            
        } catch (e: Exception) {
            Logger.logError(COMPONENT_NAME, "Failed to restore WebView state", e)
        }
    }
    
    /**
     * Clear the WebView cache to free memory.
     * 
     * This method is called during low memory situations to help prevent
     * the app from being terminated by the system.
     * 
     * Implementation (Task 7.1):
     * - Clear WebView cache
     * - Clear form data
     * - Log cache clearing
     * 
     * Requirements: 12.3
     */
    fun clearCache() {
        Logger.logInfo(COMPONENT_NAME, "Clearing WebView cache")
        
        try {
            // Clear cache (including disk files)
            webView.clearCache(true)
            
            // Clear form data
            webView.clearFormData()
            
            // Clear history
            webView.clearHistory()
            
            Logger.logInfo(COMPONENT_NAME, "WebView cache cleared successfully")
            
        } catch (e: Exception) {
            Logger.logError(COMPONENT_NAME, "Failed to clear WebView cache", e)
        }
    }
    
    /**
     * Get the current URL loaded in the WebView.
     * 
     * This method is used for state management and diagnostics.
     * 
     * Implementation (Task 7.1):
     * - Return current URL from WebView
     * - Handle null case
     * 
     * @return The current URL, or null if no page is loaded
     */
    fun getCurrentUrl(): String? {
        val url = webView.url
        Logger.logDebug(COMPONENT_NAME, "Current URL: ${url ?: "none"}")
        return url
    }
    
    /**
     * Get the current vertical scroll position of the WebView.
     * 
     * This method is used for state management to preserve scroll position
     * across app lifecycle events.
     * 
     * Implementation (Task 7.1):
     * - Return current scroll Y position
     * 
     * @return The vertical scroll position in pixels
     */
    fun getScrollY(): Int {
        val scrollY = webView.scrollY
        Logger.logDebug(COMPONENT_NAME, "Current scroll Y: $scrollY")
        return scrollY
    }
    
    /**
     * Set up JavaScript bridge for sync operations.
     * 
     * This method adds a JavaScript interface that allows the WebView frontend
     * to trigger sync operations through the native Android layer.
     * 
     * Task 10.2 Implementation:
     * - Create JavaScript interface for sync operations
     * - Add interface to WebView with name "AndroidBridge"
     * - Log setup for diagnostics
     * 
     * Requirements: 6.1, 6.2, 11.1
     */
    private fun setupJavaScriptBridge() {
        Logger.logInfo(COMPONENT_NAME, "Setting up JavaScript bridge")
        
        try {
            // Create JavaScript interface
            val jsBridge = object : Any() {
                @android.webkit.JavascriptInterface
                fun requestSync() {
                    Logger.logInfo(COMPONENT_NAME, "Sync requested from JavaScript")
                    syncCallback?.onSyncRequested()
                }

                @android.webkit.JavascriptInterface
                fun getApiToken(): String {
                    return this@WebViewManager.apiToken
                }
            }

            // Add JavaScript interface to WebView
            webView.addJavascriptInterface(jsBridge, "AndroidBridge")

            Logger.logInfo(COMPONENT_NAME, "JavaScript bridge configured successfully")
            Logger.logInfo(COMPONENT_NAME, "JavaScript can call: AndroidBridge.requestSync(), AndroidBridge.getApiToken()")
            
        } catch (e: Exception) {
            Logger.logError(COMPONENT_NAME, "Failed to set up JavaScript bridge", e)
        }
    }
    
    /**
     * Clean up WebView resources.
     * 
     * This method should be called when the WebView is no longer needed
     * to properly release resources and prevent memory leaks.
     * 
     * Implementation (Task 7.1):
     * - Stop loading any pending requests
     * - Clear WebView
     * - Remove all views
     * - Destroy WebView
     * - Log cleanup
     * 
     * Requirements: 14.4
     */
    fun cleanup() {
        Logger.logInfo(COMPONENT_NAME, "Cleaning up WebView resources")
        
        try {
            // Remove keyboard visibility listener (Task 7.4)
            keyboardVisibilityListener?.let {
                webView.viewTreeObserver.removeOnGlobalLayoutListener(it)
                keyboardVisibilityListener = null
                Logger.logInfo(COMPONENT_NAME, "Keyboard visibility listener removed")
            }
            
            // Stop any pending loads
            webView.stopLoading()
            
            // Clear the WebView
            webView.clearHistory()
            webView.clearCache(true)
            
            // Remove all views
            webView.removeAllViews()
            
            // Destroy the WebView
            webView.destroy()
            
            Logger.logInfo(COMPONENT_NAME, "WebView resources cleaned up successfully")
            
        } catch (e: Exception) {
            Logger.logError(COMPONENT_NAME, "Failed to clean up WebView resources", e)
        }
    }
}
