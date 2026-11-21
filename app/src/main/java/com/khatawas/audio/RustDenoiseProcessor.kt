/*
 * Copyright (c) 2026 KHATAWAS Project
 * Pure Rust audio denoising engine wrapper for Android.
 * Evaluated alongside RNNoise baseline for Kirana noise suppression.
 */

package com.khatawas.audio

import com.khatawas.rust.RustEngine

/**
 * Rust-native audio denoising processor.
 * Performs STFT, adaptive noise floor estimation, and spectral enhancement
 * (Spectral Subtraction, Decision-Directed Wiener Filter, or Combined) on 48 kHz float audio.
 * Frame size is exactly 480 samples (10 ms at 48000 Hz).
 */
class RustDenoiseProcessor(
    initialMode: Int = MODE_WIENER_FILTER
) : AutoCloseable {

    companion object {
        const val FRAME_SIZE = 480
        const val MODE_SPECTRAL_SUBTRACTION = RustEngine.DENOISE_MODE_SPECTRAL_SUBTRACTION
        const val MODE_WIENER_FILTER = RustEngine.DENOISE_MODE_WIENER_FILTER
        const val MODE_COMBINED = RustEngine.DENOISE_MODE_COMBINED
    }

    @Volatile
    private var nativeHandle: Long = 0L

    val isAvailable: Boolean
        get() = nativeHandle != 0L

    init {
        if (RustEngine.isNativeLoaded()) {
            try {
                nativeHandle = RustEngine.nativeDenoiseCreate(initialMode)
            } catch (_: Throwable) {
                nativeHandle = 0L
            }
        }
    }

    /**
     * Process 480 float samples in-place or into separate output array.
     * Guaranteed zero heap allocations in real-time path.
     */
    @Synchronized
    fun processFrame(input: FloatArray, output: FloatArray): Int {
        val handle = nativeHandle
        return if (handle != 0L) {
            RustEngine.nativeDenoiseProcess(handle, input, output)
        } else {
            val copyLen = minOf(input.size, output.size, FRAME_SIZE)
            System.arraycopy(input, 0, output, 0, copyLen)
            copyLen
        }
    }

    /**
     * Update active denoising algorithm mode.
     */
    @Synchronized
    fun setMode(mode: Int): Boolean {
        val handle = nativeHandle
        return if (handle != 0L) {
            RustEngine.nativeDenoiseSetMode(handle, mode)
        } else {
            false
        }
    }

    /**
     * Reset internal STFT overlap buffer and noise estimation statistics.
     */
    @Synchronized
    fun reset(): Boolean {
        val handle = nativeHandle
        return if (handle != 0L) {
            RustEngine.nativeDenoiseReset(handle)
        } else {
            false
        }
    }

    /**
     * Free native Rust state handle.
     */
    @Synchronized
    fun destroy() {
        val handle = nativeHandle
        if (handle != 0L) {
            nativeHandle = 0L
            RustEngine.nativeDenoiseDestroy(handle)
        }
    }

    override fun close() {
        destroy()
    }
}
