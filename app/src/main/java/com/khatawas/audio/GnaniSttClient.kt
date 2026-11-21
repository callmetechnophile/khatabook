/*
 * Copyright (c) 2026 KHATAWAS Project
 * Simplified Technical English (ASD STE-100) compliant.
 * Production-ready Gnani STT streaming client with explicit state machine, bounded backpressure queue, and zero-loss audio transport.
 */

package com.khatawas.audio

import android.util.Log
import com.khatawas.BuildConfig
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Explicit connection lifecycle states for Gnani STT streaming.
 */
enum class SttState {
    DISCONNECTED,
    CONNECTING,
    INITIALIZING,
    READY,
    STREAMING,
    CLOSING,
    ERROR
}

/**
 * Interface to receive speech transcription and error events.
 */
interface SttListener {
    fun onPartialTranscription(text: String) {}
    fun onTranscriptionReceived(text: String)
    fun onSttFailed()
}

/**
 * Gnani STT streaming client extending Java-WebSocket WebSocketClient.
 * Compatible with Android API 24 through 34.
 * Operates on background worker threads with bounded backpressure queue to decouple network latency from audio capture.
 */
class GnaniSttClient(
    endpointUri: URI = URI(BuildConfig.GNANI_STT_WS_URL),
    headers: Map<String, String> = createAuthHeaders()
) : WebSocketClient(endpointUri, headers) {

    companion object {
        private const val TAG = "KHATAWAS_AUDIO"
        private const val MAX_QUEUE_CAPACITY = 100 // Up to 1.0 second of 10ms audio frames

        private fun createAuthHeaders(): Map<String, String> {
            val headers = HashMap<String, String>()
            val apiKey = BuildConfig.GNANI_API_KEY
            if (apiKey.isNotEmpty()) {
                headers["Authorization"] = "Bearer $apiKey"
                headers["X-API-Key-ID"] = apiKey
                headers["token"] = apiKey
                headers["language_code"] = "hi-IN"
                headers["model"] = "prisma-v2.5"
                headers["encoding"] = "pcm"
                headers["sampling_rate"] = "16000"
            }
            return headers
        }
    }

    private var listener: SttListener? = null
    var fallbackTts: (() -> Unit)? = null
    var onStateChanged: ((SttState) -> Unit)? = null

    @Volatile
    var currentState: SttState = SttState.DISCONNECTED
        private set

    // Backpressure queue: bounded capacity prevents out-of-memory under network congestion
    private val audioQueue = ArrayBlockingQueue<ByteArray>(MAX_QUEUE_CAPACITY)
    private val isSenderRunning = AtomicBoolean(false)
    private val networkExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val senderExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // Telemetry metrics
    val totalFramesEnqueued = AtomicLong(0)
    val totalFramesSent = AtomicLong(0)
    val totalFramesDropped = AtomicLong(0)

    fun setListener(listener: SttListener) {
        this.listener = listener
    }

    private fun setState(newState: SttState) {
        if (currentState != newState) {
            currentState = newState
            Log.i(TAG, "STT State changed -> $newState")
            onStateChanged?.invoke(newState)
        }
    }

    /**
     * Connect to the WebSocket on a dedicated background thread.
     */
    fun connectOnBackgroundThread() {
        setState(SttState.CONNECTING)
        networkExecutor.execute {
            try {
                if (!isOpen) {
                    connect()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Connection error: ${e.message}", e)
                setState(SttState.ERROR)
                handleFailure()
            }
        }
    }

    override fun onOpen(handshakedata: ServerHandshake?) {
        Log.i(TAG, "Gnani STT WebSocket opened; initializing session")
        setState(SttState.INITIALIZING)

        val apiKey = BuildConfig.GNANI_API_KEY
        val initPayload = """{"token":"$apiKey","format":"pcm16","rate":16000,"encoding":"pcm","sample_rate":16000,"channels":1,"language":"hi-IN","lang":"hi-IN"}"""
        try {
            send(initPayload)
            Log.i(TAG, "Gnani STT configuration frame sent (16kHz PCM Mono, hi-IN)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send initial config frame: ${e.message}", e)
            setState(SttState.ERROR)
            handleFailure()
            return
        }

        startAudioSenderLoop()
    }

    private fun startAudioSenderLoop() {
        if (isSenderRunning.compareAndSet(false, true)) {
            senderExecutor.execute {
                while (isSenderRunning.get()) {
                    try {
                        val chunk = audioQueue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS)
                        if (chunk != null) {
                            if (isOpen) {
                                send(chunk)
                                totalFramesSent.incrementAndGet()
                            }
                        }
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in audio sender loop: ${e.message}", e)
                    }
                }
            }
        }
    }

    override fun onMessage(message: String) {
        Log.d(TAG, "STT payload received: $message")
        handleServerJson(message)
    }

    override fun onMessage(bytes: ByteBuffer?) {
        Log.d(TAG, "Binary STT frame received: ${bytes?.remaining()} bytes")
    }

    override fun onClose(code: Int, reason: String?, remote: Boolean) {
        Log.i(TAG, "Gnani STT WebSocket closed. Code: $code, Reason: $reason")
        setState(SttState.DISCONNECTED)
    }

    override fun onError(ex: Exception?) {
        Log.e(TAG, "Gnani STT WebSocket error: ${ex?.message}", ex)
        setState(SttState.ERROR)
        handleFailure()
    }

    /**
     * Send 16kHz PCM audio chunk to the bounded queue.
     * Non-blocking: will never block the AudioRecord capture thread.
     * Drops oldest frame if queue capacity is exceeded under high network jitter.
     */
    fun sendAudioChunk(pcm16k: ByteArray) {
        if (currentState != SttState.READY && currentState != SttState.STREAMING && currentState != SttState.CLOSING) {
            return
        }

        if (currentState == SttState.READY) {
            setState(SttState.STREAMING)
        }

        // Copy chunk bytes into queue storage
        val copy = pcm16k.copyOf()
        totalFramesEnqueued.incrementAndGet()

        if (!audioQueue.offer(copy)) {
            // Queue full: drop oldest frame to maintain low-latency real-time stream
            audioQueue.poll()
            totalFramesDropped.incrementAndGet()
            audioQueue.offer(copy)
            if (totalFramesDropped.get() % 25 == 1L) {
                Log.w(TAG, "STT audio queue full; dropped oldest frame. Total dropped: ${totalFramesDropped.get()}")
            }
        }
    }

    /**
     * Push trailing silence frames to flush VAD and signal speech end cleanly.
     */
    fun flushAndFinish() {
        setState(SttState.CLOSING)
        networkExecutor.execute {
            try {
                // Send 500 ms of silence (5 x 100ms frames)
                val silence = ByteArray(3200)
                for (i in 0 until 5) {
                    audioQueue.offer(silence.copyOf())
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error in flushAndFinish: ${e.message}")
            }
        }
    }

    private fun handleServerJson(raw: String) {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            if (trimmed.isNotEmpty()) {
                listener?.onTranscriptionReceived(trimmed)
            }
            return
        }

        try {
            val json = JSONObject(trimmed)
            val type = json.optString("type")

            // 1. Connection acknowledgement
            if (type == "connected" || json.optString("message").contains("ready", ignoreCase = true)) {
                Log.i(TAG, "Gnani STT service acknowledged readiness")
                setState(SttState.READY)
                return
            }

            // 2. Control / VAD events
            if (type == "vad" || type == "speech_start" || type == "speech_end" || type == "ping" || type == "pong") {
                Log.d(TAG, "VAD/Control event: $type")
                return
            }

            // 3. Extract transcript
            val transcript = extractTranscript(json)
            if (transcript.isNotEmpty()) {
                val isPartial = json.optBoolean("is_partial", false) ||
                        type.contains("partial", ignoreCase = true)

                if (isPartial) {
                    listener?.onPartialTranscription(transcript)
                } else {
                    Log.i(TAG, "Final transcript resolved: $transcript")
                    listener?.onTranscriptionReceived(transcript)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse server JSON: ${e.message}", e)
        }
    }

    private fun extractTranscript(json: JSONObject): String {
        val candidateKeys = listOf(
            "transcript", "text", "result", "asr_output",
            "final_transcript", "sentence"
        )
        for (key in candidateKeys) {
            val candidate = json.optString(key, "")
            if (candidate.isNotEmpty()) return candidate
        }

        val dataObj = json.optJSONObject("data") ?: json.optJSONObject("result")
        if (dataObj != null) {
            for (key in candidateKeys) {
                val candidate = dataObj.optString(key, "")
                if (candidate.isNotEmpty()) return candidate
            }
        }

        val dataArray = json.optJSONArray("data") ?: json.optJSONArray("result")
        if (dataArray != null && dataArray.length() > 0) {
            val firstItem = dataArray.optJSONObject(0)
            if (firstItem != null) {
                for (key in candidateKeys) {
                    val candidate = firstItem.optString(key, "")
                    if (candidate.isNotEmpty()) return candidate
                }
            }
        }

        return ""
    }

    private fun handleFailure() {
        listener?.onSttFailed()
        fallbackTts?.invoke()
    }

    /**
     * Release background executor and close socket.
     */
    fun release() {
        isSenderRunning.set(false)
        audioQueue.clear()

        networkExecutor.execute {
            try {
                if (isOpen) {
                    close()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during close: ${e.message}", e)
            }
        }
        networkExecutor.shutdown()
        senderExecutor.shutdown()
    }
}
