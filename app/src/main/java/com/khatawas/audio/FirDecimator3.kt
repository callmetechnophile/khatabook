/*
 * Copyright (c) 2026 KHATAWAS Project
 * Stateful 48 kHz to 16 kHz FIR decimator in Kotlin (Reference Implementation).
 */

package com.khatawas.audio

/**
 * A causal, stateful 31-tap low-pass FIR followed by decimation by three.
 *
 * The cutoff is 7.2 kHz (0.15 cycles/input sample), below the 8 kHz
 * Nyquist limit of the 16 kHz output. Filter history is retained across calls.
 *
 * Reference implementation in pure Kotlin.
 */
class FirDecimator3 : IFirDecimator {

    companion object {
        val COEFFICIENTS = doubleArrayOf(
            0.001694864290, 0.001201491530, -0.000904732020, -0.004227545336,
            -0.005427042574, 0.0, 0.011365095290, 0.018584217910,
            0.008250100127, -0.021236460303, -0.048939206360, -0.039590259461,
            0.029858125147, 0.145106949851, 0.254510780636, 0.299507242544,
            0.254510780636, 0.145106949851, 0.029858125147, -0.039590259461,
            -0.048939206360, -0.021236460303, 0.008250100127, 0.018584217910,
            0.011365095290, 0.0, -0.005427042574, -0.004227545336,
            -0.000904732020, 0.001201491530, 0.001694864290
        )
        const val TAPS = 31
        const val DECIMATION = 3
    }

    private val history = DoubleArray(TAPS)
    private var writeIndex = 0
    private var inputSampleCount = 0L

    override fun reset() {
        history.fill(0.0)
        writeIndex = 0
        inputSampleCount = 0L
    }

    /**
     * Process input samples and write decimated samples to output.
     * For input sizes divisible by three, output size is input.size / 3.
     * The output array must have enough capacity.
     */
    override fun process(input: FloatArray, output: FloatArray): Int {
        if (input.isEmpty()) return 0
        require(output.size >= (input.size + DECIMATION - 1) / DECIMATION) {
            "Output buffer is too small"
        }

        var outputCount = 0
        for (sample in input) {
            history[writeIndex] = sample.toDouble()
            writeIndex = (writeIndex + 1) % TAPS
            inputSampleCount++

            if (inputSampleCount % DECIMATION == 0L) {
                var accumulator = 0.0
                var index = writeIndex
                for (tap in 0 until TAPS) {
                    index = if (index == 0) TAPS - 1 else index - 1
                    accumulator += COEFFICIENTS[tap] * history[index]
                }
                output[outputCount++] = accumulator.toFloat()
            }
        }
        return outputCount
    }
}
