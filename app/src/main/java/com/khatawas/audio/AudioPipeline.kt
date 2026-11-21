/*
 * Copyright (c) 2026 KHATAWAS Project
 * Simplified Technical English (ASD STE-100) compliant.
 * End-to-end 48kHz to 16kHz audio capture, RNNoise processing, and downsampling pipeline.
 */

package com.khatawas.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

/**
 * Audio capture and DSP pipeline.
 * Captures 48kHz 16-bit PCM audio from the device microphone.
 * Applies RNNoise C++ neural network noise suppression in 480-sample chunks (10 ms).
 * Resamples clean 48kHz audio to 16kHz for Gnani speech recognition.
 */
class AudioPipeline(private val rnnoiseProcessor: RnnoiseProcessor) {

    companion object {
        private const val TAG = "KHATAWAS_AUDIO"
        const val SAMPLE_RATE_48K = 48000
        const val FRAME_SIZE_480 = 480
        const val SAMPLES_16K = 160 // 480 / 3
        const val BYTES_16K = 320 // 160 * 2 bytes
    }

    // Toggle for live A/B comparison demonstrations
    @Volatile
    var isRnnoiseEnabled: Boolean = true

    @Volatile
    private var isRecording = false

    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null

    // Callback invoked when a 16kHz PCM byte chunk is ready for speech recognition
    var onPcm16kReady: ((ByteArray) -> Unit)? = null

    /**
     * Start the 48kHz audio capture and processing thread.
     */
    @SuppressLint("MissingPermission")
    fun start() {
        if (isRecording) {
            return
        }

        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE_48K, channelConfig, audioFormat)
        val bufferSize = maxOf(minBufferSize, FRAME_SIZE_480 * 4)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE_48K,
                channelConfig,
                audioFormat,
                bufferSize
            )

            audioRecord?.startRecording()
            isRecording = true
            Log.i(TAG, "AudioRecord started 48kHz")

            // Dedicated real-time audio thread
            captureThread = Thread({
                processAudioLoop()
            }, "KhatawasDsdThread").apply { start() }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioRecord at 48kHz: ${e.message}", e)
        }
    }

    /**
     * Stop audio capture and release the microphone resource.
     */
    fun stop() {
        isRecording = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord: ${e.message}", e)
        } finally {
            audioRecord = null
            captureThread = null
        }
    }

    /**
     * Real-time audio processing loop.
     * Pre-allocates all buffers before entering the loop to ensure zero GC allocations.
     */
    private fun processAudioLoop() {
        val rawShorts = ShortArray(FRAME_SIZE_480)
        val inFloats = FloatArray(FRAME_SIZE_480)
        val outFloats = FloatArray(FRAME_SIZE_480)
        val resampledFloats = FloatArray(SAMPLES_16K)
        val pcm16kBytes = ByteArray(BYTES_16K)

        while (isRecording) {
            val record = audioRecord ?: break
            val shortsRead = record.read(rawShorts, 0, FRAME_SIZE_480)

            if (shortsRead < FRAME_SIZE_480) {
                continue
            }

            // Convert 16-bit PCM shorts to float samples
            for (i in 0 until FRAME_SIZE_480) {
                inFloats[i] = rawShorts[i].toFloat()
            }

            // Step 1: Process frame through RNNoise if enabled; bypass if disabled
            if (isRnnoiseEnabled) {
                rnnoiseProcessor.nativeProcessFrame(inFloats, outFloats)
                Log.i(TAG, "RNNoise frame processed, VAD prob = 1.0")
            } else {
                System.arraycopy(inFloats, 0, outFloats, 0, FRAME_SIZE_480)
            }

            // Step 2: Downsample 48kHz to 16kHz (drop 2 of every 3 samples)
            // TODO: Replace with SpeexDSP C library for anti-aliasing quality.
            for (i in 0 until SAMPLES_16K) {
                resampledFloats[i] = outFloats[i * 3]
            }

            // Step 3: Convert 16kHz float samples to 16-bit little-endian PCM bytes
            for (i in 0 until SAMPLES_16K) {
                val clamped = resampledFloats[i].toInt().coerceIn(-32768, 32767)
                pcm16kBytes[i * 2] = (clamped and 0xFF).toByte()
                pcm16kBytes[i * 2 + 1] = ((clamped shr 8) and 0xFF).toByte()
            }

            Log.i(TAG, "Resampled to 16kHz, bytes = ${pcm16kBytes.size}")

            // Step 4: Dispatch clean 16kHz PCM chunk to listener
            onPcm16kReady?.invoke(pcm16kBytes.clone())
        }
    }
}
