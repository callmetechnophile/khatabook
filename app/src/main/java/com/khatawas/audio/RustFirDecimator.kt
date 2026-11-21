/*
 * Copyright (c) 2026 KHATAWAS Project
 * Rust-backed 48 kHz to 16 kHz FIR Decimator via JNI.
 */

package com.khatawas.audio

import com.khatawas.rust.RustEngine

/**
 * High-performance stateful FIR decimator executing in Rust.
 * Manages an opaque native handle with lifecycle and memory safety.
 */
class RustFirDecimator : IFirDecimator {

    private var handle: Long = 0L
    private val fallbackKotlin: FirDecimator3 by lazy { FirDecimator3() }

    init {
        if (RustEngine.isNativeLoaded()) {
            handle = RustEngine.nativeFirInit()
        }
    }

    val isNativeActive: Boolean
        get() = handle != 0L

    override fun process(input: FloatArray, output: FloatArray): Int {
        if (input.isEmpty()) return 0
        val currentHandle = handle
        return if (currentHandle != 0L) {
            val written = RustEngine.nativeFirProcessHandle(currentHandle, input, output)
            if (written >= 0) {
                written
            } else {
                // If handle invalid, fallback gracefully
                fallbackKotlin.process(input, output)
            }
        } else {
            fallbackKotlin.process(input, output)
        }
    }

    override fun reset() {
        val currentHandle = handle
        if (currentHandle != 0L) {
            RustEngine.nativeFirResetHandle(currentHandle)
        } else {
            fallbackKotlin.reset()
        }
    }

    override fun close() {
        val currentHandle = handle
        if (currentHandle != 0L) {
            RustEngine.nativeFirDestroyHandle(currentHandle)
            handle = 0L
        }
    }
}
