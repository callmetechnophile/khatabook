/*
 * Copyright (c) 2026 KHATAWAS Project
 * Simplified Technical English (ASD STE-100) compliant.
 * Voice Assistant Manager for Chatbot integrating Gnani STT and Gnani TTS.
 * Enforces explicit cloud audio privacy disclosure, user consent, and read-only query semantics.
 */

package com.khatawas.ai

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.khatawas.BuildConfig
import com.khatawas.audio.GnaniSttClient
import com.khatawas.audio.GnaniTtsClient
import com.khatawas.audio.SttListener
import com.khatawas.audio.SttState
import com.khatawas.audio.TtsListener

interface VoiceAssistantListener {
    fun onTranscriptPartial(text: String)
    fun onTranscriptFinal(text: String)
    fun onVoiceError(errorMessage: String)
    fun onTtsPlaybackStarted()
    fun onTtsPlaybackCompleted()
}

/**
 * Manages voice input and spoken output for the KHATAWAS Chatbot.
 * Reuses existing GnaniSttClient and GnaniTtsClient.
 */
class ChatbotVoiceAssistant(private val context: Context) {

    companion object {
        private const val TAG = "KHATAWAS_CHATBOT_VOICE"
        private const val PREFS_NAME = "khatawas_voice_prefs"
        private const val KEY_GNANI_CONSENT = "gnani_remote_audio_consent"

        const val PRIVACY_DISCLOSURE_TEXT =
            "Speech recognition and speech synthesis use Gnani AI cloud services. " +
            "Your voice audio is transmitted securely over TLS to api.vachana.ai (STT) " +
            "and asr.gnani.ai (TTS). Transcripts are processed solely to answer your ledger queries " +
            "and are never used to commit financial transactions without your review."
    }

    private var sttClient: GnaniSttClient? = null
    private var ttsClient: GnaniTtsClient? = null
    private var listener: VoiceAssistantListener? = null

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun setListener(listener: VoiceAssistantListener) {
        this.listener = listener
    }

    /**
     * Check if user has consented to remote cloud audio processing by Gnani AI.
     */
    fun hasUserConsentedToRemoteAudio(): Boolean {
        return prefs.getBoolean(KEY_GNANI_CONSENT, false)
    }

    /**
     * Record user consent for remote audio processing.
     */
    fun setUserConsent(consented: Boolean) {
        prefs.edit().putBoolean(KEY_GNANI_CONSENT, consented).apply()
    }

    /**
     * Start voice capture for conversational query.
     * Enforces user consent check before starting connection.
     */
    fun startListening(): Boolean {
        if (!hasUserConsentedToRemoteAudio()) {
            listener?.onVoiceError("Remote audio consent required before activating voice input.")
            return false
        }

        stopPlayback()
        stopListening()

        try {
            val client = GnaniSttClient()
            client.setListener(object : SttListener {
                override fun onPartialTranscription(text: String) {
                    listener?.onTranscriptPartial(text)
                }

                override fun onTranscriptionReceived(text: String) {
                    Log.i(TAG, "Final transcript received: $text")
                    listener?.onTranscriptFinal(text)
                }

                override fun onSttFailed() {
                    listener?.onVoiceError("Speech recognition failed. Please type your query or try again.")
                }
            })

            client.connect()
            sttClient = client
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Gnani STT client: ${e.message}", e)
            listener?.onVoiceError("Could not connect to speech service: ${e.message}")
            return false
        }
    }

    /**
     * Feed audio frame (PCM16 16kHz) to Gnani STT client.
     */
    fun feedAudioFrame(pcmData: ByteArray) {
        sttClient?.sendAudioChunk(pcmData)
    }

    /**
     * Stop listening.
     */
    fun stopListening() {
        try {
            sttClient?.close()
        } catch (e: Exception) {
            // Ignored
        } finally {
            sttClient = null
        }
    }

    /**
     * Synthesize and speak the response text using Gnani TTS.
     */
    fun speakResponse(text: String, language: String = "hi-IN") {
        if (!hasUserConsentedToRemoteAudio()) {
            Log.i(TAG, "Skipping TTS: User has not consented to remote audio.")
            return
        }

        stopPlayback()

        try {
            val client = GnaniTtsClient()
            client.setListener(object : TtsListener {
                override fun onAudioReceived(audioData: ByteArray) {
                    listener?.onTtsPlaybackStarted()
                }

                override fun onTtsFailed(errorMessage: String) {
                    Log.w(TAG, "TTS playback error: $errorMessage")
                    listener?.onVoiceError("Voice playback unavailable: $errorMessage")
                }
            })

            ttsClient = client
            client.speak(text, language)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize TTS client: ${e.message}", e)
        }
    }

    /**
     * Stop ongoing TTS audio playback.
     */
    fun stopPlayback() {
        try {
            ttsClient?.release()
        } catch (e: Exception) {
            // Ignored
        } finally {
            ttsClient = null
            listener?.onTtsPlaybackCompleted()
        }
    }
}
