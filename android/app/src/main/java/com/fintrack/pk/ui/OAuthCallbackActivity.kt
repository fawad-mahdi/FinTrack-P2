package com.fintrack.pk.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.fintrack.pk.utils.Logger
import com.fintrack.pk.utils.OAuthTokenManager
import kotlinx.coroutines.*
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.NoClientAuthentication
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Activity for handling OAuth callback from Gmail
 * Receives the authorization response and exchanges authorization code for tokens
 *
 * Task 9.2 Implementation:
 * - Receives authorization response from Custom Chrome Tab
 * - Exchanges authorization code for access + refresh tokens
 * - Stores tokens via OAuthTokenManager (Keystore-backed encrypted storage)
 *
 * Requirements: 6.1, 6.3, 6.5
 */
class OAuthCallbackActivity : AppCompatActivity() {

    companion object {
        private const val TOKEN_EXCHANGE_TIMEOUT_SECONDS = 60L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var authService: AuthorizationService
    private lateinit var tokenManager: OAuthTokenManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Logger.logInfo("OAuthCallbackActivity", "OAuth callback received")

        // Initialize AuthorizationService and TokenManager
        authService = AuthorizationService(this)
        tokenManager = OAuthTokenManager(this)

        // Handle the authorization response
        val response = AuthorizationResponse.fromIntent(intent)
        val exception = AuthorizationException.fromIntent(intent)

        when {
            response != null -> {
                Logger.logInfo("OAuthCallbackActivity", "Authorization successful, code received")
                // Task 9.2: Exchange authorization code for tokens
                exchangeAuthorizationCode(response)
            }
            exception != null -> {
                // Task 9.4: Handle OAuth errors with specific messages
                handleAuthorizationError(exception)
            }
            else -> {
                Logger.logError("OAuthCallbackActivity", "No response or exception in intent")
                Toast.makeText(this, "Authorization failed: No response received", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    /**
     * Handle authorization errors with specific messages.
     *
     * Task 9.4 Implementation:
     * - Check if user denied consent (ACCESS_DENIED error)
     * - Show appropriate message based on error type
     * - Log error details for diagnostics
     *
     * Requirements: 6.4
     */
    private fun handleAuthorizationError(exception: AuthorizationException) {
        Logger.logError("OAuthCallbackActivity", "Authorization error: ${exception.type}, ${exception.message}")

        val message = when (exception.type) {
            AuthorizationException.TYPE_OAUTH_AUTHORIZATION_ERROR -> {
                // User denied consent or other OAuth error
                if (exception.code == AuthorizationException.AuthorizationRequestErrors.ACCESS_DENIED.code) {
                    Logger.logInfo("OAuthCallbackActivity", "User denied consent")
                    "Gmail access was denied. You can connect Gmail later from Settings."
                } else {
                    "Authorization failed: ${exception.message}"
                }
            }
            AuthorizationException.TYPE_GENERAL_ERROR -> {
                "Authorization failed: ${exception.message}"
            }
            else -> {
                "Authorization failed: ${exception.message}"
            }
        }

        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    /**
     * Exchange authorization code for access and refresh tokens
     *
     * Task 9.2 Implementation:
     * - Create TokenRequest from authorization response
     * - Perform token exchange using AuthorizationService
     * - Store tokens using OAuthTokenManager
     *
     * Requirements: 6.1, 6.3
     */
    private fun exchangeAuthorizationCode(authResponse: AuthorizationResponse) {
        Logger.logInfo("OAuthCallbackActivity", "Starting token exchange")

        // Android-type OAuth client: PKCE only, no client secret needed.
        val tokenRequest = authResponse.createTokenExchangeRequest()

        // Perform token exchange
        scope.launch {
            try {
                val tokenResponse = withContext(Dispatchers.IO) {
                    // Perform synchronous token exchange
                    var result: net.openid.appauth.TokenResponse? = null
                    var error: AuthorizationException? = null
                    val latch = CountDownLatch(1)

                    authService.performTokenRequest(tokenRequest, NoClientAuthentication.INSTANCE) { response, exception ->
                        result = response
                        error = exception
                        latch.countDown()
                    }

                    // Bounded wait: never hang the flow on a stuck network call
                    if (!latch.await(TOKEN_EXCHANGE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        throw Exception("Token exchange timed out")
                    }

                    if (error != null) {
                        throw Exception("Token exchange failed: ${error!!.message}")
                    }

                    result ?: throw Exception("Token exchange returned null response")
                }

                Logger.logInfo("OAuthCallbackActivity", "Token exchange successful")

                // Store tokens using OAuthTokenManager
                val tokensSaved = tokenManager.saveInitialTokens(tokenResponse)

                if (tokensSaved) {
                    Logger.logInfo("OAuthCallbackActivity", "Tokens saved successfully")
                    Toast.makeText(this@OAuthCallbackActivity, "Gmail connected successfully", Toast.LENGTH_SHORT).show()
                } else {
                    Logger.logError("OAuthCallbackActivity", "Failed to save tokens")
                    Toast.makeText(this@OAuthCallbackActivity, "Failed to save tokens", Toast.LENGTH_LONG).show()
                }

                finish()

            } catch (e: Exception) {
                Logger.logError("OAuthCallbackActivity", "Token exchange failed: ${e.message}")
                Toast.makeText(this@OAuthCallbackActivity, "Token exchange failed: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        authService.dispose()
        scope.cancel()
    }
}
