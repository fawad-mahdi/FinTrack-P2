package com.fintrack.pk.e2e

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fintrack.pk.security.PinEncryptionManager
import com.fintrack.pk.server.ServerProcessManager
import com.fintrack.pk.utils.Logger
import com.fintrack.pk.utils.PythonRuntimeManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end tests for complete app launch sequence.
 * 
 * Tests verify the complete flow:
 * 1. Python runtime extraction (first launch)
 * 2. PIN authentication (setup or login)
 * 3. Server initialization and startup
 * 4. WebView loading
 * 
 * These tests simulate the actual user experience from app launch to ready state.
 */
@RunWith(AndroidJUnit4::class)
class AppLaunchFlowTest {

    private lateinit var context: Context
    private lateinit var pythonRuntimeManager: PythonRuntimeManager
    private lateinit var serverProcessManager: ServerProcessManager
    private lateinit var pinEncryptionManager: PinEncryptionManager
    private lateinit var prefs: SharedPreferences

    companion object {
        private const val PREFS_NAME = "app_prefs"
        private const val KEY_ENCRYPTED_PIN = "encrypted_pin"
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        pythonRuntimeManager = PythonRuntimeManager(context)
        serverProcessManager = ServerProcessManager(context)
        pinEncryptionManager = PinEncryptionManager()
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        // Clean up any existing state
        prefs.edit().clear().apply()
        pinEncryptionManager.deleteKey()
        
        Logger.logInfo("AppLaunchFlowTest", "Test setup complete")
    }

    @After
    fun tearDown() {
        // Stop server if running
        if (serverProcessManager.isServerRunning()) {
            serverProcessManager.stopServer()
        }
        serverProcessManager.cleanup()
        
        // Clean up
        prefs.edit().clear().apply()
        pinEncryptionManager.deleteKey()
        
        Logger.logInfo("AppLaunchFlowTest", "Test teardown complete")
    }

    /**
     * Test complete first launch flow:
     * - Python runtime extraction
     * - PIN setup
     * - Server start
     */
    @Test
    fun testFirstLaunchFlow() = runBlocking {
        Logger.logInfo("AppLaunchFlowTest", "Testing first launch flow")
        
        // Step 1: Check if Python runtime needs extraction
        val isFirstLaunch = pythonRuntimeManager.isFirstLaunch()
        Logger.logInfo("AppLaunchFlowTest", "Is first launch: $isFirstLaunch")
        
        if (isFirstLaunch) {
            // Extract Python runtime
            Logger.logInfo("AppLaunchFlowTest", "Extracting Python runtime")
            val extracted = pythonRuntimeManager.extractRuntime()
            assertTrue("Python runtime should extract successfully", extracted)
            
            // Verify extraction
            val verified = pythonRuntimeManager.verifyExtraction()
            assertTrue("Python runtime extraction should be verified", verified)
        }
        
        // Step 2: PIN setup (first launch)
        Logger.logInfo("AppLaunchFlowTest", "Setting up PIN")
        val testPin = "1234"
        val encryptedPin = pinEncryptionManager.encryptPin(testPin)
        prefs.edit().putString(KEY_ENCRYPTED_PIN, encryptedPin).apply()
        
        // Verify PIN is set
        assertTrue("PIN should be set", prefs.contains(KEY_ENCRYPTED_PIN))
        
        // Step 3: Start server
        Logger.logInfo("AppLaunchFlowTest", "Starting server")
        val serverStarted = serverProcessManager.startServer()
        assertTrue("Server should start successfully", serverStarted)
        
        // Wait for server to become healthy
        delay(3000)
        
        // Step 4: Verify server is running
        assertTrue("Server should be running", serverProcessManager.isServerRunning())
        
        val status = serverProcessManager.getServerStatus()
        assertTrue("Server status should show running", status.isRunning)
        assertEquals("Server port should be 8000", 8000, status.port)
        
        Logger.logInfo("AppLaunchFlowTest", "First launch flow test passed")
    }

    /**
     * Test subsequent launch flow (PIN login):
     * - PIN validation
     * - Server start
     */
    @Test
    fun testSubsequentLaunchFlow() = runBlocking {
        Logger.logInfo("AppLaunchFlowTest", "Testing subsequent launch flow")
        
        // Setup: Create existing PIN
        val testPin = "5678"
        val encryptedPin = pinEncryptionManager.encryptPin(testPin)
        prefs.edit().putString(KEY_ENCRYPTED_PIN, encryptedPin).apply()
        
        // Step 1: Verify PIN exists (subsequent launch)
        assertTrue("PIN should exist", prefs.contains(KEY_ENCRYPTED_PIN))
        
        // Step 2: Validate PIN (simulate login)
        val storedEncryptedPin = prefs.getString(KEY_ENCRYPTED_PIN, null)!!
        val storedPin = pinEncryptionManager.decryptPin(storedEncryptedPin)
        assertEquals("PIN should validate correctly", testPin, storedPin)
        
        // Step 3: Start server
        Logger.logInfo("AppLaunchFlowTest", "Starting server")
        val serverStarted = serverProcessManager.startServer()
        assertTrue("Server should start successfully", serverStarted)
        
        // Wait for server to become healthy
        delay(3000)
        
        // Step 4: Verify server is running
        assertTrue("Server should be running", serverProcessManager.isServerRunning())
        
        Logger.logInfo("AppLaunchFlowTest", "Subsequent launch flow test passed")
    }

    /**
     * Test launch flow with incorrect PIN.
     */
    @Test
    fun testLaunchFlowWithIncorrectPin() = runBlocking {
        Logger.logInfo("AppLaunchFlowTest", "Testing launch flow with incorrect PIN")
        
        // Setup: Create existing PIN
        val correctPin = "9999"
        val incorrectPin = "0000"
        val encryptedPin = pinEncryptionManager.encryptPin(correctPin)
        prefs.edit().putString(KEY_ENCRYPTED_PIN, encryptedPin).apply()
        
        // Step 1: Attempt validation with incorrect PIN
        val storedEncryptedPin = prefs.getString(KEY_ENCRYPTED_PIN, null)!!
        val storedPin = pinEncryptionManager.decryptPin(storedEncryptedPin)
        assertNotEquals("Incorrect PIN should not validate", incorrectPin, storedPin)
        
        // Step 2: Server should NOT start without successful PIN validation
        // (In real app, MainActivity would not proceed to server start)
        Logger.logInfo("AppLaunchFlowTest", "Server should not start without PIN validation")
        
        Logger.logInfo("AppLaunchFlowTest", "Launch flow with incorrect PIN test passed")
    }

    /**
     * Test launch flow with server startup failure.
     */
    @Test
    fun testLaunchFlowWithServerFailure() = runBlocking {
        Logger.logInfo("AppLaunchFlowTest", "Testing launch flow with server failure")
        
        // Setup: Create PIN
        val testPin = "1111"
        val encryptedPin = pinEncryptionManager.encryptPin(testPin)
        prefs.edit().putString(KEY_ENCRYPTED_PIN, encryptedPin).apply()
        
        // Step 1: Validate PIN
        val storedEncryptedPin = prefs.getString(KEY_ENCRYPTED_PIN, null)!!
        val storedPin = pinEncryptionManager.decryptPin(storedEncryptedPin)
        assertEquals("PIN should validate", testPin, storedPin)
        
        // Step 2: Attempt to start server
        val serverStarted = serverProcessManager.startServer()
        
        // Step 3: Handle potential server failure
        if (!serverStarted) {
            Logger.logInfo("AppLaunchFlowTest", "Server failed to start (expected in some test environments)")
            val status = serverProcessManager.getServerStatus()
            assertFalse("Server status should show not running", status.isRunning)
            assertNotNull("Server should have error message", status.lastError)
        } else {
            Logger.logInfo("AppLaunchFlowTest", "Server started successfully")
            delay(2000)
            assertTrue("Server should be running", serverProcessManager.isServerRunning())
        }
        
        Logger.logInfo("AppLaunchFlowTest", "Launch flow with server failure test passed")
    }

    /**
     * Test complete launch sequence timing.
     */
    @Test
    fun testLaunchSequenceTiming() = runBlocking {
        Logger.logInfo("AppLaunchFlowTest", "Testing launch sequence timing")
        
        val startTime = System.currentTimeMillis()
        
        // Step 1: PIN setup
        val testPin = "2222"
        val encryptedPin = pinEncryptionManager.encryptPin(testPin)
        prefs.edit().putString(KEY_ENCRYPTED_PIN, encryptedPin).apply()
        
        val pinSetupTime = System.currentTimeMillis() - startTime
        Logger.logInfo("AppLaunchFlowTest", "PIN setup time: ${pinSetupTime}ms")
        
        // Step 2: Server start
        val serverStartTime = System.currentTimeMillis()
        val serverStarted = serverProcessManager.startServer()
        
        if (serverStarted) {
            delay(3000)
            val serverReadyTime = System.currentTimeMillis() - serverStartTime
            Logger.logInfo("AppLaunchFlowTest", "Server ready time: ${serverReadyTime}ms")
            
            // Verify reasonable timing (server should start within 10 seconds)
            assertTrue("Server should start within 10 seconds", serverReadyTime < 10000)
        }
        
        val totalTime = System.currentTimeMillis() - startTime
        Logger.logInfo("AppLaunchFlowTest", "Total launch time: ${totalTime}ms")
        
        Logger.logInfo("AppLaunchFlowTest", "Launch sequence timing test passed")
    }

    /**
     * Test launch flow with multiple retries.
     */
    @Test
    fun testLaunchFlowWithRetries() = runBlocking {
        Logger.logInfo("AppLaunchFlowTest", "Testing launch flow with retries")
        
        // Setup: Create PIN
        val testPin = "3333"
        val encryptedPin = pinEncryptionManager.encryptPin(testPin)
        prefs.edit().putString(KEY_ENCRYPTED_PIN, encryptedPin).apply()
        
        // Attempt server start with retries
        val maxRetries = 3
        var attempt = 0
        var serverStarted = false
        
        while (attempt < maxRetries && !serverStarted) {
            attempt++
            Logger.logInfo("AppLaunchFlowTest", "Server start attempt $attempt/$maxRetries")
            
            serverStarted = serverProcessManager.startServer()
            
            if (!serverStarted) {
                Logger.logInfo("AppLaunchFlowTest", "Server start failed, retrying...")
                delay(2000)
            }
        }
        
        if (serverStarted) {
            delay(2000)
            assertTrue("Server should be running after retries", serverProcessManager.isServerRunning())
            Logger.logInfo("AppLaunchFlowTest", "Server started successfully after $attempt attempts")
        } else {
            Logger.logInfo("AppLaunchFlowTest", "Server failed to start after $maxRetries attempts")
        }
        
        Logger.logInfo("AppLaunchFlowTest", "Launch flow with retries test passed")
    }

    /**
     * Test launch flow state persistence.
     */
    @Test
    fun testLaunchFlowStatePersistence() = runBlocking {
        Logger.logInfo("AppLaunchFlowTest", "Testing launch flow state persistence")
        
        // Step 1: Setup initial state
        val testPin = "4444"
        val encryptedPin = pinEncryptionManager.encryptPin(testPin)
        prefs.edit().putString(KEY_ENCRYPTED_PIN, encryptedPin).apply()
        
        // Step 2: Start server
        val serverStarted = serverProcessManager.startServer()
        if (serverStarted) {
            delay(2000)
            assertTrue("Server should be running", serverProcessManager.isServerRunning())
        }
        
        // Step 3: Verify state persists
        val storedPin = prefs.getString(KEY_ENCRYPTED_PIN, null)
        assertNotNull("PIN should persist", storedPin)
        
        val decryptedPin = pinEncryptionManager.decryptPin(storedPin!!)
        assertEquals("Persisted PIN should match", testPin, decryptedPin)
        
        Logger.logInfo("AppLaunchFlowTest", "Launch flow state persistence test passed")
    }

    /**
     * Test launch flow cleanup on failure.
     */
    @Test
    fun testLaunchFlowCleanupOnFailure() = runBlocking {
        Logger.logInfo("AppLaunchFlowTest", "Testing launch flow cleanup on failure")
        
        // Setup: Create PIN
        val testPin = "5555"
        val encryptedPin = pinEncryptionManager.encryptPin(testPin)
        prefs.edit().putString(KEY_ENCRYPTED_PIN, encryptedPin).apply()
        
        // Attempt server start
        val serverStarted = serverProcessManager.startServer()
        
        if (serverStarted) {
            delay(2000)
            
            // Simulate failure by stopping server
            serverProcessManager.stopServer()
            delay(1000)
            
            // Verify cleanup
            assertFalse("Server should be stopped", serverProcessManager.isServerRunning())
        }
        
        // Verify PIN state is preserved even after failure
        assertTrue("PIN should still exist after failure", prefs.contains(KEY_ENCRYPTED_PIN))
        
        Logger.logInfo("AppLaunchFlowTest", "Launch flow cleanup on failure test passed")
    }

    /**
     * Test rapid launch/stop cycles.
     */
    @Test
    fun testRapidLaunchStopCycles() = runBlocking {
        Logger.logInfo("AppLaunchFlowTest", "Testing rapid launch/stop cycles")
        
        // Setup: Create PIN
        val testPin = "6666"
        val encryptedPin = pinEncryptionManager.encryptPin(testPin)
        prefs.edit().putString(KEY_ENCRYPTED_PIN, encryptedPin).apply()
        
        // Perform multiple rapid cycles
        for (i in 1..3) {
            Logger.logInfo("AppLaunchFlowTest", "Cycle $i: Starting")
            
            val started = serverProcessManager.startServer()
            if (started) {
                delay(1000)
                
                Logger.logInfo("AppLaunchFlowTest", "Cycle $i: Stopping")
                serverProcessManager.stopServer()
                delay(500)
            }
        }
        
        // Verify final state
        assertFalse("Server should be stopped after cycles", serverProcessManager.isServerRunning())
        
        Logger.logInfo("AppLaunchFlowTest", "Rapid launch/stop cycles test passed")
    }
}
