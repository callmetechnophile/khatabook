/*
 * Copyright (c) 2026 KHATAWAS Project
 * Common interface for 48 kHz to 16 kHz FIR Decimators.
 */

package com.khatawas.audio

import java.io.Closeable

interface IFirDecimator : Closeable {
    /**
     * Process streaming input samples and write decimated samples into output.
     * Returns the number of output samples written.
     */
    fun process(input: FloatArray, output: FloatArray): Int

    /**
     * Resets filter history and sample counter to initial zero state.
     */
    fun reset()

    /**
     * Releases any native resources. Default is no-op.
     */
    override fun close() {}
}
