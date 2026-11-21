/*
 * Copyright (c) 2026 KHATAWAS Project
 * Simplified Technical English (ASD STE-100) compliant.
 * Localized Android TextToSpeech engine with thread-safe volume ducking.
 */

package com.khatawas.audio

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.khatawas.logic.Transaction
import java.util.Locale

/**
 * Voice feedback manager using Android native TextToSpeech.
 * Provides localized audio confirmation for ledger transactions.
 * Uses AudioManager to duck media volume during audio playback.
 */
class VoiceFeedback(context: Context) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "VoiceFeedback"
    }

    private val appContext = context.applicationContext ?: context
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var tts: TextToSpeech? = null
    @Volatile
    private var isTtsReady = false

    private var originalVolume: Int = -1
    private var pendingMessage: String? = null

    init {
        // Initialize TextToSpeech engine on the main thread
        if (Looper.myLooper() == Looper.getMainLooper()) {
            initTts()
        } else {
            mainHandler.post { initTts() }
        }
    }

    /**
     * Create the TextToSpeech instance on the main thread.
     */
    private fun initTts() {
        tts = TextToSpeech(appContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val ttsEngine = tts
            if (ttsEngine != null) {
                // Set language to Hindi (India). Fallback to English on missing data or unsupported locale.
                val hindiLocale = Locale("hi", "IN")
                val langResult = ttsEngine.setLanguage(hindiLocale)
                if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "Hindi locale not supported. Fallback to English.")
                    ttsEngine.setLanguage(Locale.ENGLISH)
                } else {
                    Log.i(TAG, "TTS initialized with Hindi (India) locale.")
                }

                // Register listener to restore music volume after speech completes
                ttsEngine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        Log.d(TAG, "TTS playback started: $utteranceId")
                    }

                    override fun onDone(utteranceId: String?) {
                        Log.d(TAG, "TTS playback completed: $utteranceId")
                        restoreOriginalVolume()
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        Log.e(TAG, "TTS playback error for utterance: $utteranceId")
                        restoreOriginalVolume()
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        Log.e(TAG, "TTS playback error code $errorCode for utterance: $utteranceId")
                        restoreOriginalVolume()
                    }
                })

                isTtsReady = true

                // Speak any queued message waiting for engine initialization
                pendingMessage?.let { queuedText ->
                    pendingMessage = null
                    speak(queuedText)
                }
            }
        } else {
            Log.e(TAG, "TextToSpeech initialization failed with status: $status")
        }
    }

    /**
     * Speak confirmation sentence for a ledger transaction.
     * Sentence format: "${transaction.name} ka ${transaction.amount} ${transaction.type} likh liya".
     *
     * Parameter transaction: The ledger transaction data class instance.
     */
    fun speakConfirmation(transaction: Transaction) {
        val spokenText = "${transaction.name} ka ${transaction.amount} ${transaction.type} likh liya"
        Log.i(TAG, "Speaking confirmation: $spokenText")
        speak(spokenText)
    }

    /**
     * Speak a text message with volume ducking.
     * Thread-safe. Dispatches playback to the main thread handler.
     *
     * Parameter text: The text string to speak.
     */
    fun speak(text: String) {
        mainHandler.post {
            if (!isTtsReady) {
                Log.w(TAG, "TTS engine not ready. Message queued: $text")
                pendingMessage = text
                return@post
            }

            // Set media volume to maximum before calling speak
            duckVolumeToMax()

            val utteranceId = "CONFIRM_${System.currentTimeMillis()}"
            try {
                val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                if (result != TextToSpeech.SUCCESS) {
                    Log.e(TAG, "Failed to queue TTS utterance. Restoring volume.")
                    restoreOriginalVolume()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception during tts.speak(): ${e.message}", e)
                restoreOriginalVolume()
            }
        }
    }

    /**
     * Set STREAM_MUSIC volume to maximum.
     * Saves previous volume level to restore after speech playback.
     */
    @Synchronized
    private fun duckVolumeToMax() {
        try {
            if (originalVolume == -1) {
                originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            }
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, 0)
            Log.d(TAG, "Ducked STREAM_MUSIC volume to max ($maxVolume). Original was $originalVolume.")
        } catch (e: Exception) {
            Log.e(TAG, "Error while ducking volume: ${e.message}", e)
        }
    }

    /**
     * Restore STREAM_MUSIC volume to the saved level.
     * Uses try-catch to prevent leaving user volume permanently maxed on errors.
     */
    @Synchronized
    private fun restoreOriginalVolume() {
        try {
            if (originalVolume != -1) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
                Log.d(TAG, "Restored STREAM_MUSIC volume to $originalVolume.")
                originalVolume = -1
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error while restoring volume: ${e.message}", e)
        }
    }

    /**
     * Stop speech playback and release native engine resources.
     */
    fun shutdown() {
        mainHandler.post {
            restoreOriginalVolume()
            try {
                tts?.stop()
                tts?.shutdown()
            } catch (e: Exception) {
                Log.e(TAG, "Error during TTS shutdown: ${e.message}", e)
            } finally {
                tts = null
                isTtsReady = false
            }
        }
    }
}
