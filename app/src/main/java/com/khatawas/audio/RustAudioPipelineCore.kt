/*
 * Copyright (c) 2026 KHATAWAS Project
 * Rust Audio Pipeline Core lifecycle wrapper.
 */

package com.khatawas.audio

import com.khatawas.rust.RustEngine
import java.io.Closeable

class RustAudioPipelineCore : Closeable {

    private var handle: Long = 0L

    init {
        if (RustEngine.isNativeLoaded()) {
            handle = RustEngine.nativeAudioPipelineCreate()
        }
    }

    val isNativeActive: Boolean
        get() = handle != 0L

    fun processFloatFrame(inFloats: FloatArray, outPcm16Bytes: ByteArray): Int {
        val currentHandle = handle
        if (currentHandle == 0L) return 0
        return RustEngine.nativeAudioPipelineProcessFloatFrame(currentHandle, inFloats, outPcm16Bytes)
    }

    fun pushAndProcessPcm16(inPcm16Bytes: ByteArray, outPcm16Bytes: ByteArray): Int {
        val currentHandle = handle
        if (currentHandle == 0L) return 0
        return RustEngine.nativeAudioPipelinePushAndProcessPcm16(currentHandle, inPcm16Bytes, outPcm16Bytes)
    }

    fun reset(): Boolean {
        val currentHandle = handle
        if (currentHandle == 0L) return false
        return RustEngine.nativeAudioPipelineReset(currentHandle)
    }

    fun getProcessedFrames(): Long {
        val currentHandle = handle
        if (currentHandle == 0L) return 0L
        return RustEngine.nativeAudioPipelineGetProcessedFrames(currentHandle)
    }

    override fun close() {
        val currentHandle = handle
        if (currentHandle != 0L) {
            RustEngine.nativeAudioPipelineDestroy(currentHandle)
            handle = 0L
        }
    }
}
