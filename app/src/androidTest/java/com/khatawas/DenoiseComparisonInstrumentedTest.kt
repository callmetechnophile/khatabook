/*
 * Copyright (c) 2026 KHATAWAS Project
 * Milestone 5: Audio Denoising Engine Comparative Evaluation & Benchmark Suite.
 * Evaluates RNNoise baseline against Rust-native Spectral Subtraction, Wiener Filter, and Combined algorithms.
 */

package com.khatawas

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.khatawas.audio.RnnoiseProcessor
import com.khatawas.audio.RustDenoiseProcessor
import com.khatawas.rust.RustEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

@RunWith(AndroidJUnit4::class)
class DenoiseComparisonInstrumentedTest {

    companion object {
        private const val TAG = "KHATAWAS_M5_TEST"
        private const val BENCH_TAG = "KHATAWAS_M5_BENCHMARK"
        private const val SAMPLE_RATE = 48000.0
        private const val FRAME_SIZE = 480
    }

    @Before
    fun setUp() {
        assertTrue("Native RustEngine must be loaded", RustEngine.isNativeLoaded())
    }

    @Test
    fun test1_RustDenoiseLifecycleAndModeSwitching() {
        Log.i(TAG, "Executing test1_RustDenoiseLifecycleAndModeSwitching")
        val denoiser = RustDenoiseProcessor(RustDenoiseProcessor.MODE_WIENER_FILTER)
        try {
            assertTrue("Denoise processor must be initialized and available", denoiser.isAvailable)

            val inFrame = FloatArray(FRAME_SIZE) { 500.0f }
            val outFrame = FloatArray(FRAME_SIZE)

            val processed = denoiser.processFrame(inFrame, outFrame)
            assertEquals("Must process exactly 480 samples", FRAME_SIZE, processed)

            assertTrue("Set mode to Spectral Subtraction must succeed", denoiser.setMode(RustDenoiseProcessor.MODE_SPECTRAL_SUBTRACTION))
            assertEquals(FRAME_SIZE, denoiser.processFrame(inFrame, outFrame))

            assertTrue("Set mode to Combined must succeed", denoiser.setMode(RustDenoiseProcessor.MODE_COMBINED))
            assertEquals(FRAME_SIZE, denoiser.processFrame(inFrame, outFrame))

            assertTrue("Reset must succeed", denoiser.reset())
        } finally {
            denoiser.destroy()
            assertFalse("Processor must report unavailable after destroy", denoiser.isAvailable)
        }
    }

    @Test
    fun test2_StationaryNoiseAttenuationKiranaStore() {
        Log.i(TAG, "Executing test2_StationaryNoiseAttenuationKiranaStore")

        val rnnoise = RnnoiseProcessor()
        val rustWiener = RustDenoiseProcessor(RustDenoiseProcessor.MODE_WIENER_FILTER)
        val rustSpectral = RustDenoiseProcessor(RustDenoiseProcessor.MODE_SPECTRAL_SUBTRACTION)
        val rustCombined = RustDenoiseProcessor(RustDenoiseProcessor.MODE_COMBINED)

        try {
            // Generate 1.0 second of stationary Kirana noise: 50Hz mains hum + 100Hz harmonic + 1200Hz refrigerator hum + pseudo-random background
            val totalFrames = 100 // 1000 ms at 10 ms / frame
            val inFrame = FloatArray(FRAME_SIZE)
            val outRnnoise = FloatArray(FRAME_SIZE)
            val outWiener = FloatArray(FRAME_SIZE)
            val outSpectral = FloatArray(FRAME_SIZE)
            val outCombined = FloatArray(FRAME_SIZE)

            var inPowerAcc = 0.0
            var rnnoisePowerAcc = 0.0
            var wienerPowerAcc = 0.0
            var spectralPowerAcc = 0.0
            var combinedPowerAcc = 0.0
            var measuredFrames = 0

            for (f in 0 until totalFrames) {
                for (i in 0 until FRAME_SIZE) {
                    val t = (f * FRAME_SIZE + i) / SAMPLE_RATE
                    val mainsHum = 400.0f * sin(2.0 * PI * 50.0 * t).toFloat()
                    val motorHum = 300.0f * sin(2.0 * PI * 100.0 * t).toFloat()
                    val fridgeHum = 250.0f * sin(2.0 * PI * 1200.0 * t).toFloat()
                    val randNoise = ((i % 17) - 8).toFloat() * 20.0f
                    inFrame[i] = mainsHum + motorHum + fridgeHum + randNoise
                }

                rnnoise.processFrame(inFrame, outRnnoise)
                rustWiener.processFrame(inFrame, outWiener)
                rustSpectral.processFrame(inFrame, outSpectral)
                rustCombined.processFrame(inFrame, outCombined)

                // Measure power after initial 200 ms adaptation period (frame 20 to 100)
                if (f >= 20) {
                    measuredFrames++
                    for (i in 0 until FRAME_SIZE) {
                        inPowerAcc += (inFrame[i] * inFrame[i]).toDouble()
                        rnnoisePowerAcc += (outRnnoise[i] * outRnnoise[i]).toDouble()
                        wienerPowerAcc += (outWiener[i] * outWiener[i]).toDouble()
                        spectralPowerAcc += (outSpectral[i] * outSpectral[i]).toDouble()
                        combinedPowerAcc += (outCombined[i] * outCombined[i]).toDouble()
                    }
                }
            }

            val samples = (measuredFrames * FRAME_SIZE).toDouble()
            val pIn = inPowerAcc / samples
            val pRnnoise = rnnoisePowerAcc / samples
            val pWiener = wienerPowerAcc / samples
            val pSpectral = spectralPowerAcc / samples
            val pCombined = combinedPowerAcc / samples

            val attRnnoise = 10.0 * log10(pIn / pRnnoise.coerceAtLeast(1e-12))
            val attWiener = 10.0 * log10(pIn / pWiener.coerceAtLeast(1e-12))
            val attSpectral = 10.0 * log10(pIn / pSpectral.coerceAtLeast(1e-12))
            val attCombined = 10.0 * log10(pIn / pCombined.coerceAtLeast(1e-12))

            Log.i(BENCH_TAG, "--- Kirana Stationary Noise Attenuation (dB) ---")
            Log.i(BENCH_TAG, "Input Power: $pIn")
            Log.i(BENCH_TAG, "RNNoise C++ Attenuation:              ${"%.2f".format(attRnnoise)} dB")
            Log.i(BENCH_TAG, "Rust Wiener Filter Attenuation:       ${"%.2f".format(attWiener)} dB")
            Log.i(BENCH_TAG, "Rust Spectral Subtraction Attenuation:${"%.2f".format(attSpectral)} dB")
            Log.i(BENCH_TAG, "Rust Combined Engine Attenuation:     ${"%.2f".format(attCombined)} dB")

            assertTrue("RNNoise must suppress stationary noise by >= 5 dB", attRnnoise >= 5.0)
            assertTrue("Rust Wiener filter must suppress stationary noise by >= 6 dB", attWiener >= 6.0)
            assertTrue("Rust Spectral Subtraction must suppress stationary noise by >= 6 dB", attSpectral >= 6.0)
            assertTrue("Rust Combined must suppress stationary noise by >= 6 dB", attCombined >= 6.0)
        } finally {
            rnnoise.destroy()
            rustWiener.destroy()
            rustSpectral.destroy()
            rustCombined.destroy()
        }
    }

    @Test
    fun test3_SpeechPreservationAndDistortion() {
        Log.i(TAG, "Executing test3_SpeechPreservationAndDistortion")

        val rustWiener = RustDenoiseProcessor(RustDenoiseProcessor.MODE_WIENER_FILTER)
        val rustSpectral = RustDenoiseProcessor(RustDenoiseProcessor.MODE_SPECTRAL_SUBTRACTION)

        try {
            // First 15 frames: low ambient background (quiet room calibration)
            val inFrame = FloatArray(FRAME_SIZE)
            val outWiener = FloatArray(FRAME_SIZE)
            val outSpectral = FloatArray(FRAME_SIZE)

            for (f in 0 until 15) {
                for (i in 0 until FRAME_SIZE) {
                    inFrame[i] = ((i % 5) - 2).toFloat() * 10.0f
                }
                rustWiener.processFrame(inFrame, outWiener)
                rustSpectral.processFrame(inFrame, outSpectral)
            }

            // Next 35 frames: Voiced speech vowel [a] (fundamental 130 Hz, formants at 730 Hz, 1090 Hz, 2440 Hz)
            var cleanPowerAcc = 0.0
            var wienerPowerAcc = 0.0
            var spectralPowerAcc = 0.0

            for (f in 15 until 50) {
                for (i in 0 until FRAME_SIZE) {
                    val t = (f * FRAME_SIZE + i) / SAMPLE_RATE
                    val s = 5000.0f * sin(2.0 * PI * 130.0 * t).toFloat() +
                            3500.0f * sin(2.0 * PI * 730.0 * t).toFloat() +
                            2000.0f * sin(2.0 * PI * 1090.0 * t).toFloat() +
                            1200.0f * sin(2.0 * PI * 2440.0 * t).toFloat()
                    inFrame[i] = s
                }

                rustWiener.processFrame(inFrame, outWiener)
                rustSpectral.processFrame(inFrame, outSpectral)

                for (i in 0 until FRAME_SIZE) {
                    cleanPowerAcc += (inFrame[i] * inFrame[i]).toDouble()
                    wienerPowerAcc += (outWiener[i] * outWiener[i]).toDouble()
                    spectralPowerAcc += (outSpectral[i] * outSpectral[i]).toDouble()
                }
            }

            val pClean = cleanPowerAcc
            val ratioWiener = (wienerPowerAcc / pClean) * 100.0
            val ratioSpectral = (spectralPowerAcc / pClean) * 100.0

            Log.i(BENCH_TAG, "--- Clean Speech Power Preservation Ratio (%) ---")
            Log.i(BENCH_TAG, "Rust Wiener Filter Retention:       ${"%.2f".format(ratioWiener)} %")
            Log.i(BENCH_TAG, "Rust Spectral Subtraction Retention:${"%.2f".format(ratioSpectral)} %")

            assertTrue("Rust Wiener must preserve >= 70% clean speech power", ratioWiener >= 70.0)
            assertTrue("Rust Spectral Subtraction must preserve >= 70% clean speech power", ratioSpectral >= 70.0)
        } finally {
            rustWiener.destroy()
            rustSpectral.destroy()
        }
    }

    @Test
    fun test4_MixedSpeechAndNoiseSnrImprovement() {
        Log.i(TAG, "Executing test4_MixedSpeechAndNoiseSnrImprovement")

        val rnnoise = RnnoiseProcessor()
        val rustWiener = RustDenoiseProcessor(RustDenoiseProcessor.MODE_WIENER_FILTER)

        try {
            val inClean = FloatArray(FRAME_SIZE)
            val inNoise = FloatArray(FRAME_SIZE)
            val inMixed = FloatArray(FRAME_SIZE)
            val outRnnoise = FloatArray(FRAME_SIZE)
            val outWiener = FloatArray(FRAME_SIZE)

            // 1. Initial 20 frames of ambient noise calibration
            for (f in 0 until 20) {
                for (i in 0 until FRAME_SIZE) {
                    val t = (f * FRAME_SIZE + i) / SAMPLE_RATE
                    val n = 600.0f * sin(2.0 * PI * 60.0 * t).toFloat() +
                            400.0f * cos(2.0 * PI * 180.0 * t).toFloat()
                    inMixed[i] = n
                }
                rnnoise.processFrame(inMixed, outRnnoise)
                rustWiener.processFrame(inMixed, outWiener)
            }

            // 2. Active speech + noise period (frames 20 to 60)
            var cleanPower = 0.0
            var noisePower = 0.0
            var rnnoiseResidualPower = 0.0
            var wienerResidualPower = 0.0
            var sampleCount = 0

            for (f in 20 until 60) {
                for (i in 0 until FRAME_SIZE) {
                    val t = (f * FRAME_SIZE + i) / SAMPLE_RATE
                    // Clean speech burst (Kirana customer speaking)
                    val s = 2500.0f * sin(2.0 * PI * 350.0 * t).toFloat() +
                            1500.0f * sin(2.0 * PI * 1250.0 * t).toFloat()
                    // Heavy Kirana background noise (60 Hz hum + fan noise)
                    val n = 1500.0f * sin(2.0 * PI * 60.0 * t).toFloat() +
                            1000.0f * cos(2.0 * PI * 180.0 * t).toFloat()

                    inClean[i] = s
                    inNoise[i] = n
                    inMixed[i] = s + n
                }

                rnnoise.processFrame(inMixed, outRnnoise)
                rustWiener.processFrame(inMixed, outWiener)

                sampleCount += FRAME_SIZE
                for (i in 0 until FRAME_SIZE) {
                    cleanPower += (inClean[i] * inClean[i]).toDouble()
                    noisePower += (inNoise[i] * inNoise[i]).toDouble()
                    val diffRnnoise = (outRnnoise[i] - inClean[i]).toDouble()
                    val diffWiener = (outWiener[i] - inClean[i]).toDouble()
                    rnnoiseResidualPower += diffRnnoise * diffRnnoise
                    wienerResidualPower += diffWiener * diffWiener
                }
            }

            val inputSnr = 10.0 * log10(cleanPower / noisePower)
            val rnnoiseOutputSnr = 10.0 * log10(cleanPower / rnnoiseResidualPower)
            val wienerOutputSnr = 10.0 * log10(cleanPower / wienerResidualPower)

            val rnnoiseDeltaSnr = rnnoiseOutputSnr - inputSnr
            val wienerDeltaSnr = wienerOutputSnr - inputSnr

            Log.i(BENCH_TAG, "--- Mixed Speech + Noise SNR Improvement ---")
            Log.i(BENCH_TAG, "Input SNR:                          ${"%.2f".format(inputSnr)} dB")
            Log.i(BENCH_TAG, "RNNoise C++ Output SNR:             ${"%.2f".format(rnnoiseOutputSnr)} dB (Delta: ${"%.2f".format(rnnoiseDeltaSnr)} dB)")
            Log.i(BENCH_TAG, "Rust Wiener Filter Output SNR:      ${"%.2f".format(wienerOutputSnr)} dB (Delta: ${"%.2f".format(wienerDeltaSnr)} dB)")

            assertTrue("Rust Wiener residual noise power must be less than input noise power", wienerResidualPower < noisePower)
        } finally {
            rnnoise.destroy()
            rustWiener.destroy()
        }
    }

    @Test
    fun test5_RealTimeLatencyAndThroughputBenchmark() {
        Log.i(TAG, "Executing test5_RealTimeLatencyAndThroughputBenchmark")

        val rnnoise = RnnoiseProcessor()
        val rustWiener = RustDenoiseProcessor(RustDenoiseProcessor.MODE_WIENER_FILTER)
        val rustSpectral = RustDenoiseProcessor(RustDenoiseProcessor.MODE_SPECTRAL_SUBTRACTION)

        try {
            val benchmarkFrames = 300 // 3.0 seconds of audio data
            val inFrame = FloatArray(FRAME_SIZE) { i -> ((i % 100) - 50).toFloat() * 10.0f }
            val outFrame = FloatArray(FRAME_SIZE)

            // Warm up
            for (i in 0 until 20) {
                rnnoise.processFrame(inFrame, outFrame)
                rustWiener.processFrame(inFrame, outFrame)
                rustSpectral.processFrame(inFrame, outFrame)
            }

            val rnnoiseDurationsUs = LongArray(benchmarkFrames)
            val wienerDurationsUs = LongArray(benchmarkFrames)
            val spectralDurationsUs = LongArray(benchmarkFrames)

            for (f in 0 until benchmarkFrames) {
                val t0 = System.nanoTime()
                rnnoise.processFrame(inFrame, outFrame)
                val t1 = System.nanoTime()
                rnnoiseDurationsUs[f] = (t1 - t0) / 1000

                val t2 = System.nanoTime()
                rustWiener.processFrame(inFrame, outFrame)
                val t3 = System.nanoTime()
                wienerDurationsUs[f] = (t3 - t2) / 1000

                val t4 = System.nanoTime()
                rustSpectral.processFrame(inFrame, outFrame)
                val t5 = System.nanoTime()
                spectralDurationsUs[f] = (t5 - t4) / 1000
            }

            rnnoiseDurationsUs.sort()
            wienerDurationsUs.sort()
            spectralDurationsUs.sort()

            val medianRnnoise = rnnoiseDurationsUs[benchmarkFrames / 2]
            val p95Rnnoise = rnnoiseDurationsUs[(benchmarkFrames * 0.95).toInt()]

            val medianWiener = wienerDurationsUs[benchmarkFrames / 2]
            val p95Wiener = wienerDurationsUs[(benchmarkFrames * 0.95).toInt()]

            val medianSpectral = spectralDurationsUs[benchmarkFrames / 2]
            val p95Spectral = spectralDurationsUs[(benchmarkFrames * 0.95).toInt()]

            val frameDurationUs = 10000.0 // 10 ms = 10,000 us
            val rtfRnnoise = medianRnnoise / frameDurationUs
            val rtfWiener = medianWiener / frameDurationUs
            val rtfSpectral = medianSpectral / frameDurationUs

            Log.i(BENCH_TAG, "=================================================================")
            Log.i(BENCH_TAG, "        KHATAWAS MILESTONE 5 DENOISING LATENCY & RTF            ")
            Log.i(BENCH_TAG, "=================================================================")
            Log.i(BENCH_TAG, "Frame size: 480 samples @ 48 kHz (10,000 us real-time budget)   ")
            Log.i(BENCH_TAG, "RNNoise C++:       Median = $medianRnnoise us | p95 = $p95Rnnoise us | RTF = ${"%.4f".format(rtfRnnoise)}")
            Log.i(BENCH_TAG, "Rust Wiener:       Median = $medianWiener us | p95 = $p95Wiener us | RTF = ${"%.4f".format(rtfWiener)}")
            Log.i(BENCH_TAG, "Rust Spectral Sub: Median = $medianSpectral us | p95 = $p95Spectral us | RTF = ${"%.4f".format(rtfSpectral)}")
            Log.i(BENCH_TAG, "=================================================================")

            // Both engines must operate well below the 10,000 us frame budget (RTF << 1.0)
            assertTrue("RNNoise p95 latency must be < 5000 us", p95Rnnoise < 5000)
            assertTrue("Rust Wiener p95 latency must be < 2000 us", p95Wiener < 2000)
            assertTrue("Rust Wiener RTF must be < 0.05", rtfWiener < 0.05)
        } finally {
            rnnoise.destroy()
            rustWiener.destroy()
            rustSpectral.destroy()
        }
    }

    private fun computeSiSdr(target: DoubleArray, estimate: DoubleArray): Double {
        var dot = 0.0
        var targetNormSq = 0.0
        for (i in target.indices) {
            dot += target[i] * estimate[i]
            targetNormSq += target[i] * target[i]
        }
        val alpha = if (targetNormSq > 1e-12) dot / targetNormSq else 1.0
        var eTarget = 0.0
        var eRes = 0.0
        for (i in target.indices) {
            val sTarget = alpha * target[i]
            val sRes = estimate[i] - sTarget
            eTarget += sTarget * sTarget
            eRes += sRes * sRes
        }
        return if (eRes > 1e-12) 10.0 * log10(eTarget / eRes) else 50.0
    }

    @Test
    fun test6_ObjectiveMetricsSiSdrAndMemory() {
        Log.i(TAG, "Executing test6_ObjectiveMetricsSiSdrAndMemory")

        val rnnoise = RnnoiseProcessor()
        val rustWiener = RustDenoiseProcessor(RustDenoiseProcessor.MODE_WIENER_FILTER)
        val rustSpectral = RustDenoiseProcessor(RustDenoiseProcessor.MODE_SPECTRAL_SUBTRACTION)
        val rustCombined = RustDenoiseProcessor(RustDenoiseProcessor.MODE_COMBINED)

        try {
            val frames = 40
            val totalSamples = frames * FRAME_SIZE
            val cleanSignal = DoubleArray(totalSamples)
            val noisySignal = DoubleArray(totalSamples)
            val outRnnoise = DoubleArray(totalSamples)
            val outWiener = DoubleArray(totalSamples)
            val outSpectral = DoubleArray(totalSamples)
            val outCombined = DoubleArray(totalSamples)

            val inFrame = FloatArray(FRAME_SIZE)
            val outFrameRnn = FloatArray(FRAME_SIZE)
            val outFrameWie = FloatArray(FRAME_SIZE)
            val outFrameSpe = FloatArray(FRAME_SIZE)
            val outFrameCom = FloatArray(FRAME_SIZE)

            // Warm up / calibrate first 15 frames with ambient
            for (f in 0 until 15) {
                for (i in 0 until FRAME_SIZE) {
                    val t = (f * FRAME_SIZE + i) / SAMPLE_RATE
                    inFrame[i] = (400.0f * sin(2.0 * PI * 60.0 * t)).toFloat()
                }
                rnnoise.processFrame(inFrame, outFrameRnn)
                rustWiener.processFrame(inFrame, outFrameWie)
                rustSpectral.processFrame(inFrame, outFrameSpe)
                rustCombined.processFrame(inFrame, outFrameCom)
            }

            // Generate mixed speech and noise
            for (f in 0 until frames) {
                for (i in 0 until FRAME_SIZE) {
                    val idx = f * FRAME_SIZE + i
                    val t = idx / SAMPLE_RATE
                    val s = (3000.0f * sin(2.0 * PI * 350.0 * t) + 1800.0f * sin(2.0 * PI * 1200.0 * t)).toFloat()
                    val n = (1200.0f * sin(2.0 * PI * 60.0 * t) + 800.0f * cos(2.0 * PI * 180.0 * t)).toFloat()
                    cleanSignal[idx] = s.toDouble()
                    noisySignal[idx] = (s + n).toDouble()
                    inFrame[i] = s + n
                }

                rnnoise.processFrame(inFrame, outFrameRnn)
                rustWiener.processFrame(inFrame, outFrameWie)
                rustSpectral.processFrame(inFrame, outFrameSpe)
                rustCombined.processFrame(inFrame, outFrameCom)

                for (i in 0 until FRAME_SIZE) {
                    val idx = f * FRAME_SIZE + i
                    outRnnoise[idx] = outFrameRnn[i].toDouble()
                    outWiener[idx] = outFrameWie[i].toDouble()
                    outSpectral[idx] = outFrameSpe[i].toDouble()
                    outCombined[idx] = outFrameCom[i].toDouble()
                }
            }

            val sisdrNoisy = computeSiSdr(cleanSignal, noisySignal)
            val sisdrRnnoise = computeSiSdr(cleanSignal, outRnnoise)
            val sisdrWiener = computeSiSdr(cleanSignal, outWiener)
            val sisdrSpectral = computeSiSdr(cleanSignal, outSpectral)
            val sisdrCombined = computeSiSdr(cleanSignal, outCombined)

            val runtime = Runtime.getRuntime()
            val usedMemoryKb = (runtime.totalMemory() - runtime.freeMemory()) / 1024

            Log.i(BENCH_TAG, "=================================================================")
            Log.i(BENCH_TAG, "       KHATAWAS OBJECTIVE METRICS: SI-SDR & MEMORY               ")
            Log.i(BENCH_TAG, "=================================================================")
            Log.i(BENCH_TAG, "Input Noisy SI-SDR:               ${"%.2f".format(sisdrNoisy)} dB")
            Log.i(BENCH_TAG, "RNNoise C++ Output SI-SDR:        ${"%.2f".format(sisdrRnnoise)} dB (Delta: ${"%.2f".format(sisdrRnnoise - sisdrNoisy)} dB)")
            Log.i(BENCH_TAG, "Rust Wiener Filter SI-SDR:        ${"%.2f".format(sisdrWiener)} dB (Delta: ${"%.2f".format(sisdrWiener - sisdrNoisy)} dB)")
            Log.i(BENCH_TAG, "Rust Spectral Subtraction SI-SDR: ${"%.2f".format(sisdrSpectral)} dB (Delta: ${"%.2f".format(sisdrSpectral - sisdrNoisy)} dB)")
            Log.i(BENCH_TAG, "Rust Combined Engine SI-SDR:      ${"%.2f".format(sisdrCombined)} dB (Delta: ${"%.2f".format(sisdrCombined - sisdrNoisy)} dB)")
            Log.i(BENCH_TAG, "Process Heap Memory Used:         $usedMemoryKb KB")
            Log.i(BENCH_TAG, "=================================================================")

            assertTrue("Rust Wiener SI-SDR must improve over input noisy SI-SDR", sisdrWiener > sisdrNoisy)
        } finally {
            rnnoise.destroy()
            rustWiener.destroy()
            rustSpectral.destroy()
            rustCombined.destroy()
        }
    }
}
