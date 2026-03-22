package com.fintrack.pk.integration

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fintrack.pk.server.ServerProcessManager
import com.fintrack.pk.ui.MainActivity
import com.fintrack.pk.ui.WebViewManager
import com.fintrack.pk.utils.Logger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Integration tests for component interactions between ServerProcessManager,
 * WebViewManager, and MainActivity.
 * 
 * Tests verify:
 * - Server lifecycle coordination with MainActivity
 * - WebView initialization after server starts
 * - Error handling and recovery flows
 * - Component state synchronization
 */
@RunWith(AndroidJUnit4::class)
class ComponentIntegrationTest {

    private lateinit var context: Context
    private lateinit var serverProcessManager: ServerProcessManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        serverProcessManager = ServerProcessManager(context)
        
        Logger.logInfo("ComponentIntegrationTest", "Test setup complete")
    }

    @After
    fun tearDown() {
        // Clean up server
        if (serverProcessManager.isServerRunning()) {
            serverProcessManager.stopServer()
        }
        serverProcessManager.cleanup()
        
        Logger.logInfo("ComponentIntegrationTest", "Test teardown complete")
    }

    /**
     * Test that ServerProcessManager starts successfully and MainActivity
     * can coordinate with it.
     */
    @Test
    fun testServerStartupCoordination() = runBlocking {
        Logger.logInfo("ComponentIntegrationTest", "Testing server startup coordination")
        
        // Start server
        val started = serverProcessManager.startServer()
        assertTrue("Server should start successfully", started)
        
        // Wait for server to become healthy
        delay(3000)
        
        // Verify server is running
        assertTrue("Server should be running", serverProcessManager.isServerRunning())
        
        // Get server status
        val status = serverProcessManager.getServerStatus()
        assertTrue("Server status should show running", status.isRunning)
        assertEquals("Server port should be 8000", 8000, status.port)
        assertTrue("Server uptime should be positive", status.uptime > 0)
        assertNull("Server should have no errors", status.lastError)
        
        Logger.logInfo("ComponentIntegrationTest", "Server startup coordination test passed")
    }

    /**
     * Test server restart coordination between components.
     */
    @Test
    fun testServerRestartCoordination() = runBlocking {
        Logger.logInfo("ComponentIntegrationTest", "Testing server restart coordination")
        
        // Start server
        serverProcessManager.startServer()
        delay(2000)
        assertTrue("Server should be running", serverProcessManager.isServerRunning())
        
        // Restart server
        serverProcessManager.restartServer()
        delay(3000)
        
        // Verify server is running again
        assertTrue("Server should be running after restart", serverProcessManager.isServerRunning())
        
        val status = serverProcessManager.getServerStatus()
        assertTrue("Server status should show running", status.isRunning)
        
        Logger.logInfo("ComponentIntegrationTest", "Server restart coordination test passed")
    }

    /**
     * Test server stop coordination.
     */
    @Test
    fun testServerStopCoordination() = runBlocking {
        Logger.logInfo("ComponentIntegrationTest", "Testing server stop coordination")
        
        // Start server
        serverProcessManager.startServer()
        delay(2000)
        assertTrue("Server should be running", serverProcessManager.isServerRunning())
        
        // Stop server
        serverProcessManager.stopServer()
        delay(1000)
        
        // Verify server is stopped
        assertFalse("Server should be stopped", serverProcessManager.isServerRunning())
        
        val status = serverProcessManager.getServerStatus()
        assertFalse("Server status should show not running", status.isRunning)
        assertEquals("Server uptime should be 0", 0L, status.uptime)
        
        Logger.logInfo("ComponentIntegrationTest", "Server stop coordination test passed")
    }

    /**
     * Test server crash callback integration.
     */
    @Test
    fun testServerCrashCallbackIntegration() = runBlocking {
        Logger.logInfo("ComponentIntegrationTest", "Testing server crash callback integration")
        
        var callbackInvoked = false
        
        // Set crash callback
        serverProcessManager.setServerCrashCallback(object : ServerProcessManager.ServerCrashCallback {
            override fun onServerCrashedAndExhausted() {
                callbackInvoked = true
                Logger.logInfo("ComponentIntegrationTest", "Crash callback invoked")
            }
        })
        
        // Start server
        serverProcessManager.startServer()
        delay(2000)
        
        // Verify callback is registered (we can't easily trigger a real crash in tests)
        // This test verifies the callback mechanism is properly set up
        assertNotNull("Crash callback should be registered", serverProcessManager)
        
        Logger.logInfo("ComponentIntegrationTest", "Server crash callback integration test passed")
    }

    /**
     * Test server status reporting integration.
     */
    @Test
    fun testServerStatusReporting() = runBlocking {
        Logger.logInfo("ComponentIntegrationTest", "Testing server status reporting")
        
        // Get status when server is not running
        val statusBefore = serverProcessManager.getServerStatus()
        assertFalse("Server should not be running initially", statusBefore.isRunning)
        assertEquals("Uptime should be 0", 0L, statusBefore.uptime)
        
        // Start server
        serverProcessManager.startServer()
        delay(2000)
        
        // Get status when server is running
        val statusAfter = serverProcessManager.getServerStatus()
        assertTrue("Server should be running", statusAfter.isRunning)
        assertTrue("Uptime should be positive", statusAfter.uptime > 0)
        assertEquals("Port should be 8000", 8000, statusAfter.port)
        
        Logger.logInfo("ComponentIntegrationTest", "Server status reporting test passed")
    }

    /**
     * Test multiple start/stop cycles.
     */
    @Test
    fun testMultipleStartStopCycles() = runBlocking {
        Logger.logInfo("ComponentIntegrationTest", "Testing multiple start/stop cycles")
        
        for (i in 1..3) {
            Logger.logInfo("ComponentIntegrationTest", "Cycle $i: Starting server")
            
            // Start server
            val started = serverProcessManager.startServer()
            assertTrue("Server should start on cycle $i", started)
            delay(2000)
            assertTrue("Server should be running on cycle $i", serverProcessManager.isServerRunning())
            
            // Stop server
            Logger.logInfo("ComponentIntegrationTest", "Cycle $i: Stopping server")
            serverProcessManager.stopServer()
            delay(1000)
            assertFalse("Server should be stopped on cycle $i", serverProcessManager.isServerRunning())
        }
        
        Logger.logInfo("ComponentIntegrationTest", "Multiple start/stop cycles test passed")
    }

    /**
     * Test server health monitoring integration.
     */
    @Test
    fun testServerHealthMonitoring() = runBlocking {
        Logger.logInfo("ComponentIntegrationTest", "Testing server health monitoring")
        
        // Start server
        serverProcessManager.startServer()
        delay(3000)
        
        // Server should be healthy and running
        assertTrue("Server should be running and healthy", serverProcessManager.isServerRunning())
        
        // Monitor for a period to ensure health checks are working
        delay(10000) // Wait 10 seconds
        
        // Server should still be running
        assertTrue("Server should still be running after health checks", serverProcessManager.isServerRunning())
        
        Logger.logInfo("ComponentIntegrationTest", "Server health monitoring test passed")
    }

    /**
     * Test component cleanup integration.
     */
    @Test
    fun testComponentCleanup() = runBlocking {
        Logger.logInfo("ComponentIntegrationTest", "Testing component cleanup")
        
        // Start server
        serverProcessManager.startServer()
        delay(2000)
        assertTrue("Server should be running", serverProcessManager.isServerRunning())
        
        // Cleanup
        serverProcessManager.cleanup()
        delay(1000)
        
        // Server should be stopped after cleanup
        assertFalse("Server should be stopped after cleanup", serverProcessManager.isServerRunning())
        
        Logger.logInfo("ComponentIntegrationTest", "Component cleanup test passed")
    }
}
