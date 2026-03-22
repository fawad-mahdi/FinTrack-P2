package com.fintrack.pk.data

/**
 * Data class representing the status of the Python FastAPI server
 */
data class ServerStatus(
    val isRunning: Boolean,
    val port: Int = 8000,
    val uptime: Long = 0L,
    val lastError: String? = null,
    val healthCheckUrl: String = "http://127.0.0.1:$port/health"
) {
    companion object {
        fun stopped(error: String? = null) = ServerStatus(
            isRunning = false,
            lastError = error
        )

        fun running(uptime: Long = 0L) = ServerStatus(
            isRunning = true,
            uptime = uptime
        )
    }
}
