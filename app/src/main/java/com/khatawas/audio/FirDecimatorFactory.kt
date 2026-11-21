/*
 * Copyright (c) 2026 KHATAWAS Project
 * Controlled FIR Decimator Factory supporting Rust migration and instant Kotlin rollback.
 */

package com.khatawas.audio

import com.khatawas.rust.RustEngine

object FirDecimatorFactory {

    enum class FirBackend {
        RUST_NATIVE,
        KOTLIN_REFERENCE
    }

    /**
     * Global production default backend.
     * Can be toggled at runtime for testing, benchmarking, or rollback.
     */
    @Volatile
    var selectedBackend: FirBackend = FirBackend.RUST_NATIVE

    /**
     * Creates an IFirDecimator instance according to the selected backend.
     * If RUST_NATIVE is chosen but the native library is unavailable, it safely falls back to KOTLIN_REFERENCE.
     */
    fun create(backend: FirBackend = selectedBackend): IFirDecimator {
        return when (backend) {
            FirBackend.RUST_NATIVE -> {
                if (RustEngine.isNativeLoaded()) {
                    RustFirDecimator()
                } else {
                    FirDecimator3()
                }
            }
            FirBackend.KOTLIN_REFERENCE -> {
                FirDecimator3()
            }
        }
    }
}
