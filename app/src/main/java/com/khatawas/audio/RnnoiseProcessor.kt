/*
 * Copyright (c) 2026 KHATAWAS Project
 * Simplified Technical English (ASD STE-100) compliant.
 * Kotlin wrapper for RNNoise noise suppression and Voice Activity Detection (VAD).
 */

package com.khatawas.audio

/**
 * RNNoise audio processor.
 * Applies neural network noise suppression to 48kHz float32 PCM audio.
 * Audio frames require exactly 480 samples (10 ms duration at 48000 Hz).
 */
class RnnoiseProcessor {

    companion object {
        const val FRAME_SIZE = 480

        init {
            System.loadLibrary("khatawas_jni")
        }
    }

    init {
        nativeInit()
    }

    /**
     * Process one audio frame of 480 float samples.
     * Parameter input: Pre-allocated FloatArray containing 480 input audio samples.
     * Parameter output: Pre-allocated FloatArray receiving 480 clean audio samples.
     */
    fun processFrame(input: FloatArray, output: FloatArray) {
        nativeProcessFrame(input, output)
    }

    /**
     * Destroy native resources and free C++ memory.
     */
    fun destroy() {
        nativeDestroy()
    }

    // Native JNI functions
    external fun nativeInit()
    external fun nativeProcessFrame(input: FloatArray, output: FloatArray)
    external fun nativeDestroy()
}
