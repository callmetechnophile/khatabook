/*
 * Copyright (c) 2026 KHATAWAS Project
 * Simplified Technical English (ASD STE-100) compliant.
 * Real Gnani TTS client using Java-WebSocket for Android API 24-30 compatibility.
 */

package com.khatawas.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Base64
import android.util.Log
import com.khatawas.BuildConfig
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Interface to receive Text-to-Speech audio and state events.
 */
interface TtsListener {
    fun onAudioReceived(audioData: ByteArray)
    fun onTtsFailed(errorMessage: String)
}

/**
 * Gnani TTS client extending Java-WebSocket WebSocketClient.
 * Connects to Gnani streaming TTS service.
 * Operates on a dedicated background thread to prevent UI thread blockage.
 */
class GnaniTtsClient(
    endpointUri: URI = URI(BuildConfig.GNANI_TTS_WS_URL),
    headers: Map<String, String> = createAuthHeaders()
) : WebSocketClient(endpointUri, headers) {

    companion object {
        private const val TAG = "KHATAWAS_AUDIO"
        private const val SAMPLE_RATE_HZ = 16000

        private fun createAuthHeaders(): Map<String, String> {
            val headers = HashMap<String, String>()
            val apiKey = BuildConfig.GNANI_API_KEY
            if (apiKey.isNotEmpty()) {
                headers["Authorization"] = "Bearer $apiKey"
                headers["X-API-Key-ID"] = apiKey
                headers["token"] = apiKey
            }
            return headers
        }
    }

    private var listener: TtsListener? = null
    private val workerExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var pendingText: String? = null
    private var pendingLanguage: String = "hi-IN"

    private var audioTrack: AudioTrack? = null

    fun setListener(listener: TtsListener) {
        this.listener = listener
    }

    /**
     * Request speech synthesis for the specified text on background thread.
     */
    fun speak(text: String, language: String = "hi-IN") {
        workerExecutor.execute {
            pendingText = text
            pendingLanguage = language
            if (isOpen) {
                sendSynthesisRequest(text, language)
            } else {
                try {
                    connect()
                } catch (e: Exception) {
                    Log.e(TAG, "TTS connection failed: ${e.message}", e)
                    listener?.onTtsFailed(e.message ?: "Connection error")
                }
            }
        }
    }

    override fun onOpen(handshakedata: ServerHandshake?) {
        Log.i(TAG, "Gnani TTS WebSocket opened")
        val text = pendingText
        if (!text.isNullOrEmpty()) {
            sendSynthesisRequest(text, pendingLanguage)
        }
    }

    private fun sendSynthesisRequest(text: String, language: String) {
        val apiKey = BuildConfig.GNANI_API_KEY
        val payload = """{"token":"$apiKey","text":"$text","language":"$language","audio_format":"pcm","format":"pcm","sampling_rate":16000,"rate":16000}"""
        try {
            send(payload)
            Log.i(TAG, "TTS triggered for text: $text")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send TTS request: ${e.message}", e)
            listener?.onTtsFailed(e.message ?: "Failed to send request")
        }
    }

    override fun onMessage(message: String) {
        Log.i(TAG, "TTS text response: $message")
        // Check if server returns audio base64 payload
        val trimmed = message.trim()
        if (trimmed.startsWith("{") && trimmed.contains("\"audio")) {
            val match = Regex("\"(?:audio|audioContent|data)\"\\s*:\\s*\"([^\"]+)\"").find(trimmed)
            if (match != null) {
                try {
                    val decodedBytes = Base64.decode(match.groupValues[1], Base64.DEFAULT)
                    playAudioChunk(decodedBytes)
                    listener?.onAudioReceived(decodedBytes)
                    return
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decode base64 audio: ${e.message}", e)
                }
            }
        }
    }

    override fun onMessage(bytes: ByteBuffer?) {
        if (bytes == null) return
        val length = bytes.remaining()
        val audioData = ByteArray(length)
        bytes.get(audioData)
        Log.i(TAG, "TTS binary audio chunk received: $length bytes")
        playAudioChunk(audioData)
        listener?.onAudioReceived(audioData)
    }

    /**
     * Play raw 16kHz 16-bit mono PCM audio chunk via AudioTrack.
     */
    private fun playAudioChunk(audioBytes: ByteArray) {
        workerExecutor.execute {
            try {
                if (audioTrack == null) {
                    val minBufSize = AudioTrack.getMinBufferSize(
                        SAMPLE_RATE_HZ,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                    )
                    val bufferSize = Math.max(minBufSize, 4096)

                    audioTrack = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        AudioTrack(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build(),
                            AudioFormat.Builder()
                                .setSampleRate(SAMPLE_RATE_HZ)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .build(),
                            bufferSize,
                            AudioTrack.MODE_STREAM,
                            AudioManager.AUDIO_SESSION_ID_GENERATE
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        AudioTrack(
                            AudioManager.STREAM_MUSIC,
                            SAMPLE_RATE_HZ,
                            AudioFormat.CHANNEL_OUT_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            bufferSize,
                            AudioTrack.MODE_STREAM
                        )
                    }
                    audioTrack?.play()
                }

                // If chunk contains WAV header (RIFF), skip 44 bytes
                var offset = 0
                var size = audioBytes.size
                if (size > 44 && audioBytes[0] == 'R'.code.toByte() && audioBytes[1] == 'I'.code.toByte()) {
                    offset = 44
                    size -= 44
                }

                audioTrack?.write(audioBytes, offset, size)
            } catch (e: Exception) {
                Log.e(TAG, "Error playing audio chunk: ${e.message}", e)
            }
        }
    }

    override fun onClose(code: Int, reason: String?, remote: Boolean) {
        Log.i(TAG, "Gnani TTS WebSocket closed. Code: $code, Reason: $reason")
    }

    override fun onError(ex: Exception?) {
        Log.e(TAG, "Gnani TTS WebSocket error: ${ex?.message}", ex)
        listener?.onTtsFailed(ex?.message ?: "WebSocket error")
    }

    /**
     * Stop playback and release system resources.
     */
    fun release() {
        workerExecutor.execute {
            try {
                audioTrack?.stop()
                audioTrack?.release()
                audioTrack = null
                if (isOpen) {
                    close()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing TTS client: ${e.message}", e)
            }
        }
        workerExecutor.shutdown()
    }
}
