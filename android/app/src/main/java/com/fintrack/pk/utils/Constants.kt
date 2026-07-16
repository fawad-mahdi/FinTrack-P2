package com.fintrack.pk.utils

/**
 * Application-wide constants
 */
object Constants {
    // Server configuration
    const val SERVER_HOST = "127.0.0.1"
    const val SERVER_PORT = 8000
    const val SERVER_HEALTH_CHECK_INTERVAL_MS = 5000L
    const val SERVER_STARTUP_TIMEOUT_MS = 30000L
    const val SERVER_MAX_RESTART_ATTEMPTS = 5
    const val SERVER_RESTART_WINDOW_MS = 60000L

    // PIN authentication
    const val PIN_LENGTH = 4
    const val PIN_MAX_ATTEMPTS = 3
    const val PIN_LOCKOUT_DURATION_SECONDS = 30
    const val PIN_TIMEOUT_MINUTES = 5L

    // Storage
    const val DATABASE_NAME = "fintrack.db"
    const val MAX_LOG_SIZE_MB = 10L
    const val PYTHON_DIR = "python"
    const val CONFIG_DIR = "config"
    const val LOGS_DIR = "logs"

    // File names
    const val CREDENTIALS_FILE = "credentials.json"
    const val TOKEN_FILE = "token.json"
    const val APP_CONFIG_FILE = "app_config.json"
    const val SERVER_LOG_FILE = "server_logs.txt"
    const val APP_LOG_FILE = "app_logs.txt"

    // SharedPreferences
    const val PREFS_PIN = "pin_prefs"
    const val PREFS_APP = "app_prefs"
    const val KEY_ENCRYPTED_PIN = "encrypted_pin"
    const val KEY_FAILED_ATTEMPTS = "failed_attempts"
    const val KEY_LOCKOUT_UNTIL = "lockout_until"
    const val KEY_FIRST_LAUNCH = "first_launch"
    const val KEY_PYTHON_EXTRACTED = "python_extracted"
    const val KEY_LAST_BACKGROUND_TIME = "last_background_time"

<<<<<<< HEAD
    // OAuth (Android-type Google client: no client secret ships with the app)
    const val OAUTH_AUTH_URI = "https://accounts.google.com/o/oauth2/v2/auth"
    const val OAUTH_TOKEN_URI = "https://oauth2.googleapis.com/token"
    const val OAUTH_SCOPE = "https://www.googleapis.com/auth/gmail.readonly"

    // Client ID is injected at build time from gradle properties (see build.gradle.kts).
    val OAUTH_CLIENT_ID: String
        get() = com.fintrack.pk.BuildConfig.OAUTH_CLIENT_ID

    // Google requires the reversed-client-ID custom scheme for Android clients.
    // Must stay in sync with the appAuthRedirectScheme manifest placeholder.
    val OAUTH_REDIRECT_URI: String
        get() {
            val id = OAUTH_CLIENT_ID
            val scheme = if (id.endsWith(".apps.googleusercontent.com"))
                "com.googleusercontent.apps." + id.removeSuffix(".apps.googleusercontent.com")
            else
                "com.fintrack.pk"
            return "$scheme:/oauth2callback"
        }
=======
    // OAuth — the redirect scheme is variant-aware (dev builds use their own
    // scheme) and must stay in sync with the appAuthRedirectScheme Gradle
    // placeholder consumed by the manifest callback filter.
    val OAUTH_REDIRECT_SCHEME: String = com.fintrack.pk.BuildConfig.OAUTH_REDIRECT_SCHEME
    const val OAUTH_REDIRECT_HOST = "oauth2callback"
    val OAUTH_REDIRECT_URI: String = "$OAUTH_REDIRECT_SCHEME://$OAUTH_REDIRECT_HOST"
>>>>>>> cf5955ab49e83f742b37cfff8091bf38565c16bf

    // Network
    const val NETWORK_TIMEOUT_SECONDS = 30L

    // WebView
    const val WEBVIEW_USER_AGENT_SUFFIX = "FinTrackPK-Android"
}
