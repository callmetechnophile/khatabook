/*
 * Copyright (c) 2026 KHATAWAS Project
 * Simplified Technical English (ASD STE-100) compliant.
 * Real Gnani STT WebSocket streaming client using standard Java HttpClient.
 */

package com.khatawas.audio

import android.util.Log
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Interface to receive speech transcription callbacks.
 */
interface SttListener {
    /**
     * Called when the speech recognition engine returns text.
     * Parameter text: The recognized speech transcript.
     */
    fun onTranscriptionReceived(text: String)
}

/**
 * Gnani STT streaming client.
 * Connects to the Gnani Prisma speech WebSocket endpoint.
 * Streams real-time PCM audio chunks and delivers recognized transcripts.
 * Operates on a dedicated background executor thread pool to avoid blocking audio or UI threads.
 */
class GnaniSttClient(private val endpointUrl: String = DEFAULT_ENDPOINT) {

    companion object {
        private const val TAG = "GnaniSttClient"
        const val DEFAULT_ENDPOINT = "wss://asr.gnani.ai/v1/streaming"
    }

    // Dedicated background executor for all network I/O operations
    private val networkExecutor: ExecutorService = Executors.newFixedThreadPool(2)

    private var webSocket: WebSocket? = null
    private var listener: SttListener? = null

    /**
     * Set the transcription callback listener.
     */
    fun setListener(listener: SttListener) {
        this.listener = listener
    }

    /**
     * Connect to the Gnani WebSocket streaming endpoint.
     * Runs asynchronously on the background network thread.
     *
     * Parameter apiKey: Authentication token for Gnani Prisma API.
     * Parameter sampleRate: Audio sample rate in Hz (default 16000).
     */
    fun connect(apiKey: String, sampleRate: Int = 16000) {
        networkExecutor.execute {
            try {
                disconnect()

                val client = HttpClient.newBuilder()
                    .executor(networkExecutor)
                    .build()

                val connectionUri = URI.create("$endpointUrl?encoding=pcm16&sample_rate=$sampleRate")
                Log.d(TAG, "Connecting to Gnani WebSocket at: $connectionUri")

                val wsListener = object : WebSocket.Listener {
                    override fun onOpen(webSocket: WebSocket) {
                        Log.i(TAG, "Gnani STT WebSocket connection established.")
                        webSocket.request(1)
                    }

                    override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                        val responsePayload = data.toString()
                        Log.d(TAG, "Received payload from Gnani: $responsePayload")

                        val transcript = extractTranscript(responsePayload)
                        if (transcript.isNotEmpty()) {
                            Log.i(TAG, "Dispatched transcript: \"$transcript\"")
                            listener?.onTranscriptionReceived(transcript)
                        }

                        webSocket.request(1)
                        return null
                    }

                    override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
                        Log.i(TAG, "Gnani WebSocket closed with status $statusCode: $reason")
                        return null
                    }

                    override fun onError(webSocket: WebSocket, error: Throwable) {
                        Log.e(TAG, "Gnani WebSocket encountered error: ${error.message}", error)
                    }
                }

                val builder = client.newWebSocketBuilder()
                if (apiKey.isNotEmpty()) {
                    builder.header("Authorization", "Bearer $apiKey")
                    builder.header("token", apiKey)
                }

                webSocket = builder.buildAsync(connectionUri, wsListener).get()
                Log.i(TAG, "Gnani STT WebSocket ready for streaming.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to Gnani STT WebSocket: ${e.message}", e)
            }
        }
    }

    /**
     * Send raw PCM audio chunk to the streaming WebSocket.
     * Executes non-blocking on the background network executor.
     *
     * Parameter pcmData: Raw PCM 16-bit byte array.
     */
    fun sendAudioChunk(pcmData: ByteArray) {
        if (pcmData.isEmpty()) {
            return
        }

        networkExecutor.execute {
            try {
                val ws = webSocket
                if (ws != null) {
                    val buffer = ByteBuffer.wrap(pcmData)
                    ws.sendBinary(buffer, true)
                } else {
                    Log.w(TAG, "Audio chunk dropped. WebSocket is not connected.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending audio chunk: ${e.message}", e)
            }
        }
    }

    /**
     * Close the WebSocket connection gracefully.
     */
    fun disconnect() {
        try {
            webSocket?.sendClose(WebSocket.NORMAL_CLOSURE, "Stream finished")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing WebSocket: ${e.message}", e)
        } finally {
            webSocket = null
        }
    }

    /**
     * Release executor and network resources.
     */
    fun shutdown() {
        disconnect()
        networkExecutor.shutdown()
    }

    /**
     * Extract transcript string from payload without external JSON libraries.
     */
    private fun extractTranscript(rawPayload: String): String {
        val trimmed = rawPayload.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            val regex = Regex("\"(?:transcript|text|result|asr_output)\"\\s*:\\s*\"([^\"]+)\"")
            val match = regex.find(trimmed)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        return trimmed
    }
}
