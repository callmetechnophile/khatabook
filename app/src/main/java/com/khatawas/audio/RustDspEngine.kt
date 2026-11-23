package com.khatawas.audio

/**
 * Native FIR/PCM stage. One instance per capture session; call from one thread.
 * The library is currently built for ARM64 only.
 */
internal class RustDspEngine : AutoCloseable {
    companion object {
        init { System.loadLibrary("khatawas_dsp") }
    }

    private var handle: Long = nativeCreate()

    fun process(filtered48k: FloatArray, pcm16k: ByteArray): Int {
        check(handle != 0L) { "DSP engine is closed" }
        require(filtered48k.size == 480)
        require(pcm16k.size >= 320)
        return nativeProcess(handle, filtered48k, pcm16k)
    }

    fun reset() {
        check(handle != 0L) { "DSP engine is closed" }
        nativeReset(handle)
    }

    override fun close() {
        val current = handle
        if (current != 0L) {
            handle = 0L
            nativeDestroy(current)
        }
    }

    private external fun nativeCreate(): Long
    private external fun nativeProcess(handle: Long, input: FloatArray, output: ByteArray): Int
    private external fun nativeReset(handle: Long)
    private external fun nativeDestroy(handle: Long)
}
