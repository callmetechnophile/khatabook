/*
 * Copyright (c) 2026 KHATAWAS Project
 * Simplified Technical English (ASD STE-100) compliant.
 * Kotlin wrapper for RNNoise noise suppression and Voice Activity Detection (VAD).
 */

package com.khatawas.audio

import android.util.Log

/**
 * RNNoise audio processor.
 * Applies neural network noise suppression to 48kHz float32 PCM audio.
 * Audio frames require exactly 480 samples (10 ms duration at 48000 Hz).
 * Owns a distinct native DenoiseState handle.
 */
class RnnoiseProcessor {

    companion object {
        private const val TAG = "KhatawasRnnoise"
        const val FRAME_SIZE = 480

        private var isLibraryLoaded = false

        init {
            try {
                System.loadLibrary("khatawas_jni")
                isLibraryLoaded = true
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load khatawas_jni library: ${e.message}", e)
                isLibraryLoaded = false
            }
        }
    }

    @Volatile
    private var nativeHandle: Long = 0L

    val isAvailable: Boolean
        get() = nativeHandle != 0L

    init {
        if (isLibraryLoaded) {
            try {
                nativeHandle = nativeInit()
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing RNNoise native state: ${e.message}", e)
                nativeHandle = 0L
            }
        }
    }

    /**
     * Process one audio frame of 480 float samples.
     * Parameter input: Pre-allocated FloatArray containing at least 480 input audio samples.
     * Parameter output: Pre-allocated FloatArray receiving at least 480 clean audio samples.
     */
    @Synchronized
    fun processFrame(input: FloatArray, output: FloatArray) {
        val handle = nativeHandle
        if (handle != 0L) {
            nativeProcessFrame(handle, input, output)
        } else {
            // Safe bypass if native engine is unavailable
            System.arraycopy(input, 0, output, 0, minOf(input.size, output.size, FRAME_SIZE))
        }
    }

    /**
     * Destroy native resources and free C++ memory.
     */
    @Synchronized
    fun destroy() {
        val handle = nativeHandle
        if (handle != 0L) {
            nativeHandle = 0L
            nativeDestroy(handle)
        }
    }

    // Native JNI functions
    private external fun nativeInit(): Long
    private external fun nativeProcessFrame(handle: Long, input: FloatArray, output: FloatArray)
    private external fun nativeDestroy(handle: Long)
}
