/*
 * Copyright (c) 2026 KHATAWAS Project
 * Comprehensive Instrumented Test & Benchmark Suite for Rust Audio Pipeline Core (Milestone 4).
 */

package com.khatawas

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.khatawas.audio.AudioPipeline
import com.khatawas.audio.FirDecimator3
import com.khatawas.audio.RustAudioPipelineCore
import com.khatawas.rust.RustEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class AudioPipelineInstrumentedTest {

    companion object {
        private const val TAG = "KHATAWAS_M4_TEST"
        private const val BENCH_TAG = "KHATAWAS_M4_BENCHMARK"
    }

    @Before
    fun setUp() {
        assertTrue("Native RustEngine must be loaded for Audio Pipeline tests", RustEngine.isNativeLoaded())
    }

    @Test
    fun test1_AudioPipelineCoreLifecycle() {
        val core = RustAudioPipelineCore()
        try {
            assertTrue("RustAudioPipelineCore native handle must be active", core.isNativeActive)
            assertEquals("Initial processed frames must be zero", 0L, core.getProcessedFrames())

            val inFloats = FloatArray(AudioPipeline.FRAME_SIZE_480) { 1000.0f }
            val outBytes = ByteArray(AudioPipeline.BYTES_16K)

            val written = core.processFloatFrame(inFloats, outBytes)
            assertEquals("Must write exactly 320 bytes for 480 input samples", AudioPipeline.BYTES_16K, written)
            assertEquals("Processed frames must increment to 1", 1L, core.getProcessedFrames())

            assertTrue("Reset must succeed", core.reset())
        } finally {
            core.close()
            assertFalse("Native handle must be cleared after close", core.isNativeActive)
        }
    }

    @Test
    fun test2_Pcm16ChunkStreamingAndAccumulation() {
        val core = RustAudioPipelineCore()
        try {
            val outBytes = ByteArray(1024)

            // Send partial frame: 300 samples = 600 bytes at 48 kHz (less than 480 samples)
            val partialPcm1 = ByteArray(600) { 0x20 }
            val written1 = core.pushAndProcessPcm16(partialPcm1, outBytes)
            assertEquals("Partial frame must yield zero output until complete", 0, written1)
            assertEquals(0L, core.getProcessedFrames())

            // Send remaining samples: 200 samples = 400 bytes (total 500 samples -> 1 full frame of 480 + 20 remainder)
            val partialPcm2 = ByteArray(400) { 0x20 }
            val written2 = core.pushAndProcessPcm16(partialPcm2, outBytes)
            assertEquals("Completing 480 samples must produce exactly 320 bytes", AudioPipeline.BYTES_16K, written2)
            assertEquals(1L, core.getProcessedFrames())
        } finally {
            core.close()
        }
    }

    @Test
    fun test3_NumericalEquivalenceKotlinVsRustPipeline() {
        val frameCount = 30
        val inFloats = FloatArray(AudioPipeline.FRAME_SIZE_480)
        val rng = Random(42)

        val kotlinFir = FirDecimator3()
        val kotlinResampled = FloatArray(AudioPipeline.SAMPLES_16K)
        val kotlinTarget = ByteArray(AudioPipeline.BYTES_16K)

        val rustCore = RustAudioPipelineCore()
        val rustTarget = ByteArray(AudioPipeline.BYTES_16K)

        try {
            var maxSampleDiff = 0

            for (f in 0 until frameCount) {
                for (i in 0 until AudioPipeline.FRAME_SIZE_480) {
                    inFloats[i] = (rng.nextDouble(-30000.0, 30000.0)).toFloat()
                }

                // Kotlin path
                val kSamples = kotlinFir.process(inFloats, kotlinResampled)
                assertEquals(AudioPipeline.SAMPLES_16K, kSamples)
                for (i in 0 until AudioPipeline.SAMPLES_16K) {
                    val sample = kotlinResampled[i].toInt().coerceIn(-32768, 32767)
                    kotlinTarget[i * 2] = (sample and 0xFF).toByte()
                    kotlinTarget[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
                }

                // Rust Audio Pipeline Core path
                val rBytes = rustCore.processFloatFrame(inFloats, rustTarget)
                assertEquals(AudioPipeline.BYTES_16K, rBytes)

                for (s in 0 until AudioPipeline.SAMPLES_16K) {
                    val kLow = kotlinTarget[s * 2].toInt() and 0xFF
                    val kHigh = kotlinTarget[s * 2 + 1].toInt()
                    val kVal = ((kHigh shl 8) or kLow).toShort()

                    val rLow = rustTarget[s * 2].toInt() and 0xFF
                    val rHigh = rustTarget[s * 2 + 1].toInt()
                    val rVal = ((rHigh shl 8) or rLow).toShort()

                    val diff = abs(kVal.toInt() - rVal.toInt())
                    if (diff > maxSampleDiff) maxSampleDiff = diff
                }
            }

            Log.i(TAG, "[PipelineEquivalence] Verified $frameCount frames, maxSampleDiff=$maxSampleDiff")
            assertEquals("Audio pipeline PCM16 sample output must be bit-exact (0 diff)", 0, maxSampleDiff)
        } finally {
            rustCore.close()
        }
    }

    @Test
    fun test4_PerformanceBenchmarkKotlinVsRustPipeline() {
        val warmupFrames = 50
        val benchFrames = 500

        val inFloats = FloatArray(AudioPipeline.FRAME_SIZE_480) { idx ->
            (sin(2.0 * PI * 1200.0 * idx / 48000.0) * 16000.0).toFloat()
        }
        val outBytes = ByteArray(AudioPipeline.BYTES_16K)

        val kotlinFir = FirDecimator3()
        val kotlinResampled = FloatArray(AudioPipeline.SAMPLES_16K)
        val rustCore = RustAudioPipelineCore()

        try {
            // Warm-up Kotlin
            for (i in 0 until warmupFrames) {
                kotlinFir.process(inFloats, kotlinResampled)
                for (s in 0 until AudioPipeline.SAMPLES_16K) {
                    val sample = kotlinResampled[s].toInt().coerceIn(-32768, 32767)
                    outBytes[s * 2] = (sample and 0xFF).toByte()
                    outBytes[s * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
                }
            }

            // Warm-up Rust
            for (i in 0 until warmupFrames) {
                rustCore.processFloatFrame(inFloats, outBytes)
            }

            // Measure Kotlin
            val kTimes = LongArray(benchFrames)
            for (i in 0 until benchFrames) {
                val t0 = System.nanoTime()
                kotlinFir.process(inFloats, kotlinResampled)
                for (s in 0 until AudioPipeline.SAMPLES_16K) {
                    val sample = kotlinResampled[s].toInt().coerceIn(-32768, 32767)
                    outBytes[s * 2] = (sample and 0xFF).toByte()
                    outBytes[s * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
                }
                val t1 = System.nanoTime()
                kTimes[i] = t1 - t0
            }

            // Measure Rust
            val rTimes = LongArray(benchFrames)
            for (i in 0 until benchFrames) {
                val t0 = System.nanoTime()
                rustCore.processFloatFrame(inFloats, outBytes)
                val t1 = System.nanoTime()
                rTimes[i] = t1 - t0
            }

            kTimes.sort()
            rTimes.sort()

            val kMedianUs = kTimes[benchFrames / 2] / 1000.0
            val kP95Us = kTimes[(benchFrames * 0.95).toInt()] / 1000.0
            val kMinUs = kTimes[0] / 1000.0

            val rMedianUs = rTimes[benchFrames / 2] / 1000.0
            val rP95Us = rTimes[(benchFrames * 0.95).toInt()] / 1000.0
            val rMinUs = rTimes[0] / 1000.0

            Log.i(BENCH_TAG, "=== KHATAWAS AUDIO PIPELINE CORE BENCHMARK (500 frames / 10ms) ===")
            Log.i(BENCH_TAG, "KOTLIN PIPELINE -> Median: %.2f us | p95: %.2f us | Min: %.2f us".format(kMedianUs, kP95Us, kMinUs))
            Log.i(BENCH_TAG, "RUST AUDIO CORE -> Median: %.2f us | p95: %.2f us | Min: %.2f us".format(rMedianUs, rP95Us, rMinUs))

            assertTrue("Rust audio core median latency must be < 500 us", rMedianUs < 500.0)
        } finally {
            rustCore.close()
        }
    }
}
