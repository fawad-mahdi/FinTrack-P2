package com.fintrack.pk.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ShareCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.fintrack.pk.BuildConfig
import com.fintrack.pk.R
import com.fintrack.pk.utils.Logger
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Activity for app settings and configuration
 *
 * Task 9.2 Implementation:
 * - Add "Connect Gmail" button to initiate OAuth flow
 * - Display Gmail connection status
 *
 * Task 9.4 Implementation:
 * - Add "Disconnect Gmail" button to delete token.json
 * - Clear OAuth state on disconnect
 * - Update UI to show appropriate buttons based on connection status
 *
 * Task 13.1 Implementation:
 * - Security section: Change PIN button
 * - Gmail section: Connection status and Connect/Disconnect buttons
 * - About section: App version and View Logs button
 *
 * Task 12 (MD3 redesign):
 * - Card-based layout with Gmail chip status indicator
 *
 * Requirements: 6.1, 6.4, 6.5, 15.1, 15.4, 15.5
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var gmailStatusChip: Chip
    private lateinit var connectGmailButton: MaterialButton
    private lateinit var disconnectGmailButton: MaterialButton
    private lateinit var changePinButton: MaterialButton
    private lateinit var appVersionText: TextView
    private lateinit var viewLogsButton: MaterialButton
    private lateinit var oauthTokenManager: com.fintrack.pk.utils.OAuthTokenManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_settings)

        val settingsRoot = findViewById<android.view.View>(R.id.settingsRoot)
        ViewCompat.setOnApplyWindowInsetsListener(settingsRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        Logger.logInfo("SettingsActivity", "Activity created")

        // Initialize OAuthTokenManager
        oauthTokenManager = com.fintrack.pk.utils.OAuthTokenManager(this)

        // Initialize views
        initializeViews()

        // Update Gmail connection status
        updateGmailStatus(oauthTokenManager.hasToken())
    }

    override fun onResume() {
        super.onResume()
        // Update status when returning from OAuth flow
        updateGmailStatus(oauthTokenManager.hasToken())
    }

    /**
     * Initialize UI views
     *
     * Task 9.4 Enhancement:
     * - Add disconnect button initialization
     * - Set up click listeners for both connect and disconnect
     *
     * Task 13.1 Enhancement:
     * - Initialize Security section views (Change PIN button)
     * - Initialize About section views (App version, View Logs button)
     */
    private fun initializeViews() {
        // Gmail section
        gmailStatusChip = findViewById(R.id.gmailStatusChip)
        connectGmailButton = findViewById(R.id.connectGmailButton)
        disconnectGmailButton = findViewById(R.id.disconnectGmailButton)

        // Security section
        changePinButton = findViewById(R.id.changePinButton)

        // About section
        appVersionText = findViewById(R.id.appVersionText)
        viewLogsButton = findViewById(R.id.viewLogsButton)

        // Set up Connect Gmail button
        connectGmailButton.setOnClickListener {
            Logger.logInfo("SettingsActivity", "Connect Gmail button clicked")
            initiateOAuthFlow()
        }

        // Task 9.4: Set up Disconnect Gmail button
        disconnectGmailButton.setOnClickListener {
            Logger.logInfo("SettingsActivity", "Disconnect Gmail button clicked")
            disconnectGmail()
        }

        // Task 13.1: Set up Change PIN button
        changePinButton.setOnClickListener {
            Logger.logInfo("SettingsActivity", "Change PIN button clicked")
            startChangePinFlow()
        }

        // Task 13.1: Set up View Logs button
        viewLogsButton.setOnClickListener {
            Logger.logInfo("SettingsActivity", "View Logs button clicked")
            showLogs()
        }

        // Task 13.1: Display app version
        appVersionText.text = getString(
            R.string.settings_version,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE
        )
    }

    /**
     * Update Gmail connection status display using the chip indicator.
     *
     * Task 12 (MD3 redesign):
     * - Use Chip widget with icon to show connected/disconnected state
     */
    private fun updateGmailStatus(isConnected: Boolean) {
        if (isConnected) {
            Logger.logInfo("SettingsActivity", "Gmail is connected (token exists)")
            gmailStatusChip.text = getString(R.string.settings_gmail_status_connected)
            gmailStatusChip.chipIcon = ContextCompat.getDrawable(this, R.drawable.ic_check_circle)
            gmailStatusChip.setChipIconTintResource(R.color.md_theme_primary)
            connectGmailButton.text = getString(R.string.settings_gmail_reconnect)
            disconnectGmailButton.visibility = View.VISIBLE
        } else {
            Logger.logInfo("SettingsActivity", "Gmail is not connected (no token)")
            gmailStatusChip.text = getString(R.string.settings_gmail_status_disconnected)
            gmailStatusChip.chipIcon = ContextCompat.getDrawable(this, R.drawable.ic_cancel)
            gmailStatusChip.setChipIconTintResource(R.color.md_theme_error)
            connectGmailButton.text = getString(R.string.settings_gmail_connect)
            disconnectGmailButton.visibility = View.GONE
        }
    }

    /**
     * Initiate OAuth flow by calling MainActivity's method
     *
     * Task 9.2 Implementation:
     * - Get MainActivity instance and call initiateOAuthFlow()
     * - If MainActivity is not available, show error
     */
    private fun initiateOAuthFlow() {
        try {
            // Start MainActivity if not already running and call OAuth flow
            val intent = Intent(this, MainActivity::class.java)
            intent.putExtra("initiate_oauth", true)
            intent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            startActivity(intent)

            Logger.logInfo("SettingsActivity", "Launching MainActivity to initiate OAuth flow")
        } catch (e: Exception) {
            Logger.logError("SettingsActivity", "Failed to initiate OAuth flow: ${e.message}")
        }
    }

    /**
     * Disconnect Gmail by deleting token.json and clearing OAuth state.
     *
     * Task 9.4 Implementation:
     * - Show confirmation dialog before disconnecting
     * - Delete token.json using OAuthTokenManager
     * - Update UI to show "Connect Gmail" button
     * - Show success message
     *
     * Requirements: 6.4
     */
    private fun disconnectGmail() {
        // Show confirmation dialog
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.gmail_disconnect_title))
            .setMessage(getString(R.string.gmail_disconnect_message))
            .setPositiveButton(getString(R.string.gmail_disconnect_confirm)) { _, _ ->
                Logger.logInfo("SettingsActivity", "User confirmed Gmail disconnect")

                // Delete token using OAuthTokenManager
                val deleted = oauthTokenManager.deleteToken()

                if (deleted) {
                    Logger.logInfo("SettingsActivity", "Gmail disconnected successfully")
                    android.widget.Toast.makeText(
                        this,
                        getString(R.string.gmail_disconnect_success),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()

                    // Update UI
                    updateGmailStatus(false)
                } else {
                    Logger.logError("SettingsActivity", "Failed to disconnect Gmail")
                    android.widget.Toast.makeText(
                        this,
                        getString(R.string.gmail_disconnect_failed),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                Logger.logInfo("SettingsActivity", "User cancelled Gmail disconnect")
                dialog.dismiss()
            }
            .show()
    }

    /**
     * Start the Change PIN flow.
     *
     * Task 13.1 Implementation:
     * - Use MODE_CHANGE which verifies current PIN first
     * - Then prompts for new PIN and confirmation
     * - Updates encrypted PIN in Keystore
     *
     * Requirements: 15.4
     */
    private fun startChangePinFlow() {
        Logger.logInfo("SettingsActivity", "Starting Change PIN flow")

        // Launch PIN authentication activity in change mode
        val intent = PinAuthenticationActivity.createChangeIntent(this)
        startActivityForResult(intent, REQUEST_CODE_CHANGE_PIN)
    }

    /**
     * Handle result from PIN change
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CODE_CHANGE_PIN) {
            if (resultCode == RESULT_OK) {
                Logger.logInfo("SettingsActivity", "PIN changed successfully")
                android.widget.Toast.makeText(
                    this,
                    "PIN changed successfully",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            } else {
                Logger.logInfo("SettingsActivity", "PIN change cancelled or failed")
            }
        }
    }

    /**
     * Show logs in a scrollable dialog.
     *
     * Task 13.1 Implementation:
     * - Display server_logs.txt in a logcat-style view
     * - Show both server and app logs
     * - Provide export option
     *
     * Requirements: 15.5
     */
    private fun showLogs() {
        startActivity(Intent(this, DebugActivity::class.java))
    }

    /**
     * Export logs using FileProvider + ShareCompat so the file is accessible to other apps.
     */
    private fun exportLogs() {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
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
            Logger.logError("SettingsActivity", "Failed to export logs: ${e.message}")
            android.widget.Toast.makeText(
                this,
                "Failed to export logs: ${e.message}",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    companion object {
        private const val REQUEST_CODE_CHANGE_PIN = 1001
    }
}
