package com.khatawas.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sqrt
import kotlin.math.sin

class FirDecimator3Test {

    @Test
    fun producesExactlyOneThirdOutputSamples() {
        val input = FloatArray(480) { 1000f }
        val output = FloatArray(160)
        val count = FirDecimator3().process(input, output)
        assertEquals(160, count)
    }

    @Test
    fun preservesDcGainAfterFilterStartup() {
        val input = FloatArray(480 * 8) { 1200f }
        val output = FloatArray(input.size / 3)
        val count = FirDecimator3().process(input, output)
        val steady = output.drop(20).take(count - 20)
        val mean = steady.average()
        assertTrue("DC gain should remain close to unity: $mean", kotlin.math.abs(mean - 1200.0) < 2.0)
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

        val passbandRms = rmsFor(1000.0)
        val stopbandRms = rmsFor(12000.0)
        assertTrue("Passband tone should remain present: $passbandRms", passbandRms > 0.60)
        assertTrue("12 kHz alias should be attenuated: $stopbandRms", stopbandRms < passbandRms * 0.10)
    }

    @Test
    fun preservesFilterStateAcrossFrameBoundaries() {
        val input = FloatArray(960) { index ->
            sin(2.0 * PI * 1000.0 * index / 48000.0).toFloat()
        }

        val singleOutput = FloatArray(320)
        FirDecimator3().process(input, singleOutput)

        val splitOutput = FloatArray(320)
        val splitResampler = FirDecimator3()
        val first = splitResampler.process(input.copyOfRange(0, 480), FloatArray(160))
        val firstBuffer = FloatArray(160)
        val secondBuffer = FloatArray(160)
        val resampler = FirDecimator3()
        resampler.process(input.copyOfRange(0, 480), firstBuffer)
        val second = resampler.process(input.copyOfRange(480, 960), secondBuffer)
        assertEquals(160, first)
        assertEquals(160, second)
        for (i in 0 until 160) {
            assertEquals(singleOutput[i], firstBuffer[i], 1e-5f)
            assertEquals(singleOutput[i + 160], secondBuffer[i], 1e-5f)
        }
    }
}
