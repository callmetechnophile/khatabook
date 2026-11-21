/*
 * Copyright (c) 2026 KHATAWAS Project
 * Audio capture, RNNoise processing, and anti-aliased 48 kHz to 16 kHz conversion.
 * Zero-allocation steady-state pipeline with race-free AudioRecord shutdown.
 */

package com.khatawas.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

/**
 * Captures mono PCM16 audio at 48 kHz, applies RNNoise, and converts it to 16 kHz.
 *
 * The FIR decimator keeps filter history between 10 ms input frames. Each complete
 * 480-sample input frame produces exactly 160 output samples (320 bytes).
 */
class AudioPipeline(private val rnnoiseProcessor: RnnoiseProcessor) {

    companion object {
        private const val TAG = "KHATAWAS_AUDIO"
        const val SAMPLE_RATE_48K = 48000
        const val FRAME_SIZE_480 = 480
        const val SAMPLES_16K = 160
        const val BYTES_16K = 320
        private const val BUFFER_POOL_SIZE = 8
    }

    @Volatile
    var isRnnoiseEnabled: Boolean = true

    @Volatile
    private var isRecording = false

    @Volatile
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var captureThread: Thread? = null

    // Callback receives 320-byte PCM16 chunks at 16 kHz
    var onPcm16kReady: ((ByteArray) -> Unit)? = null

    // Pre-allocated buffer pool to eliminate per-frame allocations in the steady-state loop
    private val bufferPool = Array(BUFFER_POOL_SIZE) { ByteArray(BYTES_16K) }
    private var poolIndex = 0

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start() {
        if (isRecording) {
            Log.w(TAG, "AudioPipeline.start() called while already recording")
            return
        }

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_48K,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferSize <= 0) {
            Log.e(TAG, "AudioRecord returned invalid minimum buffer size: $minBufferSize")
            return
        }

        val bufferSize = maxOf(minBufferSize, FRAME_SIZE_480 * 4 * 2)
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE_48K,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create AudioRecord: ${e.message}", e)
            return
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord initialization failed")
            try {
                recorder.release()
            } catch (_: Exception) {}
            return
        }

        try {
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("AudioRecord did not enter recording state")
            }
            audioRecord = recorder
            isRecording = true

            captureThread = Thread({ processAudioLoop(recorder) }, "KhatawasAudioCaptureThread").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
            Log.i(TAG, "AudioRecord started at 48 kHz")
        } catch (e: Exception) {
            isRecording = false
            audioRecord = null
            try {
                recorder.release()
            } catch (_: Exception) {}
            Log.e(TAG, "Failed to start AudioRecord: ${e.message}", e)
        }
    }

    /**
     * Deterministic shutdown sequence:
     * 1. Set isRecording = false so capture loop stops.
     * 2. Call recorder.stop() to unblock any waiting read().
     * 3. Join the capture thread completely.
     * 4. Only after thread termination release AudioRecord native resources.
     */
    @Synchronized
    fun stop() {
        if (!isRecording && audioRecord == null) {
            return
        }

        isRecording = false
        val recorder = audioRecord
        val thread = captureThread

        try {
            recorder?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord stop exception: ${e.message}")
        }

        if (thread != null && thread !== Thread.currentThread()) {
            try {
                thread.join(2000)
                if (thread.isAlive) {
                    Log.w(TAG, "Capture thread did not terminate within timeout; interrupting")
                    thread.interrupt()
                    thread.join(1000)
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                Log.w(TAG, "Interrupted while waiting for capture thread to terminate")
            }
        }

        try {
            recorder?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioRecord: ${e.message}", e)
        } finally {
            audioRecord = null
            captureThread = null
        }
        Log.i(TAG, "AudioRecord safely stopped and released")
    }

    private fun processAudioLoop(record: AudioRecord) {
        val rawShorts = ShortArray(FRAME_SIZE_480)
        val inFloats = FloatArray(FRAME_SIZE_480)
        val outFloats = FloatArray(FRAME_SIZE_480)
        val resampledFloats = FloatArray(SAMPLES_16K)
        val resampler = FirDecimator3()

        try {
            while (isRecording && audioRecord === record) {
                var samplesRead = 0
                while (isRecording && samplesRead < FRAME_SIZE_480) {
                    val count = record.read(
                        rawShorts,
                        samplesRead,
                        FRAME_SIZE_480 - samplesRead,
                        AudioRecord.READ_BLOCKING
                    )
                    if (count < 0) {
                        if (isRecording) {
                            Log.e(TAG, "AudioRecord read returned error: $count")
                        }
                        return
                    }
                    if (count == 0) continue
                    samplesRead += count
                }

                if (!isRecording || samplesRead != FRAME_SIZE_480) {
                    break
                }

                // Convert raw PCM16 short values to float
                for (i in 0 until FRAME_SIZE_480) {
                    inFloats[i] = rawShorts[i].toFloat()
                }

                // RNNoise noise suppression
                if (isRnnoiseEnabled && rnnoiseProcessor.isAvailable) {
                    rnnoiseProcessor.processFrame(inFloats, outFloats)
                } else {
                    System.arraycopy(inFloats, 0, outFloats, 0, FRAME_SIZE_480)
                }

                // 48 kHz -> 16 kHz decimation with 31-tap FIR low-pass filter (Rust native engine with Kotlin fallback)
                val outputSamples = if (com.khatawas.rust.RustEngine.isNativeLoaded()) {
                    com.khatawas.rust.RustEngine.nativeFirProcess(outFloats, resampledFloats)
                } else {
                    resampler.process(outFloats, resampledFloats)
                }
                if (outputSamples != SAMPLES_16K) {
                    Log.e(TAG, "Resampler output mismatch: expected $SAMPLES_16K, got $outputSamples")
                    continue
                }

                // Obtain reusable buffer from pool to eliminate per-frame allocations
                val targetBuffer = bufferPool[poolIndex]
                poolIndex = (poolIndex + 1) % BUFFER_POOL_SIZE

                // Convert float samples to signed 16-bit little-endian bytes with clamping
                for (i in 0 until SAMPLES_16K) {
                    val sample = resampledFloats[i].toInt().coerceIn(-32768, 32767)
                    targetBuffer[i * 2] = (sample and 0xFF).toByte()
                    targetBuffer[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
                }

                onPcm16kReady?.invoke(targetBuffer)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in processAudioLoop: ${e.message}", e)
        }
    }
}
