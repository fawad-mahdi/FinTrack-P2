package com.fintrack.pk.data

/**
 * Data class representing server configuration
 */
data class ServerConfig(
    val pythonPath: String,
    val serverPort: Int = 8000,
    val serverHost: String = "127.0.0.1",
    val logLevel: String = "info",
    val maxLogSizeMB: Long = 10L
) {
    val serverUrl: String
        get() = "http://$serverHost:$serverPort"

    val healthCheckUrl: String
        get() = "$serverUrl/health"
}
