package com.fintrack.pk.data

/**
 * Data class representing app configuration settings
 */
data class AppSettings(
    val monitoredEmails: List<String> = emptyList(),
    val autoSyncOnLaunch: Boolean = false,
    val pinTimeoutMinutes: Long = 5L
) {
    companion object {
        fun default() = AppSettings()
    }
}
