/*
 * Copyright (c) 2026 KHATAWAS Project
 * Simplified Technical English (ASD STE-100) compliant.
 * Android BiometricPrompt integration supporting fingerprint, face, and device credentials.
 * Raw biometric data is handled exclusively by the Android OS and hardware keystore.
 */

package com.khatawas.auth

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat

object BiometricAuthHelper {

    /**
     * Checks if biometric hardware (fingerprint or face) is available and enrolled.
     */
    fun canAuthenticateWithBiometrics(context: Context): Boolean {
        val biometricManager = BiometricManager.from(context)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.BIOMETRIC_WEAK
        return biometricManager.canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS
    }

    /**
     * Checks if device credentials (screen lock PIN, pattern, or password) are available.
     */
    fun canAuthenticateWithDeviceCredentials(context: Context): Boolean {
        val biometricManager = BiometricManager.from(context)
        val authenticators = BiometricManager.Authenticators.DEVICE_CREDENTIAL
        return biometricManager.canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS
    }

    /**
     * Launches Android BiometricPrompt for fingerprint, face, or device credential authentication.
     */
    fun authenticate(
        activity: AppCompatActivity,
        title: String = "Unlock KHATAWAS",
        subtitle: String = "Verify fingerprint, face, or screen lock to view ledger values",
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
        onUsePinFallback: () -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(activity)

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                PrivacyManager.unlockSession()
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    errorCode == BiometricPrompt.ERROR_USER_CANCELED
                ) {
                    onUsePinFallback()
                } else {
                    onError(errString.toString())
                }
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                // Individual attempt failed (e.g. unrecognized fingerprint). Prompt remains active.
            }
        }

        val biometricPrompt = BiometricPrompt(activity, executor, callback)

        val promptInfoBuilder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)

        // If device credential fallback is supported, allow it; otherwise provide PIN button
        val canDeviceCred = canAuthenticateWithDeviceCredentials(activity)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && canDeviceCred) {
            promptInfoBuilder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
        } else {
            promptInfoBuilder.setNegativeButtonText("Use App PIN")
        }

        try {
            biometricPrompt.authenticate(promptInfoBuilder.build())
        } catch (e: Exception) {
            // Fallback immediately to user PIN
            onUsePinFallback()
        }
    }
}
