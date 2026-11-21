/*
 * Copyright (c) 2026 KHATAWAS Project
 * Simplified Technical English (ASD STE-100) compliant.
 * Real Gnani STT streaming client using Java-WebSocket for Android API 24-30 compatibility.
 */

package com.khatawas.audio

import android.util.Log
import com.khatawas.BuildConfig
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Interface to receive speech transcription and error events.
 */
interface SttListener {
    fun onTranscriptionReceived(text: String)
    fun onSttFailed()
}

/**
 * Gnani STT streaming client extending Java-WebSocket WebSocketClient.
 * Compatible with Android API 24 through 30.
 * Operates exclusively on background threads to prevent UI and audio loop contention.
 */
class GnaniSttClient(
    endpointUri: URI = URI(DEFAULT_ENDPOINT),
    headers: Map<String, String> = createAuthHeaders()
) : WebSocketClient(endpointUri, headers) {

    companion object {
        private const val TAG = "KHATAWAS_AUDIO"
        const val DEFAULT_ENDPOINT = "wss://asr.gnani.ai/v1/streaming"

        private fun createAuthHeaders(): Map<String, String> {
            val headers = HashMap<String, String>()
            val apiKey = BuildConfig.GNANI_API_KEY
            if (apiKey.isNotEmpty()) {
                headers["Authorization"] = "Bearer $apiKey"
                headers["token"] = apiKey
            }
            return headers
        }
    }

    private var listener: SttListener? = null
    var fallbackTts: (() -> Unit)? = null

    private val workerExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    fun setListener(listener: SttListener) {
        this.listener = listener
    }

    /**
     * Connect to the WebSocket on a dedicated background thread.
     */
    fun connectOnBackgroundThread() {
        workerExecutor.execute {
            try {
                if (!isOpen) {
                    connect()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Connection error: ${e.message}", e)
                handleFailure()
            }
        }
    }

    override fun onOpen(handshakedata: ServerHandshake?) {
        Log.i(TAG, "Gnani STT WebSocket opened")
        val key = BuildConfig.GNANI_API_KEY
        if (key.isNotEmpty()) {
            try {
                send("""{"token":"$key","format":"pcm16","rate":16000}""")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send auth frame: ${e.message}", e)
            }
        }
    }

    override fun onMessage(message: String) {
        Log.i(TAG, "STT text received: $message")
        val transcript = extractTranscript(message)
        if (transcript.isNotEmpty()) {
            listener?.onTranscriptionReceived(transcript)
        }
    }

    override fun onClose(code: Int, reason: String?, remote: Boolean) {
        Log.i(TAG, "Gnani STT WebSocket closed. Code: $code, Reason: $reason")
    }

    override fun onError(ex: Exception?) {
        Log.e(TAG, "Gnani STT WebSocket error: ${ex?.message}", ex)
        handleFailure()
    }

    /**
     * Send 16kHz PCM audio chunk to the WebSocket as binary data.
     * Parameter pcm16k: 16-bit PCM byte array.
     */
    fun sendAudioChunk(pcm16k: ByteArray) {
        if (!isOpen) {
            return
        }
        try {
            send(pcm16k)
            Log.i(TAG, "STT chunk sent")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send audio chunk: ${e.message}", e)
        }
    }

    private fun handleFailure() {
        listener?.onSttFailed()
        fallbackTts?.invoke()
    }

    /**
     * Extract transcript from JSON payload using Regex to avoid heavy JSON dependencies.
     */
    private fun extractTranscript(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            val match = Regex("\"(?:transcript|text|result|asr_output)\"\\s*:\\s*\"([^\"]+)\"").find(trimmed)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        return trimmed
    }

    /**
     * Release background executor and close socket.
     */
    fun release() {
        workerExecutor.execute {
            try {
                if (isOpen) {
                    close()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during close: ${e.message}", e)
            }
        }
        workerExecutor.shutdown()
    }
}
