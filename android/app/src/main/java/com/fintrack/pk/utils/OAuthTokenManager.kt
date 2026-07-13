package com.fintrack.pk.utils

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.TokenRequest
import java.io.File
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.concurrent.CountDownLatch

/**
 * Utility class for managing OAuth tokens for Gmail API access.
 * 
 * This class handles:
 * - Token validation and expiry checking
 * - Token refresh using AppAuth's TokenRequest
 * - Token storage in Python backend compatible format
 * 
 * Task 9.3 Implementation:
 * - Check token expiry before sync operations
 * - Refresh tokens if expired using AppAuth
 * - Store tokens in format compatible with Python's google-auth library
 * 
 * Requirements: 6.2, 6.4
 */
class OAuthTokenManager(private val context: Context) {

    private val gson = Gson()
    private val configDir = File(context.filesDir, Constants.CONFIG_DIR)
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
    }

    /**
     * Check if the current token is expired.
     * 
     * @return true if token is expired or expiry cannot be determined, false if valid
     */
    fun isTokenExpired(): Boolean {
        return try {
            val tokenData = loadTokenData() ?: run {
                Logger.logInfo("OAuthTokenManager", "No token data found, considering expired")
                return true
            }
            
            // Parse expiry timestamp (ISO 8601 format)
            val expiryInstant = try {
                Instant.parse(tokenData.expiry)
            } catch (e: DateTimeParseException) {
                Logger.logError("OAuthTokenManager", "Failed to parse expiry timestamp: ${tokenData.expiry}")
                return true // Consider expired if we can't parse
            }
            
            // Add a 5-minute buffer to refresh before actual expiry
            val bufferSeconds = 300L
            val now = Instant.now()
            val isExpired = now.plusSeconds(bufferSeconds).isAfter(expiryInstant)
            
            Logger.logInfo("OAuthTokenManager", "Token expiry: ${tokenData.expiry}")
            Logger.logInfo("OAuthTokenManager", "Current time: $now")
            Logger.logInfo("OAuthTokenManager", "Token expired: $isExpired")
            
            isExpired
        } catch (e: Exception) {
            Logger.logError("OAuthTokenManager", "Error checking token expiry: ${e.message}")
            true // Consider expired on error
        }
    }

    /**
     * Refresh the OAuth token using the refresh token.
     * 
     * This method uses AppAuth's TokenRequest to refresh the access token
     * and updates token.json with the new token data.
     * 
     * @return true if refresh was successful, false otherwise
     */
    suspend fun refreshToken(): Boolean = withContext(Dispatchers.IO) {
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
            null
        }
    }

    /**
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
     */
    fun deleteToken(): Boolean {
        return try {
            if (tokenFile.exists()) {
                val deleted = tokenFile.delete()
                Logger.logInfo("OAuthTokenManager", "Token file deleted: $deleted")
                deleted
            } else {
                Logger.logInfo("OAuthTokenManager", "Token file does not exist, nothing to delete")
                true
            }
        } catch (e: Exception) {
            Logger.logError("OAuthTokenManager", "Failed to delete token: ${e.message}")
            false
        }
    }

    /**
     * Save tokens from initial OAuth authorization flow.
     *
     * This method is used by OAuthCallbackActivity to save tokens after
     * the initial authorization code exchange. The Android-type OAuth
     * client has no secret: client_secret is persisted as "" only to keep
     * the token.json shape the Python backend expects.
     *
     * @param tokenResponse TokenResponse from AppAuth
     * @return true if successful, false otherwise
     */
    fun saveInitialTokens(tokenResponse: net.openid.appauth.TokenResponse): Boolean {
        return try {
            // Calculate expiry timestamp in ISO 8601 format
            val expiryTime = if (tokenResponse.accessTokenExpirationTime != null) {
                Instant.ofEpochMilli(tokenResponse.accessTokenExpirationTime!!).toString()
            } else {
                // Default to 1 hour from now if not provided
                Instant.now().plusSeconds(3600).toString()
            }

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
        } catch (e: Exception) {
            Logger.logError("OAuthTokenManager", "Failed to save initial tokens: ${e.message}")
            false
        }
    }

    /**
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
    )
}
