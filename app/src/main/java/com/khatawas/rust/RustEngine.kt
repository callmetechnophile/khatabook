package com.khatawas.rust

/**
 * JNI bindings for the KHATAWAS Rust computational engine.
 * Connects Android platform code to the khatawas_android and khatawas_core workspace.
 */
object RustEngine {
    private const val TAG = "RustEngine"
    private var isLoaded = false

    init {
        try {
            System.loadLibrary("khatawas_android")
            isLoaded = true
            safeLogI(TAG, "Successfully loaded libkhatawas_android.so")
        } catch (e: Throwable) {
            safeLogW(TAG, "Native library libkhatawas_android.so not loaded in host environment: ${e.message}")
            isLoaded = false
        }
    }

    private fun safeLogI(tag: String, msg: String) {
        try {
            android.util.Log.i(tag, msg)
        } catch (_: Throwable) {
            // JVM host unit test environment without Android framework mock
        }
    }

    private fun safeLogW(tag: String, msg: String) {
        try {
            android.util.Log.w(tag, msg)
        } catch (_: Throwable) {
            // JVM host unit test environment without Android framework mock
        }
    }

    fun isNativeLoaded(): Boolean = isLoaded

    external fun nativeGetVersion(): String
    external fun nativeAdd(a: Int, b: Int): Int
    external fun nativeHealthCheck(): Boolean

    // Milestone 3: Stateful FIR Handle Management
    external fun nativeFirInit(): Long
    external fun nativeFirProcessHandle(handle: Long, input: FloatArray, output: FloatArray): Int
    external fun nativeFirResetHandle(handle: Long): Boolean
    external fun nativeFirDestroyHandle(handle: Long): Boolean

    // Legacy / Convenience FIR Process
    external fun nativeFirProcess(input: FloatArray, output: FloatArray): Int

    // Milestone 4: Stateful Audio Pipeline Core Management
    external fun nativeAudioPipelineCreate(): Long
    external fun nativeAudioPipelineProcessFloatFrame(handle: Long, inFloats: FloatArray, outPcm16Bytes: ByteArray): Int
    external fun nativeAudioPipelinePushAndProcessPcm16(handle: Long, inPcm16Bytes: ByteArray, outPcm16Bytes: ByteArray): Int
    external fun nativeAudioPipelineReset(handle: Long): Boolean
    external fun nativeAudioPipelineDestroy(handle: Long): Boolean
    external fun nativeAudioPipelineGetProcessedFrames(handle: Long): Long

    // Milestone 5: Stateful Denoising Engine Management
    const val DENOISE_MODE_SPECTRAL_SUBTRACTION = 0
    const val DENOISE_MODE_WIENER_FILTER = 1
    const val DENOISE_MODE_COMBINED = 2

    external fun nativeDenoiseCreate(mode: Int): Long
    external fun nativeDenoiseDestroy(handle: Long): Boolean
    external fun nativeDenoiseReset(handle: Long): Boolean
    external fun nativeDenoiseSetMode(handle: Long, mode: Int): Boolean
    external fun nativeDenoiseProcess(handle: Long, inFloats: FloatArray, outFloats: FloatArray): Int
    external fun nativeDenoiseProcessDirect(mode: Int, inFloats: FloatArray, outFloats: FloatArray): Int

    external fun nativeParseUtterance(utterance: String): String
    external fun nativeCalculateBalance(credits: LongArray, payments: LongArray, reversals: LongArray): Long
}
