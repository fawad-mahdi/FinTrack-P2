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

    // OAuth
    const val OAUTH_REDIRECT_SCHEME = "com.fintrack.pk"
    const val OAUTH_REDIRECT_HOST = "oauth2callback"
    const val OAUTH_REDIRECT_URI = "$OAUTH_REDIRECT_SCHEME://$OAUTH_REDIRECT_HOST"

    // Network
    const val NETWORK_TIMEOUT_SECONDS = 30L

    // WebView
    const val WEBVIEW_USER_AGENT_SUFFIX = "FinTrackPK-Android"
}
