/*
 * Copyright (c) 2026 KHATAWAS Project
 * Comprehensive DSP unit test suite for 31-tap 48 kHz to 16 kHz FIR decimator.
 */

package com.khatawas.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class FirDecimator3Test {

    @Test
    fun producesExactlyOneThirdOutputSamples() {
        val input = FloatArray(480) { 1000f }
        val output = FloatArray(160)
        val count = FirDecimator3().process(input, output)
        assertEquals("480 input samples must produce exactly 160 output samples", 160, count)
    }

    @Test
    fun preservesDcGainAfterFilterStartup() {
        val input = FloatArray(480 * 8) { 1200f }
        val output = FloatArray(input.size / 3)
        val count = FirDecimator3().process(input, output)
        val steady = output.drop(20).take(count - 20)
        val mean = steady.average()
        assertTrue("DC gain should remain close to unity: $mean", abs(mean - 1200.0) < 2.0)
    }

    @Test
    fun processesDigitalSilenceWithoutDrift() {
        val input = FloatArray(480 * 4) { 0f }
        val output = FloatArray(input.size / 3)
        val count = FirDecimator3().process(input, output)
        for (i in 0 until count) {
            assertEquals("Silence input must produce exact zero output", 0.0f, output[i], 0.000001f)
        }
    }

    @Test
    fun attenuatesFrequenciesAboveOutputNyquist() {
        fun rmsFor(frequencyHz: Double): Double {
            val input = FloatArray(480 * 20) { index ->
                sin(2.0 * PI * frequencyHz * index / 48000.0).toFloat()
            }
            val output = FloatArray(input.size / 3)
            val count = FirDecimator3().process(input, output)
            val steady = output.drop(20).take(count - 20)
            return sqrt(steady.sumOf { it.toDouble() * it.toDouble() } / steady.size)
        }

        val passband1k = rmsFor(1000.0)
        val passband7k = rmsFor(7000.0)
        val stopband8k = rmsFor(8000.0)
        val stopband12k = rmsFor(12000.0)
        val stopband16k = rmsFor(16000.0)

        assertTrue("1 kHz passband tone should remain present: $passband1k", passband1k > 0.60)
        assertTrue("7 kHz edge tone should have moderate attenuation: $passband7k", passband7k > 0.30)
        assertTrue("8 kHz Nyquist boundary should be attenuated: $stopband8k", stopband8k < passband1k * 0.70)
        assertTrue("12 kHz alias must be heavily attenuated: $stopband12k", stopband12k < passband1k * 0.10)
        assertTrue("16 kHz alias must be heavily attenuated: $stopband16k", stopband16k < passband1k * 0.05)
    }

    @Test
    fun preservesFilterStateAcrossFrameBoundaries() {
        val input = FloatArray(960) { index ->
            sin(2.0 * PI * 1000.0 * index / 48000.0).toFloat()
        }

        // Single continuous run
        val singleOutput = FloatArray(320)
        FirDecimator3().process(input, singleOutput)

        // Chunked 480-sample runs with state preservation
        val firstBuffer = FloatArray(160)
        val secondBuffer = FloatArray(160)
        val resampler = FirDecimator3()
        resampler.process(input.copyOfRange(0, 480), firstBuffer)
        resampler.process(input.copyOfRange(480, 960), secondBuffer)

        // Compare second frame: must match singleOutput exactly
        for (i in 0 until 160) {
            val expected = singleOutput[160 + i]
            val actual = secondBuffer[i]
            assertEquals("Frame boundary state must be continuous at index $i", expected, actual, 0.0001f)
        }
    }

    @Test
    fun verifiesImpulseResponse() {
        val input = FloatArray(480) { 0f }
        input[0] = 1.0f // Unit impulse at t=0
        val output = FloatArray(160)
        val count = FirDecimator3().process(input, output)
        assertEquals(160, count)
        // Energy must be bounded
        val energy = output.sumOf { (it * it).toDouble() }
        assertTrue("Impulse response energy must be stable and finite: $energy", energy > 0.0 && energy < 2.0)
    }
}
