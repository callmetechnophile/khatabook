/*
 * Copyright (c) 2026 KHATAWAS Project
 * Simplified Technical English (ASD STE-100) compliant.
 * Privacy-first state management, financial data masking, and user-configured administrator PIN.
 */

package com.khatawas.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

object PrivacyManager {

    const val MASK_VALUE = "###XXX"
    const val ACCESSIBLE_AMOUNT_HIDDEN = "Amount hidden"
    const val ACCESSIBLE_BALANCE_HIDDEN = "Balance hidden"
    const val ACCESSIBLE_NAME_HIDDEN = "Customer name hidden"
    const val ACCESSIBLE_DETAILS_HIDDEN = "Financial details hidden"

    private const val PREFS_NAME = "khatawas_privacy_prefs"
    private const val KEY_PIN_HASH = "admin_pin_hash"
    private const val KEY_PIN_SALT = "admin_pin_salt"
    private const val KEY_MASK_CUSTOMER_NAMES = "mask_customer_names"
    private const val KEY_INACTIVITY_TIMEOUT_MS = "inactivity_timeout_ms"

    const val DEFAULT_INACTIVITY_TIMEOUT_MS = 60_000L // 60 seconds

    // Memory-only session state. Always starts LOCKED.
    @Volatile
    var isUnlocked: Boolean = false
        private set

    /**
     * Unlocks the current session.
     */
    fun unlockSession() {
        isUnlocked = true
    }

    /**
     * Locks the current session and masks all financial data.
     */
    fun lockSession() {
        isUnlocked = false
    }

    /**
     * Returns true if the user has configured an administrator PIN.
     * Enforces the rule: "Do not invent a default administrator PIN."
     */
    fun hasConfiguredPin(context: Context): Boolean {
        val prefs = getPrefs(context)
        return prefs.contains(KEY_PIN_HASH) && prefs.contains(KEY_PIN_SALT)
    }

    /**
     * Checks if a PIN satisfies complexity requirements (4 to 8 numeric digits).
     */
    fun isValidPinFormat(pin: String): Boolean {
        val trimmed = pin.trim()
        return trimmed.length in 4..8 && trimmed.all { it.isDigit() }
    }

    /**
     * Configures a user-created administrator PIN.
     * Hashes the PIN with SHA-256 and a cryptographically secure random salt.
     *
     * @param context Android context
     * @param pin User-entered PIN (must be 4 to 8 digits)
     * @return true if successfully saved, false if invalid
     */
    fun configurePin(context: Context, pin: String): Boolean {
        val trimmed = pin.trim()
        if (!isValidPinFormat(trimmed)) {
            return false
        }

        val saltBytes = ByteArray(16)
        SecureRandom().nextBytes(saltBytes)
        val saltBase64 = encodeBase64(saltBytes)

        val hashBase64 = hashPin(trimmed, saltBytes)

        getPrefs(context).edit()
            .putString(KEY_PIN_HASH, hashBase64)
            .putString(KEY_PIN_SALT, saltBase64)
            .apply()

        return true
    }

    /**
     * Verifies a user-entered administrator PIN against the stored salted hash.
     */
    fun verifyPin(context: Context, pin: String): Boolean {
        val prefs = getPrefs(context)
        val storedHash = prefs.getString(KEY_PIN_HASH, null) ?: return false
        val storedSaltBase64 = prefs.getString(KEY_PIN_SALT, null) ?: return false

        val saltBytes = decodeBase64(storedSaltBase64)
        val computedHash = hashPin(pin.trim(), saltBytes)

        val matches = MessageDigest.isEqual(
            decodeBase64(storedHash),
            decodeBase64(computedHash)
        )

        if (matches) {
            unlockSession()
        }
        return matches
    }

    /**
     * Masks financial amounts using the fixed format `###XXX` when locked.
     */
    fun maskAmount(amountFormatted: String): String {
        return if (isUnlocked) amountFormatted else MASK_VALUE
    }

    /**
     * Masks customer names when locked if name masking is enabled.
     */
    fun maskCustomerName(context: Context, customerName: String): String {
        if (isUnlocked) return customerName
        val maskNames = isMaskCustomerNamesEnabled(context)
        return if (maskNames) MASK_VALUE else customerName
    }

    /**
     * Masks sensitive payment / bank references.
     */
    fun maskPaymentReference(reference: String): String {
        return if (isUnlocked) reference else MASK_VALUE
    }

    /**
     * Returns accessible content description for an amount.
     */
    fun getAmountContentDescription(amountFormatted: String): String {
        return if (isUnlocked) amountFormatted else ACCESSIBLE_AMOUNT_HIDDEN
    }

    /**
     * Returns accessible content description for a balance.
     */
    fun getBalanceContentDescription(balanceFormatted: String): String {
        return if (isUnlocked) balanceFormatted else ACCESSIBLE_BALANCE_HIDDEN
    }

    /**
     * Returns accessible content description for a customer name.
     */
    fun getCustomerNameContentDescription(context: Context, customerName: String): String {
        if (isUnlocked) return customerName
        val maskNames = isMaskCustomerNamesEnabled(context)
        return if (maskNames) ACCESSIBLE_NAME_HIDDEN else customerName
    }

    fun isMaskCustomerNamesEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_MASK_CUSTOMER_NAMES, true)
    }

    fun setMaskCustomerNamesEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_MASK_CUSTOMER_NAMES, enabled).apply()
    }

    fun getInactivityTimeoutMs(context: Context): Long {
        return getPrefs(context).getLong(KEY_INACTIVITY_TIMEOUT_MS, DEFAULT_INACTIVITY_TIMEOUT_MS)
    }

    fun setInactivityTimeoutMs(context: Context, timeoutMs: Long) {
        getPrefs(context).edit().putLong(KEY_INACTIVITY_TIMEOUT_MS, timeoutMs).apply()
    }

    private fun hashPin(pin: String, salt: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt)
        val digest = md.digest(pin.toByteArray(Charsets.UTF_8))
        return encodeBase64(digest)
    }

    private fun encodeBase64(bytes: ByteArray): String {
        return try {
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Throwable) {
            java.util.Base64.getEncoder().encodeToString(bytes)
        }
    }

    private fun decodeBase64(str: String): ByteArray {
        return try {
            Base64.decode(str, Base64.NO_WRAP)
        } catch (e: Throwable) {
            java.util.Base64.getDecoder().decode(str)
        }
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
