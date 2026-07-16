package com.fintrack.pk.utils

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

/**
 * Utility class for managing OAuth tokens for Gmail API access.
 *
 * All token material lives in Keystore-backed encrypted storage owned by
 * [GmailTokenBroker]; this class is a thin facade used by the UI layer.
 * No refresh token or client secret is ever written to plaintext files.
 */
class OAuthTokenManager(private val context: Context) {

    private val gson = Gson()
    private val configDir = File(context.filesDir, Constants.CONFIG_DIR)
<<<<<<< HEAD
    private val tokenFile = File(configDir, Constants.TOKEN_FILE)

    /**
     * Check if a valid OAuth token exists.
     *
     * Also invalidates tokens issued by a different OAuth client (e.g. an
     * install upgraded from a build that shipped the old desktop-type client):
     * their refresh tokens fail with invalid_client, so we delete the token
     * and let the caller prompt a clean re-connect.
     *
     * @return true if token.json exists and contains a token, false otherwise
     */
    fun hasToken(): Boolean {
        return try {
            if (!tokenFile.exists()) {
                Logger.logInfo("OAuthTokenManager", "token.json does not exist")
                return false
            }

            val tokenData = loadTokenData()
            val hasToken = tokenData != null && tokenData.token.isNotEmpty()

            val expectedClientId = Constants.OAUTH_CLIENT_ID
            if (hasToken && expectedClientId.isNotEmpty() && tokenData!!.clientId != expectedClientId) {
                Logger.logInfo(
                    "OAuthTokenManager",
                    "Token was issued by a different OAuth client — deleting; user must reconnect Gmail"
                )
                deleteToken()
                return false
            }

            Logger.logInfo("OAuthTokenManager", "Token exists: $hasToken")
            hasToken
        } catch (e: Exception) {
            Logger.logError("OAuthTokenManager", "Error checking token existence: ${e.message}")
            false
        }
=======
    private val credentialsFile = File(configDir, Constants.CREDENTIALS_FILE)

    /**
     * Check if an OAuth token exists in the encrypted store.
     */
    fun hasToken(): Boolean {
        val hasToken = GmailTokenBroker.hasToken(context)
        Logger.logInfo("OAuthTokenManager", "Token exists: $hasToken")
        return hasToken
>>>>>>> cf5955ab49e83f742b37cfff8091bf38565c16bf
    }

    /**
     * Check if the current access token is expired (with a safety buffer).
     */
    fun isTokenExpired(): Boolean {
        val isExpired = GmailTokenBroker.isTokenExpired(context)
        Logger.logInfo("OAuthTokenManager", "Token expired: $isExpired")
        return isExpired
    }

    /**
     * Refresh the OAuth access token using the stored refresh token.
     *
     * @return true if refresh was successful, false otherwise
     */
    suspend fun refreshToken(): Boolean = withContext(Dispatchers.IO) {
<<<<<<< HEAD
        try {
            Logger.logInfo("OAuthTokenManager", "Starting token refresh")
            
            // Load current token data
            val tokenData = loadTokenData() ?: run {
                Logger.logError("OAuthTokenManager", "No token data found for refresh")
                return@withContext false
            }
            
            if (tokenData.refreshToken.isEmpty()) {
                Logger.logError("OAuthTokenManager", "No refresh token available")
                return@withContext false
            }

            Logger.logInfo("OAuthTokenManager", "Creating token refresh request")

            // Android-type OAuth client: no client secret, endpoints are constants.
            // Refresh with the client that issued the token.
            val serviceConfig = AuthorizationServiceConfiguration(
                android.net.Uri.parse(Constants.OAUTH_AUTH_URI),
                android.net.Uri.parse(Constants.OAUTH_TOKEN_URI)
            )

            // Create TokenRequest for refresh
            val tokenRequest = TokenRequest.Builder(
                serviceConfig,
                tokenData.clientId
            )
                .setRefreshToken(tokenData.refreshToken)
                .setGrantType(net.openid.appauth.GrantTypeValues.REFRESH_TOKEN)
                .build()
            
            // Perform token refresh
            val authService = AuthorizationService(context)
            
            try {
                val tokenResponse = performTokenRefresh(authService, tokenRequest)
                
                if (tokenResponse == null) {
                    Logger.logError("OAuthTokenManager", "Token refresh returned null response")
                    return@withContext false
                }
                
                Logger.logInfo("OAuthTokenManager", "Token refresh successful")
                
                // Update token.json with new token data
                val newTokenData = tokenData.copy(
                    token = tokenResponse.accessToken ?: tokenData.token,
                    // Keep existing refresh token if new one not provided
                    refreshToken = tokenResponse.refreshToken ?: tokenData.refreshToken,
                    expiry = if (tokenResponse.accessTokenExpirationTime != null) {
                        Instant.ofEpochMilli(tokenResponse.accessTokenExpirationTime!!).toString()
                    } else {
                        // Default to 1 hour from now if not provided
                        Instant.now().plusSeconds(3600).toString()
                    }
                )
                
                // Save updated token data
                val saved = saveTokenData(newTokenData)
                
                if (saved) {
                    Logger.logInfo("OAuthTokenManager", "Updated token saved successfully")
                    Logger.logInfo("OAuthTokenManager", "New token expiry: ${newTokenData.expiry}")
                } else {
                    Logger.logError("OAuthTokenManager", "Failed to save updated token")
                }
                
                authService.dispose()
                return@withContext saved
                
            } catch (e: Exception) {
                Logger.logError("OAuthTokenManager", "Token refresh failed: ${e.message}")
                authService.dispose()
                return@withContext false
            }
            
        } catch (e: Exception) {
            Logger.logError("OAuthTokenManager", "Error during token refresh: ${e.message}")
            return@withContext false
        }
    }

    /**
     * Perform synchronous token refresh using AppAuth.
     * 
     * @param authService AuthorizationService instance
     * @param tokenRequest TokenRequest for refresh
     * @return TokenResponse if successful, null otherwise
     */
    private fun performTokenRefresh(
        authService: AuthorizationService,
        tokenRequest: TokenRequest
    ): net.openid.appauth.TokenResponse? {
        var result: net.openid.appauth.TokenResponse? = null
        var error: net.openid.appauth.AuthorizationException? = null
        val latch = CountDownLatch(1)
        
        authService.performTokenRequest(tokenRequest) { response, exception ->
            result = response
            error = exception
            latch.countDown()
        }
        
        latch.await()
        
        if (error != null) {
            Logger.logError("OAuthTokenManager", "Token refresh error: ${error!!.message}")
            return null
        }
        
        return result
=======
        Logger.logInfo("OAuthTokenManager", "Starting token refresh")
        GmailTokenBroker.refreshAccessToken(context)
>>>>>>> cf5955ab49e83f742b37cfff8091bf38565c16bf
    }

    /**
     * Ensure token is valid before sync operations.
     * Checks expiry and refreshes if needed.
     *
     * @return true if token is valid or was successfully refreshed, false otherwise
     */
    suspend fun ensureValidToken(): Boolean {
        return try {
            if (!hasToken()) {
                Logger.logInfo("OAuthTokenManager", "No token available")
                return false
            }

            if (isTokenExpired()) {
                Logger.logInfo("OAuthTokenManager", "Token expired, attempting refresh")
                return refreshToken()
            }

            Logger.logInfo("OAuthTokenManager", "Token is valid")
            true
        } catch (e: Exception) {
            Logger.logError("OAuthTokenManager", "Error ensuring valid token: ${e.message}")
            false
        }
    }

    /**
<<<<<<< HEAD
     * Load token data from token.json.
     * 
     * @return TokenData if successful, null otherwise
     */
    private fun loadTokenData(): TokenData? {
        return try {
            if (!tokenFile.exists()) {
                return null
            }
            
            val json = tokenFile.readText()
            gson.fromJson(json, TokenData::class.java)
        } catch (e: Exception) {
            Logger.logError("OAuthTokenManager", "Failed to load token data: ${e.message}")
=======
     * Load OAuth client credentials from credentials.json.
     *
     * @return OAuthCredentials if successful, null otherwise
     */
    fun loadCredentials(): OAuthCredentials? {
        return try {
            if (!credentialsFile.exists()) {
                Logger.logError("OAuthTokenManager", "credentials.json not found")
                return null
            }

            val json = credentialsFile.readText()
            val wrapper = gson.fromJson(json, CredentialsWrapper::class.java)
            wrapper.installed
        } catch (e: Exception) {
            Logger.logError("OAuthTokenManager", "Failed to load credentials: ${e.message}")
>>>>>>> cf5955ab49e83f742b37cfff8091bf38565c16bf
            null
        }
    }

    /**
<<<<<<< HEAD
     * Save token data to token.json in Python backend compatible format.
     * 
     * @param tokenData TokenData to save
     * @return true if successful, false otherwise
     */
    private fun saveTokenData(tokenData: TokenData): Boolean {
        return try {
            if (!configDir.exists()) {
                configDir.mkdirs()
            }
            
            val json = gson.toJson(tokenData)
            tokenFile.writeText(json)
            
            Logger.logInfo("OAuthTokenManager", "Token data saved to ${tokenFile.absolutePath}")
            true
        } catch (e: Exception) {
            Logger.logError("OAuthTokenManager", "Failed to save token data: ${e.message}")
            false
        }
    }

    /**
     * Delete token.json (for disconnect/logout).
     * 
     * @return true if successful or file doesn't exist, false on error
=======
     * Delete all locally stored OAuth tokens without revoking the grant.
     * Used on refresh-failure paths where the grant is already invalid
     * upstream; for a user-initiated disconnect use [disconnect].
     *
     * @return true if successful
>>>>>>> cf5955ab49e83f742b37cfff8091bf38565c16bf
     */
    fun deleteToken(): Boolean {
        return try {
            GmailTokenBroker.clearTokens(context)
            Logger.logInfo("OAuthTokenManager", "Stored tokens cleared")
            true
        } catch (e: Exception) {
            Logger.logError("OAuthTokenManager", "Failed to delete token: ${e.message}")
            false
        }
    }

    /**
<<<<<<< HEAD
     * Save tokens from initial OAuth authorization flow.
     *
     * This method is used by OAuthCallbackActivity to save tokens after
     * the initial authorization code exchange. The Android-type OAuth
     * client has no secret: client_secret is persisted as "" only to keep
     * the token.json shape the Python backend expects.
     *
     * @param tokenResponse TokenResponse from AppAuth
=======
     * Full disconnect for user-initiated "Disconnect Gmail": revokes the
     * grant at Google, then clears all local OAuth state (tokens and any
     * pending authorization request). Runs the network call off the main
     * thread.
     *
     * @return true (local state is always cleared)
     */
    suspend fun disconnect(): Boolean = withContext(Dispatchers.IO) {
        Logger.logInfo("OAuthTokenManager", "Disconnecting Gmail (revoke + local clear)")
        GmailTokenBroker.revokeAndClear(context)
    }

    /**
     * Save tokens from the initial OAuth authorization flow into the
     * encrypted token store.
     *
     * @param tokenResponse TokenResponse from AppAuth
     * @param credentials OAuthCredentials for the token endpoint URI
>>>>>>> cf5955ab49e83f742b37cfff8091bf38565c16bf
     * @return true if successful, false otherwise
     */
    fun saveInitialTokens(tokenResponse: net.openid.appauth.TokenResponse): Boolean {
        return try {
            val expiryTime = if (tokenResponse.accessTokenExpirationTime != null) {
                Instant.ofEpochMilli(tokenResponse.accessTokenExpirationTime!!).toString()
            } else {
                // Default to 1 hour from now if not provided
                Instant.now().plusSeconds(3600).toString()
            }

<<<<<<< HEAD
            // Create token data in Python backend format
            val tokenData = TokenData(
                token = tokenResponse.accessToken ?: "",
                refreshToken = tokenResponse.refreshToken ?: "",
                tokenUri = Constants.OAUTH_TOKEN_URI,
                clientId = Constants.OAUTH_CLIENT_ID,
                clientSecret = "",
                scopes = listOf(Constants.OAUTH_SCOPE),
                expiry = expiryTime
            )

            // Save token data
            val saved = saveTokenData(tokenData)

            if (saved) {
                Logger.logInfo("OAuthTokenManager", "Initial tokens saved successfully")
                Logger.logInfo("OAuthTokenManager", "Token expiry: $expiryTime")
            }

            saved
=======
            GmailTokenBroker.saveTokens(
                context,
                accessToken = tokenResponse.accessToken ?: "",
                refreshToken = tokenResponse.refreshToken ?: "",
                expiryIso = expiryTime,
                tokenUri = credentials.tokenUri
            )

            Logger.logInfo("OAuthTokenManager", "Initial tokens saved successfully")
            Logger.logInfo("OAuthTokenManager", "Token expiry: $expiryTime")
            true
>>>>>>> cf5955ab49e83f742b37cfff8091bf38565c16bf
        } catch (e: Exception) {
            Logger.logError("OAuthTokenManager", "Failed to save initial tokens: ${e.message}")
            false
        }
    }

    /**
<<<<<<< HEAD
     * Data class for token.json format (Python backend compatible).
     *
     * Format matches what google-auth library expects:
     * {
     *   "token": "access_token",
     *   "refresh_token": "refresh_token",
     *   "token_uri": "https://oauth2.googleapis.com/token",
     *   "client_id": "...",
     *   "client_secret": "",
     *   "scopes": ["https://www.googleapis.com/auth/gmail.readonly"],
     *   "expiry": "2024-01-15T10:30:00Z"
     * }
     */
    data class TokenData(
        val token: String,
        @SerializedName("refresh_token")
        val refreshToken: String,
        @SerializedName("token_uri")
        val tokenUri: String,
        @SerializedName("client_id")
        val clientId: String,
        @SerializedName("client_secret")
        val clientSecret: String = "",
        val scopes: List<String>,
        val expiry: String
=======
     * Data classes for parsing credentials.json
     */
    data class CredentialsWrapper(
        val installed: OAuthCredentials
    )

    data class OAuthCredentials(
        @SerializedName("client_id")
        val clientId: String,

        @SerializedName("project_id")
        val projectId: String,

        @SerializedName("auth_uri")
        val authUri: String,

        @SerializedName("token_uri")
        val tokenUri: String,

        @SerializedName("auth_provider_x509_cert_url")
        val authProviderCertUrl: String,

        @SerializedName("client_secret")
        val clientSecret: String,

        @SerializedName("redirect_uris")
        val redirectUris: List<String>
>>>>>>> cf5955ab49e83f742b37cfff8091bf38565c16bf
    )
}
