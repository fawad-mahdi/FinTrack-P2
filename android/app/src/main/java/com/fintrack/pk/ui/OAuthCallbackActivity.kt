package com.fintrack.pk.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.fintrack.pk.utils.GmailTokenBroker
import com.fintrack.pk.utils.Logger
import com.fintrack.pk.utils.OAuthTokenManager
import kotlinx.coroutines.*
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.ClientAuthentication
import net.openid.appauth.ClientSecretPost
import net.openid.appauth.NoClientAuthentication
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Activity for handling the OAuth redirect from the browser.
 *
 * This exported activity is the single entry point for the custom-scheme
 * callback. Before any token exchange it binds the incoming redirect to the
 * authorization request this app actually initiated (AUTH-06):
 *
 * - The pending AuthorizationRequest (state + PKCE code verifier) was
 *   persisted in Keystore-backed encrypted storage by MainActivity.
 * - It is consumed one-shot: a redirect can only ever be matched once.
 * - The redirect's `state` must exactly match the pending request's state;
 *   forged, replayed, or mismatched callbacks are rejected without exchange.
 * - The token exchange carries the pending request's PKCE verifier and
 *   authenticates the client, and the wait is bounded (no unbounded latch).
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

        authService = AuthorizationService(this)
        tokenManager = OAuthTokenManager(this)

        handleCallbackIntent(intent)
    }

    /**
     * With launchMode=singleTask a second redirect while this activity is
     * alive arrives here instead of onCreate.
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        Logger.logInfo("OAuthCallbackActivity", "OAuth callback received via onNewIntent")
        if (intent != null) {
            handleCallbackIntent(intent)
        }
    }

    private fun handleCallbackIntent(intent: Intent) {
        // An explicit AppAuth error extra (e.g. user cancelled the tab) does
        // not require request binding to be reported.
        val exception = AuthorizationException.fromIntent(intent)
        if (exception != null) {
            handleAuthorizationError(exception)
            return
        }

        val redirectUri = intent.data
        if (redirectUri == null) {
            Logger.logError("OAuthCallbackActivity", "Callback intent carries no redirect URI")
            rejectCallback("Authorization failed: No response received")
            return
        }

        // Bind the redirect to the request we initiated. One-shot: consuming
        // removes the stored request, so replays find nothing to match.
        val pendingRequestJson = GmailTokenBroker.consumePendingAuthRequest(this)
        if (pendingRequestJson == null) {
            Logger.logError(
                "OAuthCallbackActivity",
                "No pending authorization request — rejecting unsolicited callback"
            )
            rejectCallback("Ignoring unexpected authorization response. Please start again from Settings.")
            return
        }

        val pendingRequest = try {
            AuthorizationRequest.jsonDeserialize(pendingRequestJson)
        } catch (e: Exception) {
            Logger.logError("OAuthCallbackActivity", "Stored pending request unreadable: ${e.message}")
            rejectCallback("Authorization failed. Please start again from Settings.")
            return
        }

        // Provider-reported error (e.g. access_denied) on the redirect itself
        val errorParam = redirectUri.getQueryParameter("error")
        if (errorParam != null) {
            Logger.logError("OAuthCallbackActivity", "Authorization error on redirect: $errorParam")
            val message = if (errorParam == "access_denied") {
                "Gmail access was denied. You can connect Gmail later from Settings."
            } else {
                "Authorization failed: $errorParam"
            }
            rejectCallback(message)
            return
        }

        // Exact state binding: reject any redirect whose state does not
        // match the request this app persisted.
        val returnedState = redirectUri.getQueryParameter("state")
        if (returnedState == null || returnedState != pendingRequest.state) {
            Logger.logError(
                "OAuthCallbackActivity",
                "State mismatch on authorization callback — possible forged or stale redirect, rejecting"
            )
            rejectCallback("Authorization failed: response could not be verified. Please start again from Settings.")
            return
        }

        val response = try {
            AuthorizationResponse.Builder(pendingRequest)
                .fromUri(redirectUri)
                .build()
        } catch (e: Exception) {
            Logger.logError("OAuthCallbackActivity", "Malformed authorization response: ${e.message}")
            rejectCallback("Authorization failed: malformed response")
            return
        }

        if (response.authorizationCode.isNullOrEmpty()) {
            Logger.logError("OAuthCallbackActivity", "Authorization response carries no code")
            rejectCallback("Authorization failed: no authorization code received")
            return
        }

        Logger.logInfo("OAuthCallbackActivity", "Authorization response verified (state + PKCE bound)")
        exchangeAuthorizationCode(response)
    }

    private fun rejectCallback(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    /**
     * Handle authorization errors with specific messages.
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
     * Exchange the verified authorization code for tokens.
     *
     * The token request is created from the bound AuthorizationResponse, so
     * it carries the original request's PKCE code verifier. Google's
     * installed-app clients additionally require the client secret at the
     * token endpoint, supplied via ClientSecretPost. Tokens are stored in
     * Keystore-backed encrypted storage (GmailTokenBroker) — never in
     * plaintext files.
     */
    private fun exchangeAuthorizationCode(authResponse: AuthorizationResponse) {
        Logger.logInfo("OAuthCallbackActivity", "Starting token exchange")

        val credentials = tokenManager.loadCredentials()
        if (credentials == null) {
            Logger.logError("OAuthCallbackActivity", "Failed to load credentials for token exchange")
            Toast.makeText(this, "Failed to load OAuth credentials", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val tokenRequest = authResponse.createTokenExchangeRequest()
        val clientAuth: ClientAuthentication = if (credentials.clientSecret.isNotEmpty()) {
            ClientSecretPost(credentials.clientSecret)
        } else {
            NoClientAuthentication.INSTANCE
        }

        scope.launch {
            try {
                val tokenResponse = withContext(Dispatchers.IO) {
                    var result: net.openid.appauth.TokenResponse? = null
                    var error: AuthorizationException? = null
                    val latch = CountDownLatch(1)

                    authService.performTokenRequest(tokenRequest, clientAuth) { response, exception ->
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

                // Store tokens in the encrypted token store
                val tokensSaved = tokenManager.saveInitialTokens(tokenResponse, credentials)

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
