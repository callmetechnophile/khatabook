package com.khatawas.rust

import android.util.Log

/**
 * JNI bindings for the KHATAWAS Rust computational engine.
 * Provides high-performance FIR decimation, speech transcript parsing,
 * pronoun guard evaluation, and integer paise ledger balance computation.
 */
object RustEngine {
    private const val TAG = "RustEngine"
    private var isLoaded = false

    init {
        try {
            System.loadLibrary("rust_khatawas")
            isLoaded = true
            Log.i(TAG, "Successfully loaded librust_khatawas.so native library (version: ${nativeGetVersion()})")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load librust_khatawas.so native library", e)
            isLoaded = false
        }
    }

    fun isNativeLoaded(): Boolean = isLoaded

    external fun nativeGetVersion(): String
    external fun nativeHealthCheck(): Boolean
    external fun nativeFirProcess(input: FloatArray, output: FloatArray): Int
    external fun nativeParseUtterance(utterance: String): String
    external fun nativeCalculateBalance(credits: LongArray, payments: LongArray, reversals: LongArray): Long
}
