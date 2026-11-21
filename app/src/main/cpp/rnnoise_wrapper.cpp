/*
 * Copyright (c) 2026 KHATAWAS Project
 * Simplified Technical English (ASD STE-100) compliant.
 * Source file for RNNoise JNI wrapper.
 */

#include "rnnoise_wrapper.h"
#include <android/log.h>
#include <mutex>

#define LOG_TAG "KhatawasRnnoise"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// RNNoise operates on frames of exactly 480 samples at 48000 Hz (10 ms duration)
static constexpr int RNNOISE_FRAME_SIZE = 480;

// Global handle for the RNNoise denoise state
static DenoiseState *g_denoise_state = nullptr;

// Mutex lock to ensure thread safety
static std::mutex g_state_mutex;

// Static pre-allocated buffers to prevent memory allocation during real-time processing
static float s_in_frame[RNNOISE_FRAME_SIZE];
static float s_out_frame[RNNOISE_FRAME_SIZE];

extern "C" {

/*
 * Initialize the RNNoise engine.
 * Calls rnnoise_create(NULL).
 * The embedded neural network weights from rnnoise_data.h are loaded automatically.
 */
JNIEXPORT void JNICALL
Java_com_khatawas_audio_RnnoiseProcessor_nativeInit(
    JNIEnv * /* env */,
    jobject /* thiz */
) {
    std::lock_guard<std::mutex> lock(g_state_mutex);

    // Free previous state if already initialized
    if (g_denoise_state != nullptr) {
        rnnoise_destroy(g_denoise_state);
        g_denoise_state = nullptr;
    }

    // Call rnnoise_create with NULL. Embedded model weights are used.
    g_denoise_state = rnnoise_create(nullptr);
    if (g_denoise_state == nullptr) {
        LOGE("Failed to create RNNoise state with embedded weights");
        return;
    }

    LOGI("RNNoise state initialized successfully");
}

/*
 * Process a single audio frame of 480 float32 PCM samples.
 * Uses stack/static buffers and GetFloatArrayRegion to ensure zero Java object allocations.
 */
JNIEXPORT void JNICALL
Java_com_khatawas_audio_RnnoiseProcessor_nativeProcessFrame(
    JNIEnv *env,
    jobject /* thiz */,
    jfloatArray input,
    jfloatArray output
) {
    if (input == nullptr || output == nullptr) {
        LOGE("Input or output buffer is null");
        return;
    }

    std::lock_guard<std::mutex> lock(g_state_mutex);

    if (g_denoise_state == nullptr) {
        LOGE("RNNoise state is not initialized");
        return;
    }

    // Copy 480 samples from Java array without allocating new memory
    env->GetFloatArrayRegion(input, 0, RNNOISE_FRAME_SIZE, s_in_frame);

    // Process audio frame through RNNoise neural network
    rnnoise_process_frame(g_denoise_state, s_out_frame, s_in_frame);

    // Write cleaned audio samples back to Java output array
    env->SetFloatArrayRegion(output, 0, RNNOISE_FRAME_SIZE, s_out_frame);
}

/*
 * Destroy the RNNoise engine and release native memory.
 */
JNIEXPORT void JNICALL
Java_com_khatawas_audio_RnnoiseProcessor_nativeDestroy(
    JNIEnv * /* env */,
    jobject /* thiz */
) {
    std::lock_guard<std::mutex> lock(g_state_mutex);

    if (g_denoise_state != nullptr) {
        rnnoise_destroy(g_denoise_state);
        g_denoise_state = nullptr;
        LOGI("RNNoise state destroyed and memory released");
    }
}

} // extern "C"
