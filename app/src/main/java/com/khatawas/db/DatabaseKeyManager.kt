/*
 * Copyright (c) 2026 KHATAWAS Project
 * Simplified Technical English (ASD STE-100) compliant.
 * Cryptographic key manager for SQLCipher database encryption.
 * Enforces fail-closed security: never falls back to plain text key storage.
 */

package com.khatawas.db

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

/**
 * Manages database encryption keys using Android Keystore and EncryptedSharedPreferences.
 * Does not store encryption passwords in plain text.
 * Fails closed if hardware-backed cryptographic storage cannot be initialized.
 */
object DatabaseKeyManager {

    private const val PREFS_FILE = "khatawas_secure_vault"
    private const val KEY_ALIAS_DB_SECRET = "db_encryption_key_256"
    private const val KEY_SIZE_BYTES = 32 // 256 bits

    @Volatile
    private var cachedPassphrase: ByteArray? = null

    /**
     * Clear cached passphrase in memory. Visible for testing.
     */
    @Synchronized
    fun clearCacheForTesting() {
        cachedPassphrase = null
    }

    /**
     * Retrieve the 256-bit database passphrase.
     * Generates and securely stores a new key if no key exists.
     * Fails closed if EncryptedSharedPreferences cannot be initialized.
     *
     * @param context Android application context.
     * @return 256-bit encryption key as a byte array.
     * @throws SecurityException If hardware keystore or encrypted storage fails.
     */
    @Synchronized
    fun getOrCreatePassphrase(context: Context): ByteArray {
        cachedPassphrase?.let { return it }

        val appContext = context.applicationContext ?: context

        val securePrefs = try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                appContext,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Strict fail-closed rule: never fall back to plaintext unencrypted storage
            throw SecurityException(
                "Hardware Keystore / EncryptedSharedPreferences initialization failed. " +
                        "Plaintext fallback is prohibited for financial ledger security.", e
            )
        }

        var keyHex = securePrefs.getString(KEY_ALIAS_DB_SECRET, null)
        if (keyHex == null) {
            // Strict fail-closed rule: if khatawas.db exists on disk, do NOT generate a new random key!
            // Generating a new key would orphan existing encrypted data and cause unrecoverable data loss.
            val dbFile = appContext.getDatabasePath("khatawas.db")
            if (dbFile != null && dbFile.exists() && dbFile.length() > 0) {
                throw SecurityException(
                    "Database file exists at ${dbFile.absolutePath} (${dbFile.length()} bytes) but Keystore decryption key is missing. " +
                            "Refusing to generate a new key to prevent permanent data loss or database wipe."
                )
            }

            // Generate a 256-bit cryptographically secure random key for brand new database
            val randomBytes = ByteArray(KEY_SIZE_BYTES)
            SecureRandom().nextBytes(randomBytes)
            keyHex = bytesToHex(randomBytes)

            securePrefs.edit()
                .putString(KEY_ALIAS_DB_SECRET, keyHex)
                .apply()
        }

        val passphrase = hexToBytes(keyHex)
        cachedPassphrase = passphrase
        return passphrase
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val hexChars = "0123456789abcdef"
        val result = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val i = b.toInt() and 0xFF
            result.append(hexChars[i shr 4])
            result.append(hexChars[i and 0x0F])
        }
        return result.toString()
    }

    private fun hexToBytes(hex: String): ByteArray {
        val result = ByteArray(hex.length / 2)
        for (i in result.indices) {
            val index = i * 2
            val byteVal = hex.substring(index, index + 2).toInt(16)
            result[i] = byteVal.toByte()
        }
        return result
    }
}
