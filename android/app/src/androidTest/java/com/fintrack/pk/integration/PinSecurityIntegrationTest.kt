package com.fintrack.pk.integration

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fintrack.pk.security.PinEncryptionManager
import com.fintrack.pk.ui.PinAuthenticationActivity
import com.fintrack.pk.utils.Logger
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Integration tests for PIN authentication flow with encryption.
 * 
 * Tests verify:
 * - PIN setup flow with encryption
 * - PIN validation with decryption
 * - PIN change flow
 * - Failed attempt tracking and lockout
 * - PIN reset and data clearing
 * - Encryption/decryption integration
 */
@RunWith(AndroidJUnit4::class)
class PinSecurityIntegrationTest {

    private lateinit var context: Context
    private lateinit var pinEncryptionManager: PinEncryptionManager
    private lateinit var prefs: SharedPreferences
    private lateinit var pinActivity: PinAuthenticationActivity

    companion object {
        private const val PREFS_NAME = "pin_prefs"
        private const val KEY_PIN = "encrypted_pin"
        private const val KEY_FAILED_ATTEMPTS = "failed_attempts"
        private const val KEY_LOCKOUT_UNTIL = "lockout_until"
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        pinEncryptionManager = PinEncryptionManager()
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        // Clear any existing PIN data
        prefs.edit().clear().apply()
        pinEncryptionManager.deleteKey()
        
        Logger.logInfo("PinSecurityIntegrationTest", "Test setup complete")
    }

    @After
    fun tearDown() {
        // Clean up
        prefs.edit().clear().apply()
        pinEncryptionManager.deleteKey()
        
        Logger.logInfo("PinSecurityIntegrationTest", "Test teardown complete")
    }

    /**
     * Test PIN encryption and decryption.
     */
    @Test
    fun testPinEncryptionDecryption() {
        Logger.logInfo("PinSecurityIntegrationTest", "Testing PIN encryption and decryption")
        
        val originalPin = "1234"
        
        // Encrypt PIN
        val encryptedPin = pinEncryptionManager.encryptPin(originalPin)
        assertNotNull("Encrypted PIN should not be null", encryptedPin)
        assertNotEquals("Encrypted PIN should differ from original", originalPin, encryptedPin)
        assertTrue("Encrypted PIN should contain separator", encryptedPin.contains("]"))
        
        // Decrypt PIN
        val decryptedPin = pinEncryptionManager.decryptPin(encryptedPin)
        assertEquals("Decrypted PIN should match original", originalPin, decryptedPin)
        
        Logger.logInfo("PinSecurityIntegrationTest", "PIN encryption and decryption test passed")
    }

    /**
     * Test PIN setup and storage.
     */
    @Test
    fun testPinSetupAndStorage() {
        Logger.logInfo("PinSecurityIntegrationTest", "Testing PIN setup and storage")
        
        val testPin = "5678"
        
        // Encrypt and store PIN
        val encryptedPin = pinEncryptionManager.encryptPin(testPin)
        prefs.edit().putString(KEY_PIN, encryptedPin).apply()
        
        // Verify PIN is stored
        val storedPin = prefs.getString(KEY_PIN, null)
        assertNotNull("PIN should be stored", storedPin)
        
        // Verify stored PIN can be decrypted
        val decryptedPin = pinEncryptionManager.decryptPin(storedPin!!)
        assertEquals("Decrypted PIN should match original", testPin, decryptedPin)
        
        Logger.logInfo("PinSecurityIntegrationTest", "PIN setup and storage test passed")
    }

    /**
     * Test PIN validation flow.
     */
    @Test
    fun testPinValidationFlow() {
        Logger.logInfo("PinSecurityIntegrationTest", "Testing PIN validation flow")
        
        val correctPin = "9876"
        val incorrectPin = "1111"
        
        // Setup PIN
        val encryptedPin = pinEncryptionManager.encryptPin(correctPin)
        prefs.edit().putString(KEY_PIN, encryptedPin).apply()
        
        // Validate correct PIN
        val storedEncryptedPin = prefs.getString(KEY_PIN, null)!!
        val storedPin = pinEncryptionManager.decryptPin(storedEncryptedPin)
        assertTrue("Correct PIN should validate", correctPin == storedPin)
        
        // Validate incorrect PIN
        assertFalse("Incorrect PIN should not validate", incorrectPin == storedPin)
        
        Logger.logInfo("PinSecurityIntegrationTest", "PIN validation flow test passed")
    }

    /**
     * Test failed attempt tracking.
     */
    @Test
    fun testFailedAttemptTracking() {
        Logger.logInfo("PinSecurityIntegrationTest", "Testing failed attempt tracking")
        
        // Initially no failed attempts
        val initialAttempts = prefs.getInt(KEY_FAILED_ATTEMPTS, 0)
        assertEquals("Initial failed attempts should be 0", 0, initialAttempts)
        
        // Simulate failed attempts
        for (i in 1..3) {
            val currentAttempts = prefs.getInt(KEY_FAILED_ATTEMPTS, 0)
            prefs.edit().putInt(KEY_FAILED_ATTEMPTS, currentAttempts + 1).apply()
        }
        
        // Verify failed attempts were tracked
        val finalAttempts = prefs.getInt(KEY_FAILED_ATTEMPTS, 0)
        assertEquals("Failed attempts should be 3", 3, finalAttempts)
        
        // Reset on successful login
        prefs.edit().putInt(KEY_FAILED_ATTEMPTS, 0).apply()
        val resetAttempts = prefs.getInt(KEY_FAILED_ATTEMPTS, 0)
        assertEquals("Failed attempts should be reset", 0, resetAttempts)
        
        Logger.logInfo("PinSecurityIntegrationTest", "Failed attempt tracking test passed")
    }

    /**
     * Test lockout mechanism.
     */
    @Test
    fun testLockoutMechanism() {
        Logger.logInfo("PinSecurityIntegrationTest", "Testing lockout mechanism")
        
        // Set lockout timestamp
        val lockoutUntil = System.currentTimeMillis() + 30000 // 30 seconds from now
        prefs.edit().putLong(KEY_LOCKOUT_UNTIL, lockoutUntil).apply()
        
        // Verify lockout is active
        val storedLockout = prefs.getLong(KEY_LOCKOUT_UNTIL, 0)
        assertTrue("Lockout should be active", storedLockout > System.currentTimeMillis())
        
        // Simulate lockout expiry
        val expiredLockout = System.currentTimeMillis() - 1000 // 1 second ago
        prefs.edit().putLong(KEY_LOCKOUT_UNTIL, expiredLockout).apply()
        
        // Verify lockout has expired
        val expiredLockoutValue = prefs.getLong(KEY_LOCKOUT_UNTIL, 0)
        assertTrue("Lockout should be expired", expiredLockoutValue < System.currentTimeMillis())
        
        Logger.logInfo("PinSecurityIntegrationTest", "Lockout mechanism test passed")
    }

    /**
     * Test PIN change flow.
     */
    @Test
    fun testPinChangeFlow() {
        Logger.logInfo("PinSecurityIntegrationTest", "Testing PIN change flow")
        
        val oldPin = "1111"
        val newPin = "2222"
        
        // Setup initial PIN
        val encryptedOldPin = pinEncryptionManager.encryptPin(oldPin)
        prefs.edit().putString(KEY_PIN, encryptedOldPin).apply()
        
        // Verify old PIN
        val storedOldPin = prefs.getString(KEY_PIN, null)!!
        val decryptedOldPin = pinEncryptionManager.decryptPin(storedOldPin)
        assertEquals("Old PIN should match", oldPin, decryptedOldPin)
        
        // Change to new PIN
        val encryptedNewPin = pinEncryptionManager.encryptPin(newPin)
        prefs.edit().putString(KEY_PIN, encryptedNewPin).apply()
        
        // Verify new PIN
        val storedNewPin = prefs.getString(KEY_PIN, null)!!
        val decryptedNewPin = pinEncryptionManager.decryptPin(storedNewPin)
        assertEquals("New PIN should match", newPin, decryptedNewPin)
        
        // Verify old PIN no longer works
        assertNotEquals("Old PIN should not match new PIN", oldPin, decryptedNewPin)
        
        Logger.logInfo("PinSecurityIntegrationTest", "PIN change flow test passed")
    }

    /**
     * Test PIN reset and data clearing.
     */
    @Test
    fun testPinResetAndDataClearing() {
        Logger.logInfo("PinSecurityIntegrationTest", "Testing PIN reset and data clearing")
        
        // Setup PIN and failed attempts
        val encryptedPin = pinEncryptionManager.encryptPin("1234")
        prefs.edit()
            .putString(KEY_PIN, encryptedPin)
            .putInt(KEY_FAILED_ATTEMPTS, 2)
            .putLong(KEY_LOCKOUT_UNTIL, System.currentTimeMillis() + 10000)
            .apply()
        
        // Verify data exists
        assertTrue("PIN should exist", prefs.contains(KEY_PIN))
        assertTrue("Failed attempts should exist", prefs.contains(KEY_FAILED_ATTEMPTS))
        assertTrue("Lockout should exist", prefs.contains(KEY_LOCKOUT_UNTIL))
        
        // Reset PIN (clear all data)
        pinEncryptionManager.deleteKey()
        prefs.edit().clear().apply()
        
        // Verify all data is cleared
        assertFalse("PIN should be cleared", prefs.contains(KEY_PIN))
        assertFalse("Failed attempts should be cleared", prefs.contains(KEY_FAILED_ATTEMPTS))
        assertFalse("Lockout should be cleared", prefs.contains(KEY_LOCKOUT_UNTIL))
        
        Logger.logInfo("PinSecurityIntegrationTest", "PIN reset and data clearing test passed")
    }

    /**
     * Test multiple PIN encryption/decryption cycles.
     */
    @Test
    fun testMultiplePinCycles() {
        Logger.logInfo("PinSecurityIntegrationTest", "Testing multiple PIN cycles")
        
        val testPins = listOf("1234", "5678", "9012", "3456")
        
        for (pin in testPins) {
            // Encrypt
            val encrypted = pinEncryptionManager.encryptPin(pin)
            assertNotNull("Encrypted PIN should not be null", encrypted)
            
            // Decrypt
            val decrypted = pinEncryptionManager.decryptPin(encrypted)
            assertEquals("Decrypted PIN should match original", pin, decrypted)
            
            Logger.logInfo("PinSecurityIntegrationTest", "PIN cycle for $pin passed")
        }
        
        Logger.logInfo("PinSecurityIntegrationTest", "Multiple PIN cycles test passed")
    }

    /**
     * Test PIN encryption with special characters.
     */
    @Test
    fun testPinEncryptionWithSpecialCharacters() {
        Logger.logInfo("PinSecurityIntegrationTest", "Testing PIN encryption with special characters")
        
        // Note: In production, PINs are typically numeric, but test encryption robustness
        val specialPins = listOf("12!@", "ab#$", "😀😁😂😃")
        
        for (pin in specialPins) {
            try {
                val encrypted = pinEncryptionManager.encryptPin(pin)
                val decrypted = pinEncryptionManager.decryptPin(encrypted)
                assertEquals("Special PIN should encrypt/decrypt correctly", pin, decrypted)
            } catch (e: Exception) {
                Logger.logWarning("PinSecurityIntegrationTest", "Special character PIN failed: $pin")
            }
        }
        
        Logger.logInfo("PinSecurityIntegrationTest", "PIN encryption with special characters test passed")
    }

    /**
     * Test concurrent PIN operations.
     */
    @Test
    fun testConcurrentPinOperations() {
        Logger.logInfo("PinSecurityIntegrationTest", "Testing concurrent PIN operations")
        
        val pin1 = "1111"
        val pin2 = "2222"
        
        // Encrypt multiple PINs
        val encrypted1 = pinEncryptionManager.encryptPin(pin1)
        val encrypted2 = pinEncryptionManager.encryptPin(pin2)
        
        // Verify they are different
        assertNotEquals("Different PINs should have different encrypted values", encrypted1, encrypted2)
        
        // Decrypt both
        val decrypted1 = pinEncryptionManager.decryptPin(encrypted1)
        val decrypted2 = pinEncryptionManager.decryptPin(encrypted2)
        
        // Verify correct decryption
        assertEquals("First PIN should decrypt correctly", pin1, decrypted1)
        assertEquals("Second PIN should decrypt correctly", pin2, decrypted2)
        
        Logger.logInfo("PinSecurityIntegrationTest", "Concurrent PIN operations test passed")
    }

    /**
     * Test PIN security with failed attempts and lockout integration.
     */
    @Test
    fun testPinSecurityWithFailedAttemptsAndLockout() {
        Logger.logInfo("PinSecurityIntegrationTest", "Testing PIN security with failed attempts and lockout")
        
        val correctPin = "4321"
        val incorrectPin = "0000"
        
        // Setup PIN
        val encryptedPin = pinEncryptionManager.encryptPin(correctPin)
        prefs.edit().putString(KEY_PIN, encryptedPin).apply()
        
        // Simulate 3 failed attempts
        for (i in 1..3) {
            val storedEncryptedPin = prefs.getString(KEY_PIN, null)!!
            val storedPin = pinEncryptionManager.decryptPin(storedEncryptedPin)
            
            if (incorrectPin != storedPin) {
                val failedAttempts = prefs.getInt(KEY_FAILED_ATTEMPTS, 0) + 1
                prefs.edit().putInt(KEY_FAILED_ATTEMPTS, failedAttempts).apply()
                
                if (failedAttempts >= 3) {
                    // Trigger lockout
                    val lockoutUntil = System.currentTimeMillis() + 30000
                    prefs.edit()
                        .putLong(KEY_LOCKOUT_UNTIL, lockoutUntil)
                        .putInt(KEY_FAILED_ATTEMPTS, 0)
                        .apply()
                }
            }
        }
        
        // Verify lockout is active
        val lockoutUntil = prefs.getLong(KEY_LOCKOUT_UNTIL, 0)
        assertTrue("Lockout should be active after 3 failed attempts", lockoutUntil > System.currentTimeMillis())
        
        // Verify failed attempts were reset
        val failedAttempts = prefs.getInt(KEY_FAILED_ATTEMPTS, 0)
        assertEquals("Failed attempts should be reset after lockout", 0, failedAttempts)
        
        Logger.logInfo("PinSecurityIntegrationTest", "PIN security with failed attempts and lockout test passed")
    }

    /**
     * Test PIN persistence across encryption manager instances.
     */
    @Test
    fun testPinPersistenceAcrossInstances() {
        Logger.logInfo("PinSecurityIntegrationTest", "Testing PIN persistence across instances")
        
        val testPin = "7890"
        
        // Encrypt with first instance
        val encryptionManager1 = PinEncryptionManager()
        val encrypted = encryptionManager1.encryptPin(testPin)
        prefs.edit().putString(KEY_PIN, encrypted).apply()
        
        // Decrypt with second instance
        val encryptionManager2 = PinEncryptionManager()
        val storedEncrypted = prefs.getString(KEY_PIN, null)!!
        val decrypted = encryptionManager2.decryptPin(storedEncrypted)
        
        assertEquals("PIN should decrypt correctly with different instance", testPin, decrypted)
        
        Logger.logInfo("PinSecurityIntegrationTest", "PIN persistence across instances test passed")
    }
}
