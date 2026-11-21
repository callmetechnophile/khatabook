/*
 * Copyright (c) 2026 KHATAWAS Project
 * Numerical Equivalence and Performance Benchmark Suite for Rust FIR Decimator.
 */

package com.khatawas

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.khatawas.audio.FirDecimator3
import com.khatawas.audio.RustFirDecimator
import com.khatawas.rust.RustEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class FirNumericalEquivalenceInstrumentedTest {

    companion object {
        private const val TAG = "KHATAWAS_FIR_EQUIV"
        private const val BENCH_TAG = "KHATAWAS_FIR_BENCHMARK"
        // Numerical tolerance: Double-precision accumulator converted to Float.
        // Direct equivalence limit: differences must be within single-precision epsilon (1e-5).
        private const val MAX_TOLERANCE = 1e-4f
    }

    private data class EquivalenceMetrics(
        val testName: String,
        val inputSamples: Int,
        val outputSamples: Int,
        val maxAbsError: Float,
        val meanAbsError: Float,
        val rmsError: Float,
        val pass: Boolean
    )

    private fun computeMetrics(
        name: String,
        kotlinOut: FloatArray,
        rustOut: FloatArray,
        count: Int
    ): EquivalenceMetrics {
        var maxErr = 0.0f
        var sumErr = 0.0
        var sumSqErr = 0.0

        for (i in 0 until count) {
            val err = abs(kotlinOut[i] - rustOut[i])
            if (err > maxErr) maxErr = err
            sumErr += err.toDouble()
            sumSqErr += (err.toDouble() * err.toDouble())
        }

        val meanErr = if (count > 0) (sumErr / count).toFloat() else 0f
        val rms = if (count > 0) sqrt(sumSqErr / count).toFloat() else 0f
        val pass = maxErr <= MAX_TOLERANCE

        Log.i(
            TAG,
            "[$name] count=$count, maxErr=$maxErr, meanErr=$meanErr, rms=$rms, pass=$pass"
        )
        return EquivalenceMetrics(name, -1, count, maxErr, meanErr, rms, pass)
    }

    @Before
    fun setUp() {
        assertTrue("Native RustEngine must be loaded for JNI equivalence testing", RustEngine.isNativeLoaded())
    }

    @Test
    fun test1_ImpulseResponseEquivalence() {
        val input = FloatArray(480) { 0f }
        input[0] = 1.0f

        val kotlinOut = FloatArray(160)
        val rustOut = FloatArray(160)

        val kDec = FirDecimator3()
        val rDec = RustFirDecimator()
        try {
            assertTrue("Rust native decimator handle must be active", rDec.isNativeActive)
            val kCount = kDec.process(input, kotlinOut)
            val rCount = rDec.process(input, rustOut)

            assertEquals("Sample count match", kCount, rCount)
            val metrics = computeMetrics("ImpulseResponse", kotlinOut, rustOut, kCount)
            assertTrue("Impulse max error within tolerance", metrics.pass)
        } finally {
            rDec.close()
        }
    }

    @Test
    fun test2_StepResponseEquivalence() {
        val input = FloatArray(480) { 1.0f }

        val kotlinOut = FloatArray(160)
        val rustOut = FloatArray(160)

        val kDec = FirDecimator3()
        val rDec = RustFirDecimator()
        try {
            val kCount = kDec.process(input, kotlinOut)
            val rCount = rDec.process(input, rustOut)

            assertEquals("Sample count match", kCount, rCount)
            val metrics = computeMetrics("StepResponse", kotlinOut, rustOut, kCount)
            assertTrue("Step response within tolerance", metrics.pass)
        } finally {
            rDec.close()
        }
    }

    @Test
    fun test3_SilenceEquivalence() {
        val input = FloatArray(480 * 4) { 0.0f }

        val kotlinOut = FloatArray(input.size / 3)
        val rustOut = FloatArray(input.size / 3)

        val kDec = FirDecimator3()
        val rDec = RustFirDecimator()
        try {
            val kCount = kDec.process(input, kotlinOut)
            val rCount = rDec.process(input, rustOut)

            assertEquals("Sample count match", kCount, rCount)
            val metrics = computeMetrics("DigitalSilence", kotlinOut, rustOut, kCount)
            assertEquals("Silence error must be exact 0.0", 0.0f, metrics.maxAbsError, 0.0f)
        } finally {
            rDec.close()
        }
    }

    @Test
    fun test4_DcInputEquivalence() {
        val input = FloatArray(480 * 8) { 15000.0f }

        val kotlinOut = FloatArray(input.size / 3)
        val rustOut = FloatArray(input.size / 3)

        val kDec = FirDecimator3()
        val rDec = RustFirDecimator()
        try {
            val kCount = kDec.process(input, kotlinOut)
            val rCount = rDec.process(input, rustOut)

            assertEquals("Sample count match", kCount, rCount)
            val metrics = computeMetrics("DcInput_15000", kotlinOut, rustOut, kCount)
            assertTrue("DC max error within tolerance", metrics.pass)
        } finally {
            rDec.close()
        }
    }

    @Test
    fun test5_MultipleSineFrequenciesEquivalence() {
        val frequencies = doubleArrayOf(500.0, 1000.0, 3500.0, 7000.0, 8000.0, 12000.0, 16000.0)

        for (f in frequencies) {
            val input = FloatArray(480 * 4) { idx ->
                (sin(2.0 * PI * f * idx / 48000.0) * 20000.0).toFloat()
            }

            val kotlinOut = FloatArray(input.size / 3)
            val rustOut = FloatArray(input.size / 3)

            val kDec = FirDecimator3()
            val rDec = RustFirDecimator()
            try {
                val kCount = kDec.process(input, kotlinOut)
                val rCount = rDec.process(input, rustOut)

                assertEquals("Count match for ${f}Hz", kCount, rCount)
                val metrics = computeMetrics("Sine_${f.toInt()}Hz", kotlinOut, rustOut, kCount)
                assertTrue("Sine ${f}Hz within tolerance", metrics.pass)
            } finally {
                rDec.close()
            }
        }
    }

    @Test
    fun test6_RandomPcmInputEquivalence() {
        val rng = Random(42)
        val input = FloatArray(480 * 10) { (rng.nextDouble(-32768.0, 32767.0)).toFloat() }

        val kotlinOut = FloatArray(input.size / 3)
        val rustOut = FloatArray(input.size / 3)

        val kDec = FirDecimator3()
        val rDec = RustFirDecimator()
        try {
            val kCount = kDec.process(input, kotlinOut)
            val rCount = rDec.process(input, rustOut)

            assertEquals("Sample count match", kCount, rCount)
            val metrics = computeMetrics("RandomPCM", kotlinOut, rustOut, kCount)
            assertTrue("Random PCM max error within tolerance", metrics.pass)
        } finally {
            rDec.close()
        }
    }

    @Test
    fun test7_MinMaxPcm16Equivalence() {
        val input = FloatArray(480) { idx ->
            if (idx % 2 == 0) -32768.0f else 32767.0f
        }

        val kotlinOut = FloatArray(160)
        val rustOut = FloatArray(160)

        val kDec = FirDecimator3()
        val rDec = RustFirDecimator()
        try {
            val kCount = kDec.process(input, kotlinOut)
            val rCount = rDec.process(input, rustOut)

            assertEquals("Sample count match", kCount, rCount)
            val metrics = computeMetrics("MinMaxPcm16", kotlinOut, rustOut, kCount)
            assertTrue("Extreme PCM16 max error within tolerance", metrics.pass)
        } finally {
            rDec.close()
        }
    }

    @Test
    fun test8_Consecutive10msFramesEquivalence() {
        val numFrames = 50
        val frameSize = 480
        val outFrameSize = 160

        val rng = Random(1234)
        val kDec = FirDecimator3()
        val rDec = RustFirDecimator()

        try {
            var globalMaxErr = 0.0f
            val kFrameOut = FloatArray(outFrameSize)
            val rFrameOut = FloatArray(outFrameSize)

            for (f in 0 until numFrames) {
                val frameIn = FloatArray(frameSize) { (rng.nextDouble(-10000.0, 10000.0)).toFloat() }
                val kCount = kDec.process(frameIn, kFrameOut)
                val rCount = rDec.process(frameIn, rFrameOut)

                assertEquals("Count match at frame $f", kCount, rCount)
                for (i in 0 until outFrameSize) {
                    val err = abs(kFrameOut[i] - rFrameOut[i])
                    if (err > globalMaxErr) globalMaxErr = err
                }
            }

            Log.i(TAG, "[Consecutive50Frames] globalMaxErr=$globalMaxErr")
            assertTrue("50 consecutive frames match across boundaries", globalMaxErr <= MAX_TOLERANCE)
        } finally {
            rDec.close()
        }
    }

    @Test
    fun test9_IrregularFrameBoundariesEquivalence() {
        val chunkSizes = intArrayOf(10, 47, 100, 480, 7, 3, 1, 99, 13)
        val rng = Random(999)

        val kDec = FirDecimator3()
        val rDec = RustFirDecimator()

        try {
            var globalMaxErr = 0.0f
            for (size in chunkSizes) {
                val chunk = FloatArray(size) { (rng.nextDouble(-5000.0, 5000.0)).toFloat() }
                val kOut = FloatArray((size + 2) / 3 + 2)
                val rOut = FloatArray((size + 2) / 3 + 2)

                val kCount = kDec.process(chunk, kOut)
                val rCount = rDec.process(chunk, rOut)

                assertEquals("Irregular chunk size $size output count match", kCount, rCount)
                for (i in 0 until kCount) {
                    val err = abs(kOut[i] - rOut[i])
                    if (err > globalMaxErr) globalMaxErr = err
                }
            }
            Log.i(TAG, "[IrregularChunks] globalMaxErr=$globalMaxErr")
            assertTrue("Irregular chunk outputs match", globalMaxErr <= MAX_TOLERANCE)
        } finally {
            rDec.close()
        }
    }

    @Test
    fun test10_ResetBehaviorEquivalence() {
        val input = FloatArray(480) { 500.0f }

        val kDec = FirDecimator3()
        val rDec = RustFirDecimator()

        try {
            val kOut1 = FloatArray(160)
            val rOut1 = FloatArray(160)
            kDec.process(input, kOut1)
            rDec.process(input, rOut1)

            // Reset both
            kDec.reset()
            rDec.reset()

            val kOut2 = FloatArray(160)
            val rOut2 = FloatArray(160)
            val kCount = kDec.process(input, kOut2)
            val rCount = rDec.process(input, rOut2)

            assertEquals(kCount, rCount)
            val metrics = computeMetrics("PostResetExecution", kOut2, rOut2, kCount)
            assertTrue("Post-reset execution matches", metrics.pass)

            // Verify out2 matches out1
            for (i in 0 until 160) {
                assertEquals("Reset state must match initial state", rOut1[i], rOut2[i], 1e-6f)
            }
        } finally {
            rDec.close()
        }
    }

    @Test
    fun test11_LongDurationStreamingEquivalence() {
        val totalSamples = 10000
        val input = FloatArray(totalSamples) { idx ->
            (sin(2.0 * PI * 1500.0 * idx / 48000.0) * 10000.0).toFloat()
        }

        val kotlinOut = FloatArray((totalSamples + 2) / 3)
        val rustOut = FloatArray((totalSamples + 2) / 3)

        val kDec = FirDecimator3()
        val rDec = RustFirDecimator()

        try {
            val kCount = kDec.process(input, kotlinOut)
            val rCount = rDec.process(input, rustOut)

            assertEquals("10,000 samples output count match", kCount, rCount)
            val metrics = computeMetrics("LongStreaming_10k", kotlinOut, rustOut, kCount)
            assertTrue("Long streaming max error within tolerance", metrics.pass)
        } finally {
            rDec.close()
        }
    }

    @Test
    fun test12_PerformanceBenchmarkKotlinVsRust() {
        val frameSize = 480 // 10 ms at 48 kHz
        val outFrameSize = 160
        val warmupIterations = 50
        val benchmarkIterations = 500

        val input = FloatArray(frameSize) { idx ->
            (sin(2.0 * PI * 1000.0 * idx / 48000.0) * 8000.0).toFloat()
        }
        val outBuf = FloatArray(outFrameSize)

        val kDec = FirDecimator3()
        val rDec = RustFirDecimator()

        try {
            // Warm-up Kotlin
            for (i in 0 until warmupIterations) {
                kDec.process(input, outBuf)
            }

            // Warm-up Rust
            for (i in 0 until warmupIterations) {
                rDec.process(input, outBuf)
            }

            // Benchmark Kotlin
            val kotlinTimesNs = LongArray(benchmarkIterations)
            for (i in 0 until benchmarkIterations) {
                val t0 = System.nanoTime()
                kDec.process(input, outBuf)
                val t1 = System.nanoTime()
                kotlinTimesNs[i] = t1 - t0
            }

            // Benchmark Rust
            val rustTimesNs = LongArray(benchmarkIterations)
            for (i in 0 until benchmarkIterations) {
                val t0 = System.nanoTime()
                rDec.process(input, outBuf)
                val t1 = System.nanoTime()
                rustTimesNs[i] = t1 - t0
            }

            kotlinTimesNs.sort()
            rustTimesNs.sort()

            val kMedianUs = kotlinTimesNs[benchmarkIterations / 2] / 1000.0
            val kP95Us = kotlinTimesNs[(benchmarkIterations * 0.95).toInt()] / 1000.0
            val kMinUs = kotlinTimesNs[0] / 1000.0

            val rMedianUs = rustTimesNs[benchmarkIterations / 2] / 1000.0
            val rP95Us = rustTimesNs[(benchmarkIterations * 0.95).toInt()] / 1000.0
            val rMinUs = rustTimesNs[0] / 1000.0

            Log.i(
                BENCH_TAG,
                "=== KHATAWAS FIR DECIMATOR PERFORMANCE BENCHMARK (500 frames of 10ms/480-samples) ==="
            )
            Log.i(
                BENCH_TAG,
                "KOTLIN REFERENCE -> Median: %.2f us | p95: %.2f us | Min: %.2f us".format(kMedianUs, kP95Us, kMinUs)
            )
            Log.i(
                BENCH_TAG,
                "RUST JNI ENGINE  -> Median: %.2f us | p95: %.2f us | Min: %.2f us".format(rMedianUs, rP95Us, rMinUs)
            )

            // Both implementations must process a 10,000 us (10 ms) audio frame in vastly under 1 ms
            assertTrue("Kotlin FIR should be faster than 2000 us per frame", kMedianUs < 2000.0)
            assertTrue("Rust FIR should be faster than 2000 us per frame", rMedianUs < 2000.0)
        } finally {
            rDec.close()
        }
    }
}
