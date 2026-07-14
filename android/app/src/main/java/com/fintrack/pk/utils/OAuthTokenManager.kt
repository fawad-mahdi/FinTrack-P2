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
    private val credentialsFile = File(configDir, Constants.CREDENTIALS_FILE)

    /**
     * Check if an OAuth token exists in the encrypted store.
     */
    fun hasToken(): Boolean {
        val hasToken = GmailTokenBroker.hasToken(context)
        Logger.logInfo("OAuthTokenManager", "Token exists: $hasToken")
        return hasToken
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
        Logger.logInfo("OAuthTokenManager", "Starting token refresh")
        GmailTokenBroker.refreshAccessToken(context)
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
            null
        }
    }

    /**
     * Delete all stored OAuth tokens (for disconnect/logout).
     *
     * @return true if successful
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
     * Save tokens from the initial OAuth authorization flow into the
     * encrypted token store.
     *
     * @param tokenResponse TokenResponse from AppAuth
     * @param credentials OAuthCredentials for the token endpoint URI
     * @return true if successful, false otherwise
     */
    fun saveInitialTokens(
        tokenResponse: net.openid.appauth.TokenResponse,
        credentials: OAuthCredentials
    ): Boolean {
        return try {
            val expiryTime = if (tokenResponse.accessTokenExpirationTime != null) {
                Instant.ofEpochMilli(tokenResponse.accessTokenExpirationTime!!).toString()
            } else {
                // Default to 1 hour from now if not provided
                Instant.now().plusSeconds(3600).toString()
            }

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
        } catch (e: Exception) {
            Logger.logError("OAuthTokenManager", "Failed to save initial tokens: ${e.message}")
            false
        }
    }

    /**
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
    )
}
