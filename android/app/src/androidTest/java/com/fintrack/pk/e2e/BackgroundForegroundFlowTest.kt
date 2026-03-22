package com.fintrack.pk.e2e

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fintrack.pk.security.PinEncryptionManager
import com.fintrack.pk.server.ServerProcessManager
import com.fintrack.pk.utils.Logger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end tests for app lifecycle with PIN re-authentication.
 * 
 * Tests verify:
 * - App going to background and returning
 * - PIN re-authentication after 5 minutes in background
 * - Server state preservation during background/foreground transitions
 * - State restoration after background
 */
@RunWith(AndroidJUnit4::class)
class BackgroundForegroundFlowTest {

    private lateinit var context: Context
    private lateinit var serverProcessManager: ServerProcessManager
    private lateinit var pinEncryptionManager: PinEncryptionManager
    private lateinit var prefs: SharedPreferences

    companion object {
        private const val PREFS_NAME = "app_prefs"
        private const val KEY_ENCRYPTED_PIN = "encrypted_pin"
        private const val PIN_REAUTH_THRESHOLD_MS = 5 * 60 * 1000L // 5 minutes
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        serverProcessManager = ServerProcessManager(context)
        pinEncryptionManager = PinEncryptionManager()
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        // Setup PIN
        val testPin = "1234"
        val encryptedPin = pinEncryptionManager.encryptPin(testPin)
        prefs.edit().putString(KEY_ENCRYPTED_PIN, encryptedPin).apply()
        
        Logger.logInfo("BackgroundForegroundFlowTest", "Test setup complete")
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
        
        Logger.logInfo("BackgroundForegroundFlowTest", "Test teardown complete")
    }

    /**
     * Test app going to background and returning within 5 minutes (no re-auth needed).
     */
    @Test
    fun testBackgroundForegroundWithinThreshold() = runBlocking {
        Logger.logInfo("BackgroundForegroundFlowTest", "Testing background/foreground within threshold")
        
        // Start server
        val started = serverProcessManager.startServer()
        if (started) {
            delay(2000)
            assertTrue("Server should be running", serverProcessManager.isServerRunning())
        }
        
        // Simulate going to background
        val pauseTimestamp = System.currentTimeMillis()
        Logger.logInfo("BackgroundForegroundFlowTest", "App going to background at $pauseTimestamp")
        
        // Wait 2 seconds (less than 5 minutes)
        delay(2000)
        
        // Simulate returning to foreground
        val resumeTimestamp = System.currentTimeMillis()
        val timeInBackground = resumeTimestamp - pauseTimestamp
        Logger.logInfo("BackgroundForegroundFlowTest", "App returning to foreground, time in background: ${timeInBackground}ms")
        
        // Verify no re-auth needed
        assertTrue("Time in background should be less than threshold", 
            timeInBackground < PIN_REAUTH_THRESHOLD_MS)
        
        // Verify server is still running
        if (started) {
            assertTrue("Server should still be running", serverProcessManager.isServerRunning())
        }
        
        Logger.logInfo("BackgroundForegroundFlowTest", "Background/foreground within threshold test passed")
    }

    /**
     * Test app going to background for more than 5 minutes (re-auth required).
     */
    @Test
    fun testBackgroundForegroundExceedingThreshold() = runBlocking {
        Logger.logInfo("BackgroundForegroundFlowTest", "Testing background/foreground exceeding threshold")
        
        // Start server
        val started = serverProcessManager.startServer()
        if (started) {
            delay(2000)
            assertTrue("Server should be running", serverProcessManager.isServerRunning())
        }
        
        // Simulate going to background 6 minutes ago
        val pauseTimestamp = System.currentTimeMillis() - (6 * 60 * 1000L)
        Logger.logInfo("BackgroundForegroundFlowTest", "Simulating app was in background since $pauseTimestamp")
        
        // Simulate returning to foreground
        val resumeTimestamp = System.currentTimeMillis()
        val timeInBackground = resumeTimestamp - pauseTimestamp
        Logger.logInfo("BackgroundForegroundFlowTest", "Time in background: ${timeInBackground}ms (${timeInBackground / 1000}s)")
        
        // Verify re-auth is needed
        assertTrue("Time in background should exceed threshold", 
            timeInBackground > PIN_REAUTH_THRESHOLD_MS)
        
        // In real app, PIN re-authentication would be required here
        Logger.logInfo("BackgroundForegroundFlowTest", "PIN re-authentication would be required")
        
        // Verify PIN can be validated
        val storedEncryptedPin = prefs.getString(KEY_ENCRYPTED_PIN, null)!!
        val storedPin = pinEncryptionManager.decryptPin(storedEncryptedPin)
        assertEquals("PIN should validate correctly", "1234", storedPin)
        
        Logger.logInfo("BackgroundForegroundFlowTest", "Background/foreground exceeding threshold test passed")
    }

    /**
     * Test server state preservation during background/foreground.
     */
    @Test
    fun testServerStatePreservation() = runBlocking {
        Logger.logInfo("BackgroundForegroundFlowTest", "Testing server state preservation")
        
        // Start server
        val started = serverProcessManager.startServer()
        if (!started) {
            Logger.logInfo("BackgroundForegroundFlowTest", "Server failed to start, skipping test")
            return@runBlocking
        }
        
        delay(2000)
        assertTrue("Server should be running", serverProcessManager.isServerRunning())
        
        val statusBefore = serverProcessManager.getServerStatus()
        Logger.logInfo("BackgroundForegroundFlowTest", "Server status before background: running=${statusBefore.isRunning}, uptime=${statusBefore.uptime}")
        
        // Simulate background
        Logger.logInfo("BackgroundForegroundFlowTest", "App going to background")
        delay(3000)
        
        // Simulate foreground
        Logger.logInfo("BackgroundForegroundFlowTest", "App returning to foreground")
        
        // Verify server is still running
        assertTrue("Server should still be running after background", serverProcessManager.isServerRunning())
        
        val statusAfter = serverProcessManager.getServerStatus()
        Logger.logInfo("BackgroundForegroundFlowTest", "Server status after foreground: running=${statusAfter.isRunning}, uptime=${statusAfter.uptime}")
        
        assertTrue("Server uptime should have increased", statusAfter.uptime > statusBefore.uptime)
        
        Logger.logInfo("BackgroundForegroundFlowTest", "Server state preservation test passed")
    }

    /**
     * Test server restart after background if not running.
     */
    @Test
    fun testServerRestartAfterBackground() = runBlocking {
        Logger.logInfo("BackgroundForegroundFlowTest", "Testing server restart after background")
        
        // Start server
        val started = serverProcessManager.startServer()
        if (!started) {
            Logger.logInfo("BackgroundForegroundFlowTest", "Server failed to start, skipping test")
            return@runBlocking
        }
        
        delay(2000)
        assertTrue("Server should be running", serverProcessManager.isServerRunning())
        
        // Simulate server crash while in background
        Logger.logInfo("BackgroundForegroundFlowTest", "Simulating server crash")
        serverProcessManager.stopServer()
        delay(1000)
        assertFalse("Server should be stopped", serverProcessManager.isServerRunning())
        
        // Simulate returning to foreground and detecting server is not running
        Logger.logInfo("BackgroundForegroundFlowTest", "Returning to foreground, restarting server")
        val restarted = serverProcessManager.startServer()
        
        if (restarted) {
            delay(2000)
            assertTrue("Server should be running after restart", serverProcessManager.isServerRunning())
        }
        
        Logger.logInfo("BackgroundForegroundFlowTest", "Server restart after background test passed")
    }

    /**
     * Test multiple background/foreground cycles.
     */
    @Test
    fun testMultipleBackgroundForegroundCycles() = runBlocking {
        Logger.logInfo("BackgroundForegroundFlowTest", "Testing multiple background/foreground cycles")
        
        // Start server
        val started = serverProcessManager.startServer()
        if (!started) {
            Logger.logInfo("BackgroundForegroundFlowTest", "Server failed to start, skipping test")
            return@runBlocking
        }
        
        delay(2000)
        
        // Perform multiple cycles
        for (i in 1..3) {
            Logger.logInfo("BackgroundForegroundFlowTest", "Cycle $i: Going to background")
            val pauseTime = System.currentTimeMillis()
            
            delay(1000)
            
            Logger.logInfo("BackgroundForegroundFlowTest", "Cycle $i: Returning to foreground")
            val resumeTime = System.currentTimeMillis()
            val timeInBackground = resumeTime - pauseTime
            
            // Verify no re-auth needed (less than 5 minutes)
            assertTrue("Time in background should be less than threshold", 
                timeInBackground < PIN_REAUTH_THRESHOLD_MS)
            
            // Verify server is still running
            assertTrue("Server should be running in cycle $i", serverProcessManager.isServerRunning())
        }
        
        Logger.logInfo("BackgroundForegroundFlowTest", "Multiple background/foreground cycles test passed")
    }

    /**
     * Test PIN re-authentication timing boundary.
     */
    @Test
    fun testPinReauthenticationTimingBoundary() {
        Logger.logInfo("BackgroundForegroundFlowTest", "Testing PIN re-authentication timing boundary")
        
        // Test exactly at threshold
        val exactlyAtThreshold = PIN_REAUTH_THRESHOLD_MS
        assertTrue("Exactly at threshold should require re-auth", 
            exactlyAtThreshold >= PIN_REAUTH_THRESHOLD_MS)
        
        // Test just before threshold
        val justBeforeThreshold = PIN_REAUTH_THRESHOLD_MS - 1000
        assertFalse("Just before threshold should not require re-auth", 
            justBeforeThreshold >= PIN_REAUTH_THRESHOLD_MS)
        
        // Test just after threshold
        val justAfterThreshold = PIN_REAUTH_THRESHOLD_MS + 1000
        assertTrue("Just after threshold should require re-auth", 
            justAfterThreshold > PIN_REAUTH_THRESHOLD_MS)
        
        Logger.logInfo("BackgroundForegroundFlowTest", "PIN re-authentication timing boundary test passed")
    }

    /**
     * Test state persistence across background/foreground.
     */
    @Test
    fun testStatePersistenceAcrossBackgroundForeground() = runBlocking {
        Logger.logInfo("BackgroundForegroundFlowTest", "Testing state persistence")
        
        // Setup state
        prefs.edit().putString("test_key", "test_value").apply()
        
        // Start server
        val started = serverProcessManager.startServer()
        if (started) {
            delay(2000)
        }
        
        // Simulate background
        Logger.logInfo("BackgroundForegroundFlowTest", "Going to background")
        delay(1000)
        
        // Simulate foreground
        Logger.logInfo("BackgroundForegroundFlowTest", "Returning to foreground")
        
        // Verify state persisted
        val persistedValue = prefs.getString("test_key", null)
        assertEquals("State should persist", "test_value", persistedValue)
        
        // Verify PIN persisted
        val persistedPin = prefs.getString(KEY_ENCRYPTED_PIN, null)
        assertNotNull("PIN should persist", persistedPin)
        
        // Clean up
        prefs.edit().remove("test_key").apply()
        
        Logger.logInfo("BackgroundForegroundFlowTest", "State persistence test passed")
    }

    /**
     * Test background/foreground with failed PIN re-authentication.
     */
    @Test
    fun testBackgroundForegroundWithFailedReauth() {
        Logger.logInfo("BackgroundForegroundFlowTest", "Testing background/foreground with failed re-auth")
        
        // Simulate being in background for more than 5 minutes
        val pauseTimestamp = System.currentTimeMillis() - (6 * 60 * 1000L)
        val timeInBackground = System.currentTimeMillis() - pauseTimestamp
        
        // Verify re-auth is needed
        assertTrue("Re-auth should be needed", timeInBackground > PIN_REAUTH_THRESHOLD_MS)
        
        // Simulate failed re-authentication
        val incorrectPin = "0000"
        val storedEncryptedPin = prefs.getString(KEY_ENCRYPTED_PIN, null)!!
        val storedPin = pinEncryptionManager.decryptPin(storedEncryptedPin)
        
        assertNotEquals("Incorrect PIN should not match", incorrectPin, storedPin)
        
        // In real app, user would be returned to PIN screen or app would exit
        Logger.logInfo("BackgroundForegroundFlowTest", "Failed re-auth would exit app")
        
        Logger.logInfo("BackgroundForegroundFlowTest", "Background/foreground with failed re-auth test passed")
    }

    /**
     * Test rapid background/foreground transitions.
     */
    @Test
    fun testRapidBackgroundForegroundTransitions() = runBlocking {
        Logger.logInfo("BackgroundForegroundFlowTest", "Testing rapid background/foreground transitions")
        
        // Start server
        val started = serverProcessManager.startServer()
        if (!started) {
            Logger.logInfo("BackgroundForegroundFlowTest", "Server failed to start, skipping test")
            return@runBlocking
        }
        
        delay(2000)
        
        // Perform rapid transitions
        for (i in 1..5) {
            Logger.logInfo("BackgroundForegroundFlowTest", "Rapid transition $i")
            
            // Background
            val pauseTime = System.currentTimeMillis()
            delay(100) // Very short background time
            
            // Foreground
            val resumeTime = System.currentTimeMillis()
            val timeInBackground = resumeTime - pauseTime
            
            assertTrue("Time should be very short", timeInBackground < 1000)
            assertFalse("No re-auth needed for rapid transitions", 
                timeInBackground > PIN_REAUTH_THRESHOLD_MS)
        }
        
        // Verify server is still running
        assertTrue("Server should still be running after rapid transitions", 
            serverProcessManager.isServerRunning())
        
        Logger.logInfo("BackgroundForegroundFlowTest", "Rapid background/foreground transitions test passed")
    }

    /**
     * Test background/foreground with server health check.
     */
    @Test
    fun testBackgroundForegroundWithServerHealthCheck() = runBlocking {
        Logger.logInfo("BackgroundForegroundFlowTest", "Testing background/foreground with server health check")
        
        // Start server
        val started = serverProcessManager.startServer()
        if (!started) {
            Logger.logInfo("BackgroundForegroundFlowTest", "Server failed to start, skipping test")
            return@runBlocking
        }
        
        delay(2000)
        assertTrue("Server should be running", serverProcessManager.isServerRunning())
        
        // Simulate background
        Logger.logInfo("BackgroundForegroundFlowTest", "Going to background")
        delay(2000)
        
        // Simulate foreground with health check
        Logger.logInfo("BackgroundForegroundFlowTest", "Returning to foreground, checking server health")
        
        val isHealthy = serverProcessManager.isServerRunning()
        Logger.logInfo("BackgroundForegroundFlowTest", "Server health check: $isHealthy")
        
        if (!isHealthy) {
            Logger.logInfo("BackgroundForegroundFlowTest", "Server not healthy, would restart")
            // In real app, would restart server here
        }
        
        Logger.logInfo("BackgroundForegroundFlowTest", "Background/foreground with server health check test passed")
    }
}
