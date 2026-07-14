package com.fintrack.pk.utils

import java.security.SecureRandom

/**
 * Per-launch capability token protecting the embedded FastAPI server.
 *
 * The token is generated once per app process from SecureRandom (256 bits)
 * and handed to exactly two parties:
 *  - the Python server process via the FINTRACK_API_TOKEN environment value
 *    (ServerProcessManager), which requires it on every /api/* request
 *  - the WebView frontend via the JavaScript bridge (WebViewManager), which
 *    attaches it as the X-API-Token header
 *
 * It is never persisted, so API access dies with the process. This replaces
 * the previous defaultable PIN ("1234") and process-global session flag as
 * the local API authorization authority; user-facing locking is handled by
 * the native Keystore-backed PIN screen.
 */
object ApiTokenProvider {

    val token: String by lazy {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        bytes.joinToString("") { "%02x".format(it) }
    }
}
