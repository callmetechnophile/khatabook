/*
 * Copyright (c) 2026 KHATAWAS Project
 * Audio capture, RNNoise processing, and anti-aliased 48 kHz to 16 kHz conversion.
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
 * 480-sample input frame produces exactly 160 output samples.
 */
class AudioPipeline(private val rnnoiseProcessor: RnnoiseProcessor) {

    companion object {
        private const val TAG = "KHATAWAS_AUDIO"
        const val SAMPLE_RATE_48K = 48000
        const val FRAME_SIZE_480 = 480
        const val SAMPLES_16K = 160
        const val BYTES_16K = 320
    }

    @Volatile
    var isRnnoiseEnabled: Boolean = true

    @Volatile
    private var isRecording = false

    @Volatile
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var captureThread: Thread? = null

    var onPcm16kReady: ((ByteArray) -> Unit)? = null

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start() {
        if (isRecording) return

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
            recorder.release()
            return
        }

        try {
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("AudioRecord did not enter recording state")
            }
            audioRecord = recorder
            isRecording = true
            captureThread = Thread({ processAudioLoop(recorder) }, "KhatawasDsdThread").apply {
                start()
            }
            Log.i(TAG, "AudioRecord started at 48 kHz")
        } catch (e: Exception) {
            isRecording = false
            audioRecord = null
            try {
                recorder.release()
            } catch (_: Exception) {
            }
            Log.e(TAG, "Failed to start AudioRecord: ${e.message}", e)
        }
    }

    @Synchronized
    fun stop() {
        isRecording = false
        val recorder = audioRecord
        val thread = captureThread

        try {
            recorder?.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "AudioRecord was not recording during stop", e)
        }

        if (thread != null && thread !== Thread.currentThread()) {
            try {
                thread.join(500)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
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
    }

    private fun processAudioLoop(record: AudioRecord) {
        val rawShorts = ShortArray(FRAME_SIZE_480)
        val inFloats = FloatArray(FRAME_SIZE_480)
        val outFloats = FloatArray(FRAME_SIZE_480)
        val resampledFloats = FloatArray(SAMPLES_16K)
        val pcm16kBytes = ByteArray(BYTES_16K)
        val resampler = FirDecimator3()

        while (isRecording && audioRecord === record) {
            val shortsRead = record.read(rawShorts, 0, FRAME_SIZE_480, AudioRecord.READ_BLOCKING)
            if (shortsRead < 0) {
                if (isRecording) Log.e(TAG, "AudioRecord read failed: $shortsRead")
                break
            }
            if (shortsRead == 0) continue
            if (shortsRead != FRAME_SIZE_480) {
                // Do not process a partial frame. The next read fills a fresh frame.
                Log.w(TAG, "Partial audio frame received: $shortsRead samples")
                continue
            }

            for (i in 0 until FRAME_SIZE_480) {
                inFloats[i] = rawShorts[i].toFloat()
            }

            if (isRnnoiseEnabled) {
                rnnoiseProcessor.processFrame(inFloats, outFloats)
            } else {
                System.arraycopy(inFloats, 0, outFloats, 0, FRAME_SIZE_480)
            }

            val outputSamples = resampler.process(outFloats, resampledFloats)
            if (outputSamples != SAMPLES_16K) {
                Log.e(TAG, "Unexpected resampler output count: $outputSamples")
                continue
            }

            for (i in 0 until SAMPLES_16K) {
                val sample = resampledFloats[i].toInt().coerceIn(-32768, 32767)
                pcm16kBytes[i * 2] = (sample and 0xFF).toByte()
                pcm16kBytes[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
            }

            // The callback receives its own array because the capture buffer is reused.
            onPcm16kReady?.invoke(pcm16kBytes.clone())
        }
    }
}
