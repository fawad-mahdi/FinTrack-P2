package com.fintrack.pk.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.fintrack.pk.utils.Logger
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.util.Base64

/**
 * Manages PIN encryption and decryption using Android Keystore
 * Provides hardware-backed encryption when available
 * 
 * Requirements: 5.3
 */
class PinEncryptionManager {

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "fintrack_pin_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
        private const val IV_SEPARATOR = "]"
    }

    private val keyStore: KeyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply {
        load(null)
    }

    init {
        // Generate key if it doesn't exist
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            generateKey()
        }
    }

    /**
     * Generate encryption key in Android Keystore
     * Uses hardware-backed storage when available
     */
    private fun generateKey() {
        try {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                KEYSTORE_PROVIDER
            )

            val keyGenParameterSpec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false) // Don't require biometric for each use
                .build()

            keyGenerator.init(keyGenParameterSpec)
            keyGenerator.generateKey()

            Logger.logInfo("PinEncryptionManager", "Encryption key generated successfully")
        } catch (e: Exception) {
            Logger.logError("PinEncryptionManager", "Error generating encryption key", e)
            throw e
        }
    }

    /**
     * Get the secret key from Keystore
     */
    private fun getSecretKey(): SecretKey {
        return keyStore.getKey(KEY_ALIAS, null) as SecretKey
    }

    /**
     * Encrypt PIN using AES with Keystore key
     * @param pin The PIN to encrypt
     * @return Base64-encoded encrypted PIN with IV
     */
    fun encryptPin(pin: String): String {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())

            val iv = cipher.iv
            val encryptedBytes = cipher.doFinal(pin.toByteArray(Charsets.UTF_8))

            // Combine IV and encrypted data
            val ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)
            val encryptedBase64 = Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)

            Logger.logInfo("PinEncryptionManager", "PIN encrypted successfully")
            return "$ivBase64$IV_SEPARATOR$encryptedBase64"
        } catch (e: Exception) {
            Logger.logError("PinEncryptionManager", "Error encrypting PIN", e)
            throw e
        }
    }

    /**
     * Decrypt PIN using AES with Keystore key
     * @param encryptedPin Base64-encoded encrypted PIN with IV
     * @return Decrypted PIN
     */
    fun decryptPin(encryptedPin: String): String {
        try {
            // Split IV and encrypted data
            val parts = encryptedPin.split(IV_SEPARATOR)
            if (parts.size != 2) {
                throw IllegalArgumentException("Invalid encrypted PIN format")
            }

            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val encryptedBytes = Base64.decode(parts[1], Base64.NO_WRAP)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec)

            val decryptedBytes = cipher.doFinal(encryptedBytes)
            val decryptedPin = String(decryptedBytes, Charsets.UTF_8)

            Logger.logInfo("PinEncryptionManager", "PIN decrypted successfully")
            return decryptedPin
        } catch (e: Exception) {
            Logger.logError("PinEncryptionManager", "Error decrypting PIN", e)
            throw e
        }
    }

    /**
     * Delete the encryption key from Keystore
     * Used when resetting PIN
     */
    fun deleteKey() {
        try {
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS)
                Logger.logInfo("PinEncryptionManager", "Encryption key deleted")
            }
        } catch (e: Exception) {
            Logger.logError("PinEncryptionManager", "Error deleting encryption key", e)
        }
    }
}
