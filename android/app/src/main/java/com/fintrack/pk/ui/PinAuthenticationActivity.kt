package com.fintrack.pk.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.fintrack.pk.R
import com.fintrack.pk.security.PinEncryptionManager
import com.fintrack.pk.utils.Logger
import com.google.android.material.button.MaterialButton
import java.io.File

/**
 * Activity for PIN authentication
 * Handles PIN setup, login, validation, and reset
 * 
 * Requirements: 5.1, 5.2
 */
class PinAuthenticationActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_SETUP = "setup"
        const val MODE_LOGIN = "login"
        const val MODE_CHANGE = "change"
        
        const val RESULT_SUCCESS = Activity.RESULT_OK
        const val RESULT_CANCELLED = Activity.RESULT_CANCELED
        
        private const val PREFS_NAME = "pin_prefs"
        private const val KEY_PIN = "encrypted_pin"
        private const val KEY_FAILED_ATTEMPTS = "failed_attempts"
        private const val KEY_LOCKOUT_UNTIL = "lockout_until"
        
        private const val MAX_FAILED_ATTEMPTS = 3
        private const val LOCKOUT_DURATION_MS = 30000L // 30 seconds
        
        fun createSetupIntent(context: Context): Intent {
            return Intent(context, PinAuthenticationActivity::class.java).apply {
                putExtra(EXTRA_MODE, MODE_SETUP)
            }
        }
        
        fun createLoginIntent(context: Context): Intent {
            return Intent(context, PinAuthenticationActivity::class.java).apply {
                putExtra(EXTRA_MODE, MODE_LOGIN)
            }
        }
        
        fun createChangeIntent(context: Context): Intent {
            return Intent(context, PinAuthenticationActivity::class.java).apply {
                putExtra(EXTRA_MODE, MODE_CHANGE)
            }
        }
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var mode: String
    private lateinit var pinEncryptionManager: PinEncryptionManager
    
    // UI elements
    private lateinit var pinTitle: TextView
    private lateinit var pinSubtitle: TextView
    private lateinit var pinErrorText: TextView
    private lateinit var pinDot1: ImageView
    private lateinit var pinDot2: ImageView
    private lateinit var pinDot3: ImageView
    private lateinit var pinDot4: ImageView
    private lateinit var pinDotsLayout: LinearLayout
    private lateinit var numberPad: GridLayout
    private lateinit var resetPinButton: MaterialButton
    
    // State
    private var currentPin = StringBuilder()
    private var firstPin: String? = null
    private var lockoutTimer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_pin_authentication)

        val pinRoot = findViewById<android.view.View>(R.id.pinRoot)
        ViewCompat.setOnApplyWindowInsetsListener(pinRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_LOGIN
        pinEncryptionManager = PinEncryptionManager()
        
        Logger.logInfo("PinAuthenticationActivity", "Activity created in mode: $mode")
        
        initializeViews()
        setupNumberPad()
        updateUIForMode()
        checkLockout()
    }
    
    private fun initializeViews() {
        pinTitle = findViewById(R.id.pinTitle)
        pinSubtitle = findViewById(R.id.pinSubtitle)
        pinErrorText = findViewById(R.id.pinErrorText)
        pinDot1 = findViewById(R.id.pinDot1)
        pinDot2 = findViewById(R.id.pinDot2)
        pinDot3 = findViewById(R.id.pinDot3)
        pinDot4 = findViewById(R.id.pinDot4)
        pinDotsLayout = findViewById(R.id.pinDotsLayout)
        numberPad = findViewById(R.id.numberPad)
        resetPinButton = findViewById(R.id.resetPinButton)
        
        resetPinButton.setOnClickListener {
            showResetPinDialog()
        }
    }
    
    private fun setupNumberPad() {
        numberPad.removeAllViews()
        
        // Create buttons 1-9, 0, and backspace
        val buttons = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "⌫")
        
        // Calculate button size based on screen width
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val buttonSize = (screenWidth - 128) / 3 // 3 columns with padding
        
        buttons.forEachIndexed { index, label ->
            val button = MaterialButton(
                android.view.ContextThemeWrapper(this, com.google.android.material.R.style.Widget_Material3_Button_TonalButton),
                null,
                0
            ).apply {
                text = label
                textSize = 24f
                minimumHeight = 0
                minHeight = 0
                minimumWidth = 0
                minWidth = 0
                insetTop = 0
                insetBottom = 0
                layoutParams = GridLayout.LayoutParams().apply {
                    width = buttonSize
                    height = buttonSize
                    rowSpec = GridLayout.spec(index / 3)
                    columnSpec = GridLayout.spec(index % 3)
                    setMargins(8, 8, 8, 8)
                }
                if (label.isEmpty()) {
                    visibility = View.INVISIBLE
                    isClickable = false
                    isFocusable = false
                } else if (label == "⌫") {
                    setOnClickListener { onBackspace() }
                } else {
                    setOnClickListener { onNumberPressed(label) }
                }
            }
            numberPad.addView(button)
        }
        
        Logger.logInfo("PinAuthenticationActivity", "Number pad setup complete with ${buttons.size} buttons in 4x3 grid")
    }
    
    private fun updateUIForMode() {
        when (mode) {
            MODE_SETUP -> {
                pinTitle.text = getString(R.string.pin_setup_title)
                pinSubtitle.text = getString(R.string.pin_setup_subtitle)
                resetPinButton.visibility = View.GONE
            }
            MODE_LOGIN -> {
                pinTitle.text = getString(R.string.pin_login_title)
                pinSubtitle.text = getString(R.string.pin_login_subtitle)
                resetPinButton.visibility = View.VISIBLE
            }
            MODE_CHANGE -> {
                pinTitle.text = getString(R.string.pin_setup_title)
                pinSubtitle.text = getString(R.string.pin_setup_subtitle)
                resetPinButton.visibility = View.GONE
            }
        }
    }
    
    private fun checkLockout() {
        val lockoutUntil = prefs.getLong(KEY_LOCKOUT_UNTIL, 0)
        val now = System.currentTimeMillis()
        
        if (lockoutUntil > now) {
            startLockoutTimer(lockoutUntil - now)
        }
    }
    
    private fun startLockoutTimer(durationMs: Long) {
        numberPad.isEnabled = false
        setNumberPadEnabled(false)
        
        lockoutTimer?.cancel()
        lockoutTimer = object : CountDownTimer(durationMs, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val secondsRemaining = (millisUntilFinished / 1000).toInt()
                showError(getString(R.string.pin_locked_out, secondsRemaining))
            }
            
            override fun onFinish() {
                setNumberPadEnabled(true)
                hideError()
                prefs.edit().remove(KEY_LOCKOUT_UNTIL).apply()
            }
        }.start()
    }
    
    private fun setNumberPadEnabled(enabled: Boolean) {
        for (i in 0 until numberPad.childCount) {
            numberPad.getChildAt(i).isEnabled = enabled
        }
    }
    
    private fun onNumberPressed(number: String) {
        if (currentPin.length < 4) {
            currentPin.append(number)
            updatePinDots()
            
            if (currentPin.length == 4) {
                onPinEntered(currentPin.toString())
            }
        }
    }
    
    private fun onBackspace() {
        if (currentPin.isNotEmpty()) {
            currentPin.deleteCharAt(currentPin.length - 1)
            updatePinDots()
            hideError()
        }
    }
    
    private fun updatePinDots() {
        val dots = listOf(pinDot1, pinDot2, pinDot3, pinDot4)
        dots.forEachIndexed { index, dot ->
            if (index < currentPin.length) {
                dot.setImageResource(R.drawable.pin_dot_filled)
                animateDotFill(dot)
            } else {
                dot.setImageResource(R.drawable.pin_dot_empty)
            }
        }
    }

    private fun animateDotFill(view: View) {
        val scaleX = ObjectAnimator.ofFloat(view, "scaleX", 0.8f, 1.0f)
        val scaleY = ObjectAnimator.ofFloat(view, "scaleY", 0.8f, 1.0f)
        AnimatorSet().apply {
            playTogether(scaleX, scaleY)
            duration = 150
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun shakeDotsOnError() {
        val shake = android.view.animation.TranslateAnimation(-12f, 12f, 0f, 0f).apply {
            duration = 50
            repeatCount = 4
            repeatMode = android.view.animation.Animation.REVERSE
        }
        pinDotsLayout.startAnimation(shake)
    }
    
    /**
     * Called when a 4-digit PIN has been entered
     * Requirements: 5.1, 5.2
     */
    fun onPinEntered(pin: String): Boolean {
        Logger.logInfo("PinAuthenticationActivity", "PIN entered in mode: $mode")
        
        return when (mode) {
            MODE_SETUP -> handleSetupPin(pin)
            MODE_LOGIN -> handleLoginPin(pin)
            MODE_CHANGE -> handleChangePin(pin)
            else -> false
        }
    }
    
    private fun handleSetupPin(pin: String): Boolean {
        if (firstPin == null) {
            // First entry - ask for confirmation
            firstPin = pin
            pinTitle.text = getString(R.string.pin_confirm_title)
            pinSubtitle.text = getString(R.string.pin_confirm_subtitle)
            currentPin.clear()
            updatePinDots()
            return false
        } else {
            // Confirmation entry
            if (pin == firstPin) {
                setupNewPin(pin)
                return true
            } else {
                showError(getString(R.string.pin_error_mismatch))
                firstPin = null
                currentPin.clear()
                updatePinDots()
                updateUIForMode()
                return false
            }
        }
    }
    
    private fun handleLoginPin(pin: String): Boolean {
        if (validatePin(pin)) {
            // Reset failed attempts
            prefs.edit()
                .putInt(KEY_FAILED_ATTEMPTS, 0)
                .remove(KEY_LOCKOUT_UNTIL)
                .apply()
            
            Logger.logInfo("PinAuthenticationActivity", "PIN validation successful")
            setResult(RESULT_SUCCESS)
            finish()
            return true
        } else {
            val failedAttempts = prefs.getInt(KEY_FAILED_ATTEMPTS, 0) + 1
            prefs.edit().putInt(KEY_FAILED_ATTEMPTS, failedAttempts).apply()
            
            Logger.logInfo("PinAuthenticationActivity", "PIN validation failed (attempt $failedAttempts)")
            
            if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
                // Trigger lockout
                val lockoutUntil = System.currentTimeMillis() + LOCKOUT_DURATION_MS
                prefs.edit()
                    .putLong(KEY_LOCKOUT_UNTIL, lockoutUntil)
                    .putInt(KEY_FAILED_ATTEMPTS, 0)
                    .apply()
                
                startLockoutTimer(LOCKOUT_DURATION_MS)
            } else {
                showError(getString(R.string.pin_error_invalid))
            }
            
            currentPin.clear()
            updatePinDots()
            return false
        }
    }
    
    /**
     * Handle PIN change flow
     * Task 13.1 Implementation:
     * - First verify current PIN
     * - Then prompt for new PIN and confirmation
     * Requirements: 15.4
     */
    private var changePinVerified = false
    
    private fun handleChangePin(pin: String): Boolean {
        if (!changePinVerified) {
            // First step: verify current PIN
            if (validatePin(pin)) {
                Logger.logInfo("PinAuthenticationActivity", "Current PIN verified, prompting for new PIN")
                changePinVerified = true
                pinTitle.text = getString(R.string.pin_change_title)
                pinSubtitle.text = getString(R.string.pin_change_subtitle)
                currentPin.clear()
                updatePinDots()
                return false
            } else {
                val failedAttempts = prefs.getInt(KEY_FAILED_ATTEMPTS, 0) + 1
                prefs.edit().putInt(KEY_FAILED_ATTEMPTS, failedAttempts).apply()
                
                Logger.logInfo("PinAuthenticationActivity", "Current PIN verification failed (attempt $failedAttempts)")
                
                if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
                    // Trigger lockout
                    val lockoutUntil = System.currentTimeMillis() + LOCKOUT_DURATION_MS
                    prefs.edit()
                        .putLong(KEY_LOCKOUT_UNTIL, lockoutUntil)
                        .putInt(KEY_FAILED_ATTEMPTS, 0)
                        .apply()
                    
                    startLockoutTimer(LOCKOUT_DURATION_MS)
                } else {
                    showError(getString(R.string.pin_error_incorrect))
                }
                
                currentPin.clear()
                updatePinDots()
                return false
            }
        } else {
            // Second step: set new PIN (with confirmation)
            return handleSetupPin(pin)
        }
    }
    
    /**
     * Setup a new PIN
     * Requirements: 5.1, 5.3
     */
    fun setupNewPin(pin: String) {
        Logger.logInfo("PinAuthenticationActivity", "Setting up new PIN")
        
        try {
            // Encrypt PIN using Android Keystore
            val encryptedPin = pinEncryptionManager.encryptPin(pin)
            
            // Store encrypted PIN
            prefs.edit()
                .putString(KEY_PIN, encryptedPin)
                .putInt(KEY_FAILED_ATTEMPTS, 0)
                .remove(KEY_LOCKOUT_UNTIL)
                .apply()
            
            setResult(RESULT_SUCCESS)
            finish()
        } catch (e: Exception) {
            Logger.logError("PinAuthenticationActivity", "Error setting up PIN", e)
            showError("Failed to setup PIN: ${e.message}")
        }
    }
    
    /**
     * Reset PIN and clear all data
     * Requirements: 5.5
     */
    fun resetPin() {
        Logger.logInfo("PinAuthenticationActivity", "Resetting PIN and clearing data")
        
        // Delete encryption key
        pinEncryptionManager.deleteKey()
        
        // Clear all app data
        clearAppData()
        
        // Clear PIN preferences
        prefs.edit().clear().apply()
        
        // Restart in setup mode
        val intent = createSetupIntent(this)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
    
    /**
     * Validate entered PIN against stored PIN
     * Requirements: 5.2, 5.3
     */
    fun validatePin(pin: String): Boolean {
        try {
            val encryptedPin = prefs.getString(KEY_PIN, null) ?: return false
            
            // Decrypt stored PIN using Android Keystore
            val storedPin = pinEncryptionManager.decryptPin(encryptedPin)
            
            return pin == storedPin
        } catch (e: Exception) {
            Logger.logError("PinAuthenticationActivity", "Error validating PIN", e)
            return false
        }
    }
    
    private fun showResetPinDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.pin_reset_title))
            .setMessage(getString(R.string.pin_reset_message))
            .setPositiveButton(getString(R.string.pin_reset_confirm)) { _, _ ->
                resetPin()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
    
    private fun clearAppData() {
        try {
            // Clear database
            val dbDir = File(filesDir, "databases")
            dbDir.listFiles()?.forEach { it.delete() }
            
            // Clear config files
            val configDir = File(filesDir, "config")
            configDir.listFiles()?.forEach { it.delete() }
            
            // Clear logs
            val logsDir = File(filesDir, "logs")
            logsDir.listFiles()?.forEach { it.delete() }
            
            Logger.logInfo("PinAuthenticationActivity", "App data cleared")
        } catch (e: Exception) {
            Logger.logError("PinAuthenticationActivity", "Error clearing app data", e)
        }
    }
    
    private fun showError(message: String) {
        pinErrorText.text = message
        pinErrorText.visibility = View.VISIBLE
        shakeDotsOnError()
    }
    
    private fun hideError() {
        pinErrorText.visibility = View.INVISIBLE
    }
    
    override fun onDestroy() {
        super.onDestroy()
        lockoutTimer?.cancel()
    }
}
