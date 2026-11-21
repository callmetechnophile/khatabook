/*
 * Copyright (c) 2026 KHATAWAS Project
 * Simplified Technical English (ASD STE-100) compliant.
 * Native Android TextToSpeech engine wrapper with volume ducking.
 */

package com.khatawas.audio

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.khatawas.logic.Transaction
import java.util.Locale

/**
 * Audio feedback manager using Android native TextToSpeech.
 * Provides voice confirmation for Kirana store ledger operations.
 * Manages audio volume ducking to ensure clear voice output in loud environments.
 */
class VoiceFeedback(context: Context) : TextToSpeech.OnInitListener {

    private val appContext = context.applicationContext ?: context
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var tts: TextToSpeech? = null
    @Volatile
    private var isTtsReady = false

    private var originalVolume: Int = -1
    private var pendingUtterance: String? = null

    init {
        // Ensure TextToSpeech initialization runs on the Android main thread
        if (Looper.myLooper() == Looper.getMainLooper()) {
            initTts()
        } else {
            mainHandler.post { initTts() }
        }
    }

    /**
     * Initialize TextToSpeech engine on the main thread.
     */
    private fun initTts() {
        tts = TextToSpeech(appContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val ttsEngine = tts
            if (ttsEngine != null) {
                // Set Hindi language if available, fallback to default locale
                val hindiLocale = Locale("hi", "IN")
                val langResult = ttsEngine.setLanguage(hindiLocale)
                if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    ttsEngine.language = Locale.getDefault()
                }

                // Register listener to restore music volume when speech finishes
                ttsEngine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        // Speech playback started
                    }

                    override fun onDone(utteranceId: String?) {
                        restoreVolume()
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        restoreVolume()
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        restoreVolume()
                    }
                })

                isTtsReady = true

                // Speak any message queued before engine initialization completed
                pendingUtterance?.let { queuedText ->
                    pendingUtterance = null
                    speak(queuedText)
                }
            }
        }
    }

    /**
     * Speak confirmation for a stored ledger transaction.
     * Constructs natural Hindi sentence from customer name, amount, and type.
     * Can be called safely from background audio processing threads.
     *
     * Parameter transaction: Stored ledger transaction data class.
     */
    fun speakConfirmation(transaction: Transaction) {
        // Map transaction type enum to code-mixed speech word
        val typeSpeechWord = when (transaction.type.uppercase(Locale.ROOT)) {
            "CREDIT" -> "udhaar"
            "DEBIT" -> "jama"
            else -> transaction.type.lowercase(Locale.ROOT)
        }

        // Construct exact confirmation sentence
        val confirmationMessage = "${transaction.name} ka ${transaction.amount} $typeSpeechWord likh liya"
        speak(confirmationMessage)
    }

    /**
     * Speak text with automatic volume ducking.
     * Can be invoked from any thread.
     *
     * Parameter text: The text message to speak.
     */
    fun speak(text: String) {
        mainHandler.post {
            if (!isTtsReady) {
                pendingUtterance = text
                return@post
            }

            // Set media volume to maximum before speaking
            duckVolumeToMax()

            val utteranceId = "CONFIRM_${System.currentTimeMillis()}"
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        }
    }

    /**
     * Increase STREAM_MUSIC volume to maximum.
     * Stores original volume to restore after speech completes.
     */
    @Synchronized
    private fun duckVolumeToMax() {
        try {
            if (originalVolume == -1) {
                originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            }
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, 0)
        } catch (_: Exception) {
            // Protect against platform audio service errors
        }
    }

    /**
     * Restore STREAM_MUSIC volume to the original level.
     */
    @Synchronized
    private fun restoreVolume() {
        try {
            if (originalVolume != -1) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
                originalVolume = -1
            }
        } catch (_: Exception) {
            // Protect against platform audio service errors
        }
    }

    /**
     * Stop speech and free engine resources.
     */
    fun shutdown() {
        mainHandler.post {
            restoreVolume()
            tts?.stop()
            tts?.shutdown()
            tts = null
            isTtsReady = false
        }
    }
}
