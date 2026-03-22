package com.fintrack.pk.utils

import android.content.Context
import android.content.SharedPreferences
import com.fintrack.pk.FinTrackApplication
import java.io.File
import java.io.FileOutputStream

/**
 * Manages Python runtime extraction and initialization
 * Handles first-launch detection and setup of Python environment
 * 
 * Requirements: 3.4
 */
class PythonRuntimeManager(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "python_runtime_prefs"
        private const val KEY_RUNTIME_EXTRACTED = "runtime_extracted"
        private const val KEY_EXTRACTION_VERSION = "extraction_version"
        private const val CURRENT_VERSION = 1 // Increment when assets change
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Check if this is the first launch of the app
     * @return true if Python runtime needs to be extracted
     */
    fun isFirstLaunch(): Boolean {
        val extracted = prefs.getBoolean(KEY_RUNTIME_EXTRACTED, false)
        val version = prefs.getInt(KEY_EXTRACTION_VERSION, 0)
        
        // Need to extract if never extracted or version changed
        return !extracted || version < CURRENT_VERSION
    }

    /**
     * Extract Python runtime and configuration files from APK assets to app-private storage
     * This includes:
     * - Configuration files (credentials.json template)
     * - Web frontend assets (index.html)
     * - Setting up directory structure
     * 
     * @return true if extraction was successful, false otherwise
     */
    fun extractRuntime(): Boolean {
        try {
            Logger.logInfo("PythonRuntimeManager", "Starting Python runtime extraction")

            // Create necessary directories
            val success = createDirectories()
            if (!success) {
                Logger.logError("PythonRuntimeManager", "Failed to create directories")
                return false
            }

            // Extract configuration files
            extractConfigFiles()

            // Extract web frontend
            extractWebFrontend()

            // Mark extraction as complete
            prefs.edit()
                .putBoolean(KEY_RUNTIME_EXTRACTED, true)
                .putInt(KEY_EXTRACTION_VERSION, CURRENT_VERSION)
                .apply()

            Logger.logInfo("PythonRuntimeManager", "Python runtime extraction completed successfully")
            return true

        } catch (e: Exception) {
            Logger.logError("PythonRuntimeManager", "Failed to extract Python runtime", e)
            return false
        }
    }

    /**
     * Create necessary directories in app-private storage
     * @return true if successful
     */
    private fun createDirectories(): Boolean {
        try {
            val dirs = listOf(
                context.filesDir.resolve("python"),
                context.filesDir.resolve("config"),
                context.filesDir.resolve("logs"),
                context.filesDir.resolve("web")
            )

            for (dir in dirs) {
                if (!dir.exists()) {
                    val created = dir.mkdirs()
                    if (created) {
                        Logger.logInfo("PythonRuntimeManager", "Created directory: ${dir.absolutePath}")
                    } else {
                        Logger.logError("PythonRuntimeManager", "Failed to create directory: ${dir.absolutePath}")
                        return false
                    }
                }
            }

            return true
        } catch (e: Exception) {
            Logger.logError("PythonRuntimeManager", "Error creating directories", e)
            return false
        }
    }

    /**
     * Extract configuration files from assets to config directory
     */
    private fun extractConfigFiles() {
        try {
            val configDir = context.filesDir.resolve("config")

            // Always overwrite credentials.json from assets to pick up updates on app upgrade.
            // The real credentials.json is bundled as an asset (not the .example placeholder).
            val credentialsFile = configDir.resolve("credentials.json")
            try {
                copyAssetToFile("credentials.json", credentialsFile)
                Logger.logInfo("PythonRuntimeManager", "Copied credentials.json to config directory")
            } catch (e: Exception) {
                // Fallback to example if real credentials not bundled
                if (!credentialsFile.exists()) {
                    copyAssetToFile("credentials.json.example", credentialsFile)
                    Logger.logInfo("PythonRuntimeManager", "Copied credentials.json.example (fallback)")
                }
            }

        } catch (e: Exception) {
            Logger.logError("PythonRuntimeManager", "Error extracting config files", e)
            throw e
        }
    }

    /**
     * Extract web frontend files from assets
     */
    private fun extractWebFrontend() {
        try {
            val webDir = context.filesDir.resolve("web")
            val indexFile = webDir.resolve("index.html")

            // Always update index.html to ensure latest version
            copyAssetToFile("index.html", indexFile)
            Logger.logInfo("PythonRuntimeManager", "Extracted index.html to web directory")

        } catch (e: Exception) {
            Logger.logError("PythonRuntimeManager", "Error extracting web frontend", e)
            throw e
        }
    }

    /**
     * Copy a file from assets to a destination file
     * @param assetPath Path to the asset file
     * @param destFile Destination file
     */
    private fun copyAssetToFile(assetPath: String, destFile: File) {
        context.assets.open(assetPath).use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        }
    }

    /**
     * Verify that the Python runtime extraction was successful
     * Checks for existence of required files and directories
     * 
     * @return true if verification passed, false otherwise
     */
    fun verifyExtraction(): Boolean {
        try {
            Logger.logInfo("PythonRuntimeManager", "Verifying Python runtime extraction")

            // Check directories exist
            val requiredDirs = listOf(
                context.filesDir.resolve("python"),
                context.filesDir.resolve("config"),
                context.filesDir.resolve("logs"),
                context.filesDir.resolve("web")
            )

            for (dir in requiredDirs) {
                if (!dir.exists() || !dir.isDirectory) {
                    Logger.logError("PythonRuntimeManager", "Required directory missing: ${dir.absolutePath}")
                    return false
                }
            }

            // Check web frontend exists
            val indexFile = context.filesDir.resolve("web/index.html")
            if (!indexFile.exists()) {
                Logger.logError("PythonRuntimeManager", "Web frontend file missing: index.html")
                return false
            }

            // Check config directory has credentials file (even if it's the example)
            val configDir = context.filesDir.resolve("config")
            val credentialsFile = configDir.resolve("credentials.json")
            if (!credentialsFile.exists()) {
                Logger.logError("PythonRuntimeManager", "Configuration file missing: credentials.json")
                return false
            }

            Logger.logInfo("PythonRuntimeManager", "Python runtime verification successful")
            return true

        } catch (e: Exception) {
            Logger.logError("PythonRuntimeManager", "Error verifying extraction", e)
            return false
        }
    }

    /**
     * Get the path to the Python runtime directory
     * @return File object pointing to the Python directory
     */
    fun getPythonDirectory(): File {
        return context.filesDir.resolve("python")
    }

    /**
     * Get the path to the config directory
     * @return File object pointing to the config directory
     */
    fun getConfigDirectory(): File {
        return context.filesDir.resolve("config")
    }

    /**
     * Get the path to the logs directory
     * @return File object pointing to the logs directory
     */
    fun getLogsDirectory(): File {
        return context.filesDir.resolve("logs")
    }

    /**
     * Get the path to the web directory
     * @return File object pointing to the web directory
     */
    fun getWebDirectory(): File {
        return context.filesDir.resolve("web")
    }

    /**
     * Reset the extraction state (for testing or troubleshooting)
     * This will force re-extraction on next app launch
     */
    fun resetExtractionState() {
        prefs.edit()
            .putBoolean(KEY_RUNTIME_EXTRACTED, false)
            .putInt(KEY_EXTRACTION_VERSION, 0)
            .apply()
        Logger.logInfo("PythonRuntimeManager", "Extraction state reset")
    }
}
