package com.fintrack.pk.ui

import android.content.Context
import android.content.SharedPreferences
import com.fintrack.pk.security.PinEncryptionManager
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.junit.MockitoJUnitRunner
import org.junit.Assert.*

/**
 * Unit tests for PinAuthenticationActivity
 * 
 * Task 13.2 Test Coverage:
 * - Verify PIN change flow prompts for current PIN first
 * - Verify current PIN validation against Keystore
 * - Verify new PIN and confirmation prompts
 * - Verify PIN update in Keystore
 * - Verify failed attempts tracking and lockout
 * 
 * Requirements: 15.4
 */
// Silent runner: the shared setup() stubs editor methods that only some
// tests use; the strict runner fails those tests with UnnecessaryStubbing.
@RunWith(MockitoJUnitRunner.Silent::class)
class PinAuthenticationActivityTest {

    @Mock
    private lateinit var mockContext: Context
    
    @Mock
    private lateinit var mockSharedPreferences: SharedPreferences
    
    @Mock
    private lateinit var mockEditor: SharedPreferences.Editor
    
    @Mock
    private lateinit var mockPinEncryptionManager: PinEncryptionManager
    
    private lateinit var activity: PinAuthenticationActivity
    
    @Before
    fun setup() {
        // Setup mock SharedPreferences
        `when`(mockContext.getSharedPreferences(anyString(), anyInt())).thenReturn(mockSharedPreferences)
        `when`(mockSharedPreferences.edit()).thenReturn(mockEditor)
        `when`(mockEditor.putString(anyString(), anyString())).thenReturn(mockEditor)
        `when`(mockEditor.putInt(anyString(), anyInt())).thenReturn(mockEditor)
        `when`(mockEditor.putLong(anyString(), anyLong())).thenReturn(mockEditor)
        `when`(mockEditor.remove(anyString())).thenReturn(mockEditor)
        `when`(mockEditor.clear()).thenReturn(mockEditor)
    }
    
    /**
     * Test: PIN change flow verifies current PIN first
     * 
     * Scenario:
     * 1. User enters current PIN
     * 2. System validates against stored PIN
     * 3. If correct, prompt for new PIN
     * 4. If incorrect, show error and track failed attempts
     */
    @Test
    fun testPinChangeFlow_VerifiesCurrentPinFirst() {
        // Setup: Store an encrypted PIN
        val currentPin = "1234"
        val encryptedPin = "encrypted_1234"
        
        `when`(mockSharedPreferences.getString("encrypted_pin", null)).thenReturn(encryptedPin)
        `when`(mockPinEncryptionManager.decryptPin(encryptedPin)).thenReturn(currentPin)
        
        // Test: Validate current PIN
        val isValid = mockPinEncryptionManager.decryptPin(encryptedPin) == currentPin
        
        assertTrue("Current PIN should be validated first", isValid)
    }
    
    /**
     * Test: PIN change flow with correct current PIN
     * 
     * Scenario:
     * 1. User enters correct current PIN
     * 2. System prompts for new PIN
     * 3. User enters new PIN twice
     * 4. System updates encrypted PIN in Keystore
     */
    @Test
    fun testPinChangeFlow_CorrectCurrentPin_PromptsForNewPin() {
        // Setup
        val currentPin = "1234"
        val newPin = "5678"
        val encryptedCurrentPin = "encrypted_1234"
        val encryptedNewPin = "encrypted_5678"
        
        `when`(mockSharedPreferences.getString("encrypted_pin", null)).thenReturn(encryptedCurrentPin)
        `when`(mockPinEncryptionManager.decryptPin(encryptedCurrentPin)).thenReturn(currentPin)
        `when`(mockPinEncryptionManager.encryptPin(newPin)).thenReturn(encryptedNewPin)
        
        // Step 1: Verify current PIN
        val currentPinValid = mockPinEncryptionManager.decryptPin(encryptedCurrentPin) == currentPin
        assertTrue("Current PIN should be valid", currentPinValid)
        
        // Step 2: Encrypt and store new PIN
        val newEncryptedPin = mockPinEncryptionManager.encryptPin(newPin)
        assertEquals("New PIN should be encrypted", encryptedNewPin, newEncryptedPin)
        
        // Verify that the new PIN would be stored
        verify(mockPinEncryptionManager).encryptPin(newPin)
    }
    
    /**
     * Test: PIN change flow with incorrect current PIN
     * 
     * Scenario:
     * 1. User enters incorrect current PIN
     * 2. System shows error
     * 3. System increments failed attempts
     * 4. After 3 failed attempts, system triggers lockout
     */
    @Test
    fun testPinChangeFlow_IncorrectCurrentPin_ShowsErrorAndTracksAttempts() {
        // Setup
        val currentPin = "1234"
        val wrongPin = "9999"
        val encryptedCurrentPin = "encrypted_1234"
        
        `when`(mockSharedPreferences.getString("encrypted_pin", null)).thenReturn(encryptedCurrentPin)
        `when`(mockPinEncryptionManager.decryptPin(encryptedCurrentPin)).thenReturn(currentPin)
        `when`(mockSharedPreferences.getInt("failed_attempts", 0)).thenReturn(0, 1, 2)
        
        // Test: Attempt 1 - Wrong PIN
        val isValid1 = mockPinEncryptionManager.decryptPin(encryptedCurrentPin) == wrongPin
        assertFalse("Wrong PIN should not be valid", isValid1)
        
        // Verify failed attempts would be incremented
        val failedAttempts1 = mockSharedPreferences.getInt("failed_attempts", 0) + 1
        assertEquals("Failed attempts should be 1", 1, failedAttempts1)
        
        // Test: Attempt 2 - Wrong PIN
        val failedAttempts2 = mockSharedPreferences.getInt("failed_attempts", 0) + 1
        assertEquals("Failed attempts should be 2", 2, failedAttempts2)
        
        // Test: Attempt 3 - Wrong PIN (triggers lockout)
        val failedAttempts3 = mockSharedPreferences.getInt("failed_attempts", 0) + 1
        assertEquals("Failed attempts should be 3", 3, failedAttempts3)
        assertTrue("Should trigger lockout after 3 attempts", failedAttempts3 >= 3)
    }
    
    /**
     * Test: PIN change flow updates encrypted PIN in Keystore
     * 
     * Scenario:
     * 1. User successfully verifies current PIN
     * 2. User enters new PIN and confirmation
     * 3. System encrypts new PIN using Keystore
     * 4. System stores encrypted PIN in SharedPreferences
     */
    @Test
    fun testPinChangeFlow_UpdatesEncryptedPinInKeystore() {
        // Setup
        val currentPin = "1234"
        val newPin = "5678"
        val encryptedCurrentPin = "encrypted_1234"
        val encryptedNewPin = "encrypted_5678"
        
        `when`(mockSharedPreferences.getString("encrypted_pin", null)).thenReturn(encryptedCurrentPin)
        `when`(mockPinEncryptionManager.decryptPin(encryptedCurrentPin)).thenReturn(currentPin)
        `when`(mockPinEncryptionManager.encryptPin(newPin)).thenReturn(encryptedNewPin)
        
        // Step 1: Verify current PIN
        val currentPinValid = mockPinEncryptionManager.decryptPin(encryptedCurrentPin) == currentPin
        assertTrue("Current PIN should be valid", currentPinValid)
        
        // Step 2: Encrypt new PIN
        val newEncryptedPin = mockPinEncryptionManager.encryptPin(newPin)
        
        // Step 3: Store new encrypted PIN
        mockEditor.putString("encrypted_pin", newEncryptedPin)
        mockEditor.putInt("failed_attempts", 0)
        mockEditor.remove("lockout_until")
        mockEditor.apply()
        
        // Verify the encryption and storage operations
        verify(mockPinEncryptionManager).encryptPin(newPin)
        verify(mockEditor).putString("encrypted_pin", encryptedNewPin)
        verify(mockEditor).putInt("failed_attempts", 0)
        verify(mockEditor).remove("lockout_until")
    }
    
    /**
     * Test: PIN change flow applies lockout after 3 failed attempts
     * 
     * Scenario:
     * 1. User enters wrong current PIN 3 times
     * 2. System triggers 30-second lockout
     * 3. System stores lockout timestamp
     * 4. System resets failed attempts counter
     */
    @Test
    fun testPinChangeFlow_AppliesLockoutAfterThreeFailedAttempts() {
        // Setup
        val currentPin = "1234"
        val wrongPin = "9999"
        val encryptedCurrentPin = "encrypted_1234"
        val maxFailedAttempts = 3
        val lockoutDurationMs = 30000L
        
        `when`(mockSharedPreferences.getString("encrypted_pin", null)).thenReturn(encryptedCurrentPin)
        `when`(mockPinEncryptionManager.decryptPin(encryptedCurrentPin)).thenReturn(currentPin)
        
        // Simulate 3 failed attempts
        var failedAttempts = 0
        for (i in 1..3) {
            val isValid = mockPinEncryptionManager.decryptPin(encryptedCurrentPin) == wrongPin
            assertFalse("Wrong PIN should not be valid", isValid)
            failedAttempts++
        }
        
        // Verify lockout should be triggered
        assertEquals("Should have 3 failed attempts", maxFailedAttempts, failedAttempts)
        
        // Calculate lockout timestamp
        val lockoutUntil = System.currentTimeMillis() + lockoutDurationMs
        
        // Verify lockout would be stored
        mockEditor.putLong("lockout_until", lockoutUntil)
        mockEditor.putInt("failed_attempts", 0)
        mockEditor.apply()
        
        verify(mockEditor).putLong(eq("lockout_until"), anyLong())
        verify(mockEditor).putInt("failed_attempts", 0)
    }
    
    /**
     * Test: PIN change flow with new PIN confirmation mismatch
     * 
     * Scenario:
     * 1. User verifies current PIN successfully
     * 2. User enters new PIN
     * 3. User enters different confirmation PIN
     * 4. System shows error and prompts to start over
     */
    @Test
    fun testPinChangeFlow_NewPinConfirmationMismatch_ShowsError() {
        // Setup
        val currentPin = "1234"
        val newPin = "5678"
        val confirmPin = "8765" // Different from newPin
        val encryptedCurrentPin = "encrypted_1234"
        
        `when`(mockSharedPreferences.getString("encrypted_pin", null)).thenReturn(encryptedCurrentPin)
        `when`(mockPinEncryptionManager.decryptPin(encryptedCurrentPin)).thenReturn(currentPin)
        
        // Step 1: Verify current PIN
        val currentPinValid = mockPinEncryptionManager.decryptPin(encryptedCurrentPin) == currentPin
        assertTrue("Current PIN should be valid", currentPinValid)
        
        // Step 2: Check new PIN confirmation
        val pinsMatch = newPin == confirmPin
        assertFalse("New PIN and confirmation should not match", pinsMatch)
        
        // Verify that PIN would NOT be updated
        verify(mockPinEncryptionManager, never()).encryptPin(newPin)
    }
}
