package com.fintrack.pk.utils

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.JsonParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant

/**
 * Single source of truth for Gmail OAuth tokens on Android.
 *
 * Tokens (access + refresh) are stored in EncryptedSharedPreferences backed
 * by an Android Keystore master key. The refresh token and the OAuth client
 * secret are never written to plaintext files and are never exposed to the
 * Python layer: Python obtains only a short-lived access token by calling
 * [getAccessToken] through the Chaquopy Java bridge.
 *
 * A legacy plaintext config/token.json (written by older app versions) is
 * migrated into encrypted storage and deleted on first access.
 */
object GmailTokenBroker {

    private const val COMPONENT_NAME = "GmailTokenBroker"
    private const val PREFS_FILE = "gmail_oauth_tokens"

    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_EXPIRY = "expiry"
    private const val KEY_TOKEN_URI = "token_uri"

    private const val KEY_PENDING_AUTH_REQUEST = "pending_auth_request"
    private const val KEY_PENDING_AUTH_CREATED_AT = "pending_auth_created_at"

    /** A pending authorization request older than this is rejected. */
    private const val PENDING_AUTH_MAX_AGE_MS = 10 * 60 * 1000L

    private const val DEFAULT_TOKEN_URI = "https://oauth2.googleapis.com/token"

    /** Refresh this many seconds before the actual expiry. */
    private const val EXPIRY_BUFFER_SECONDS = 300L

    private const val REFRESH_TIMEOUT_MS = 15000

    @Volatile
    private var cachedPrefs: SharedPreferences? = null

    @Synchronized
    private fun prefs(context: Context): SharedPreferences {
        cachedPrefs?.let { return it }

        val appContext = context.applicationContext
        val created = try {
            createEncryptedPrefs(appContext)
        } catch (e: Exception) {
            // Keystore/pref-file corruption: drop the stored tokens (they are
            // unrecoverable without the key) and start fresh. Never fall back
            // to plaintext storage.
            Logger.logError(COMPONENT_NAME, "Encrypted prefs unreadable, resetting token store: ${e.message}")
            appContext.deleteSharedPreferences(PREFS_FILE)
            createEncryptedPrefs(appContext)
        }
        cachedPrefs = created
        return created
    }

    private fun createEncryptedPrefs(appContext: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            appContext,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * Store tokens after an authorization or refresh exchange.
     * Pass an empty [refreshToken] to keep the one already stored.
     */
    @Synchronized
    fun saveTokens(
        context: Context,
        accessToken: String,
        refreshToken: String,
        expiryIso: String,
        tokenUri: String = DEFAULT_TOKEN_URI
    ) {
        val editor = prefs(context).edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_EXPIRY, expiryIso)
            .putString(KEY_TOKEN_URI, tokenUri.ifEmpty { DEFAULT_TOKEN_URI })
        if (refreshToken.isNotEmpty()) {
            editor.putString(KEY_REFRESH_TOKEN, refreshToken)
        }
        editor.apply()
        Logger.logInfo(COMPONENT_NAME, "Tokens saved to encrypted storage (expiry: $expiryIso)")
    }

    fun hasToken(context: Context): Boolean {
        migrateLegacyTokenFile(context)
        return try {
            !prefs(context).getString(KEY_ACCESS_TOKEN, null).isNullOrEmpty()
        } catch (e: Exception) {
            Logger.logError(COMPONENT_NAME, "Error checking token existence: ${e.message}")
            false
        }
    }

    fun isTokenExpired(context: Context): Boolean {
        migrateLegacyTokenFile(context)
        return try {
            val expiryIso = prefs(context).getString(KEY_EXPIRY, null) ?: return true
            val expiry = parseExpiry(expiryIso) ?: return true
            Instant.now().plusSeconds(EXPIRY_BUFFER_SECONDS).isAfter(expiry)
        } catch (e: Exception) {
            Logger.logError(COMPONENT_NAME, "Error checking token expiry: ${e.message}")
            true
        }
    }

    /** Remove all stored OAuth material (disconnect/logout). */
    @Synchronized
    fun clearTokens(context: Context) {
        try {
            prefs(context).edit().clear().apply()
        } catch (e: Exception) {
            Logger.logError(COMPONENT_NAME, "Error clearing token store: ${e.message}")
        }
        deleteLegacyArtifacts(context)
        Logger.logInfo(COMPONENT_NAME, "Token store cleared")
    }

    /**
     * Return a currently valid access token, refreshing it first if needed.
     *
     * This is the only token entry point for the embedded Python server
     * (called via the Chaquopy bridge), so Python never sees the refresh
     * token or the client secret. Returns null when the user is not
     * connected or the refresh failed.
     */
    @JvmStatic
    @Synchronized
    fun getAccessToken(context: Context): String? {
        return try {
            migrateLegacyTokenFile(context)
            val p = prefs(context)
            val accessToken = p.getString(KEY_ACCESS_TOKEN, null)
            if (accessToken.isNullOrEmpty()) {
                Logger.logInfo(COMPONENT_NAME, "No access token stored")
                return null
            }
            if (!isTokenExpired(context)) {
                return accessToken
            }
            Logger.logInfo(COMPONENT_NAME, "Access token expired, refreshing")
            if (refreshAccessToken(context)) {
                prefs(context).getString(KEY_ACCESS_TOKEN, null)
            } else {
                null
            }
        } catch (e: Exception) {
            Logger.logError(COMPONENT_NAME, "getAccessToken failed: ${e.message}")
            null
        }
    }

    /**
     * Refresh the access token using the stored refresh token.
     * Blocking; must be called off the main thread.
     *
     * @return true if a new access token was stored
     */
    @Synchronized
    fun refreshAccessToken(context: Context): Boolean {
        return try {
            val p = prefs(context)
            val refreshToken = p.getString(KEY_REFRESH_TOKEN, null)
            if (refreshToken.isNullOrEmpty()) {
                Logger.logError(COMPONENT_NAME, "No refresh token available")
                return false
            }
            val credentials = loadClientCredentials(context)
            if (credentials == null) {
                Logger.logError(COMPONENT_NAME, "No client credentials available for refresh")
                return false
            }
            val (clientId, clientSecret) = credentials
            val tokenUri = p.getString(KEY_TOKEN_URI, DEFAULT_TOKEN_URI) ?: DEFAULT_TOKEN_URI

            val form = mapOf(
                "client_id" to clientId,
                "client_secret" to clientSecret,
                "refresh_token" to refreshToken,
                "grant_type" to "refresh_token"
            )
            val response = postForm(context, tokenUri, form) ?: return false

            val json = JsonParser.parseString(response).asJsonObject
            val newAccessToken = json.get("access_token")?.asString
            if (newAccessToken.isNullOrEmpty()) {
                Logger.logError(COMPONENT_NAME, "Refresh response contained no access token")
                return false
            }
            val expiresIn = json.get("expires_in")?.asLong ?: 3600L
            val newRefreshToken = json.get("refresh_token")?.asString ?: ""
            saveTokens(
                context,
                accessToken = newAccessToken,
                refreshToken = newRefreshToken,
                expiryIso = Instant.now().plusSeconds(expiresIn).toString(),
                tokenUri = tokenUri
            )
            Logger.logInfo(COMPONENT_NAME, "Access token refreshed")
            true
        } catch (e: Exception) {
            Logger.logError(COMPONENT_NAME, "Token refresh failed: ${e.message}")
            false
        }
    }

    /**
     * Persist the serialized in-flight AuthorizationRequest so the exported
     * OAuth callback can bind an incoming redirect (state + PKCE verifier)
     * to a request this app actually initiated (AUTH-06). Stored in the
     * same Keystore-backed encrypted prefs as the tokens.
     */
    @Synchronized
    fun savePendingAuthRequest(context: Context, authRequestJson: String) {
        prefs(context).edit()
            .putString(KEY_PENDING_AUTH_REQUEST, authRequestJson)
            .putLong(KEY_PENDING_AUTH_CREATED_AT, System.currentTimeMillis())
            .apply()
        Logger.logInfo(COMPONENT_NAME, "Pending authorization request persisted")
    }

    /**
     * One-shot retrieval of the pending AuthorizationRequest: the stored
     * value is removed before being returned, so a redirect can only be
     * matched once (no replay). Returns null when there is no pending
     * request or it has expired.
     */
    @Synchronized
    fun consumePendingAuthRequest(context: Context): String? {
        return try {
            val p = prefs(context)
            val json = p.getString(KEY_PENDING_AUTH_REQUEST, null)
            val createdAt = p.getLong(KEY_PENDING_AUTH_CREATED_AT, 0L)
            p.edit()
                .remove(KEY_PENDING_AUTH_REQUEST)
                .remove(KEY_PENDING_AUTH_CREATED_AT)
                .apply()
            when {
                json == null -> null
                System.currentTimeMillis() - createdAt > PENDING_AUTH_MAX_AGE_MS -> {
                    Logger.logError(COMPONENT_NAME, "Pending authorization request expired, rejecting")
                    null
                }
                else -> json
            }
        } catch (e: Exception) {
            Logger.logError(COMPONENT_NAME, "Failed to read pending authorization request: ${e.message}")
            null
        }
    }

    /**
     * Load client_id/client_secret from config/credentials.json.
     * The client document stays on disk (it identifies the app, not the
     * user), but it is excluded from backups and never copied into the
     * token store.
     */
    private fun loadClientCredentials(context: Context): Pair<String, String>? {
        return try {
            val credentialsFile = File(
                File(context.filesDir, Constants.CONFIG_DIR),
                Constants.CREDENTIALS_FILE
            )
            if (!credentialsFile.exists()) {
                return null
            }
            val root = JsonParser.parseString(credentialsFile.readText()).asJsonObject
            val installed = root.getAsJsonObject("installed") ?: root.getAsJsonObject("web") ?: return null
            val clientId = installed.get("client_id")?.asString ?: return null
            val clientSecret = installed.get("client_secret")?.asString ?: ""
            Pair(clientId, clientSecret)
        } catch (e: Exception) {
            Logger.logError(COMPONENT_NAME, "Failed to load client credentials: ${e.message}")
            null
        }
    }

    /**
     * POST a form to [urlString], routing through the active network so DNS
     * resolution also works when invoked from Chaquopy (Python) threads on
     * Android 14+.
     */
    private fun postForm(context: Context, urlString: String, form: Map<String, String>): String? {
        val body = form.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        val url = URL(urlString)
        val connection = try {
            val cm = context.applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.activeNetwork?.openConnection(url) ?: url.openConnection()
        } catch (e: Exception) {
            url.openConnection()
        } as HttpURLConnection

        return try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.doOutput = true
            connection.connectTimeout = REFRESH_TIMEOUT_MS
            connection.readTimeout = REFRESH_TIMEOUT_MS
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val responseCode = connection.responseCode
            if (responseCode >= 400) {
                // Do not log the response body: token endpoint errors can
                // carry sensitive account/technical context.
                Logger.logError(COMPONENT_NAME, "Token endpoint returned HTTP $responseCode")
                null
            } else {
                connection.inputStream.bufferedReader().readText()
            }
        } catch (e: Exception) {
            Logger.logError(COMPONENT_NAME, "Token endpoint request failed: ${e.message}")
            null
        } finally {
            connection.disconnect()
        }
    }

    /**
     * One-time migration: import a legacy plaintext config/token.json into
     * encrypted storage, then delete it together with stale OAuth artifacts.
     */
    @Synchronized
    fun migrateLegacyTokenFile(context: Context) {
        try {
            val tokenFile = File(File(context.filesDir, Constants.CONFIG_DIR), Constants.TOKEN_FILE)
            if (!tokenFile.exists()) {
                return
            }
            Logger.logInfo(COMPONENT_NAME, "Migrating legacy plaintext token.json to encrypted storage")
            try {
                val json = JsonParser.parseString(tokenFile.readText()).asJsonObject
                val accessToken = json.get("token")?.asString ?: ""
                if (accessToken.isNotEmpty()) {
                    saveTokens(
                        context,
                        accessToken = accessToken,
                        refreshToken = json.get("refresh_token")?.asString ?: "",
                        expiryIso = json.get("expiry")?.asString ?: "",
                        tokenUri = json.get("token_uri")?.asString ?: DEFAULT_TOKEN_URI
                    )
                }
            } catch (e: Exception) {
                Logger.logError(COMPONENT_NAME, "Legacy token.json unreadable, discarding: ${e.message}")
            }
            deleteLegacyArtifacts(context)
        } catch (e: Exception) {
            Logger.logError(COMPONENT_NAME, "Legacy token migration failed: ${e.message}")
        }
    }

    private fun deleteLegacyArtifacts(context: Context) {
        val configDir = File(context.filesDir, Constants.CONFIG_DIR)
        listOf(Constants.TOKEN_FILE, ".oauth_state.json", ".oauth_pending.json").forEach { name ->
            val file = File(configDir, name)
            if (file.exists() && file.delete()) {
                Logger.logInfo(COMPONENT_NAME, "Deleted legacy OAuth artifact: $name")
            }
        }
    }

    private fun parseExpiry(expiryIso: String): Instant? {
        if (expiryIso.isEmpty()) return null
        return try {
            Instant.parse(expiryIso)
        } catch (e: Exception) {
            // Legacy format without zone/fraction, e.g. 2024-01-15T10:30:00
            try {
                Instant.parse(expiryIso + "Z")
            } catch (e2: Exception) {
                Logger.logError(COMPONENT_NAME, "Unparseable token expiry: $expiryIso")
                null
            }
        }
    }
}
