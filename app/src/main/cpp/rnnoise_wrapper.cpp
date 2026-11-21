/*
 * Copyright (c) 2026 KHATAWAS Project
 * Simplified Technical English (ASD STE-100) compliant.
 * Per-instance RNNoise JNI implementation with explicit lifecycle and array bounds validation.
 */

#include "rnnoise_wrapper.h"
#include <android/log.h>
#include <cmath>
#include <mutex>

#define LOG_TAG "KhatawasRnnoise"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static constexpr int RNNOISE_FRAME_SIZE = 480;

extern "C" {

/*
 * Initialize a new RNNoise engine instance.
 * Returns the native DenoiseState address cast to jlong, or 0 on failure.
 */
JNIEXPORT jlong JNICALL
Java_com_khatawas_audio_RnnoiseProcessor_nativeInit(
    JNIEnv * /* env */,
    jobject /* thiz */
) {
    // Call rnnoise_create with NULL to load embedded model weights
    DenoiseState *state = rnnoise_create(nullptr);
    if (state == nullptr) {
        LOGE("Failed to allocate RNNoise state");
        return 0;
    }

    LOGI("RNNoise instance %p allocated successfully", state);
    return reinterpret_cast<jlong>(state);
}

/*
 * Process a single audio frame of 480 float32 PCM samples for the specified instance.
 * Validates handle, pointers, and array lengths before accessing buffers.
 */
JNIEXPORT void JNICALL
Java_com_khatawas_audio_RnnoiseProcessor_nativeProcessFrame(
    JNIEnv *env,
    jobject /* thiz */,
    jlong handle,
    jfloatArray input,
    jfloatArray output
) {
    if (handle == 0) {
        LOGE("RNNoise processFrame called with null native handle");
        return;
    }

    if (input == nullptr || output == nullptr) {
        LOGE("RNNoise processFrame called with null input or output array");
        return;
    }

    jsize inLen = env->GetArrayLength(input);
    jsize outLen = env->GetArrayLength(output);
    if (inLen < RNNOISE_FRAME_SIZE || outLen < RNNOISE_FRAME_SIZE) {
        LOGE("Invalid array length: in=%d, out=%d (expected at least %d)", inLen, outLen, RNNOISE_FRAME_SIZE);
        return;
    }

    DenoiseState *state = reinterpret_cast<DenoiseState *>(handle);

    // Frame buffers allocated on stack to ensure thread safety without shared global state
    float inFrame[RNNOISE_FRAME_SIZE];
    float outFrame[RNNOISE_FRAME_SIZE];

    env->GetFloatArrayRegion(input, 0, RNNOISE_FRAME_SIZE, inFrame);

    // Sanitize input values: ensure no NaN or Infinity enters RNNoise GRU layers
    for (int i = 0; i < RNNOISE_FRAME_SIZE; ++i) {
        float s = inFrame[i];
        if (std::isnan(s) || std::isinf(s)) {
            inFrame[i] = 0.0f;
        }
    }

    // Process audio frame through RNNoise neural network
    rnnoise_process_frame(state, outFrame, inFrame);

    // Sanitize and clamp output to [-32768.0f, 32767.0f] PCM16 range
    for (int i = 0; i < RNNOISE_FRAME_SIZE; ++i) {
        float s = outFrame[i];
        if (std::isnan(s) || std::isinf(s)) {
            s = 0.0f;
        } else if (s > 32767.0f) {
            s = 32767.0f;
        } else if (s < -32768.0f) {
            s = -32768.0f;
        }
        outFrame[i] = s;
    }

    env->SetFloatArrayRegion(output, 0, RNNOISE_FRAME_SIZE, outFrame);
}

/*
 * Destroy the RNNoise engine instance and release native memory.
 */
JNIEXPORT void JNICALL
Java_com_khatawas_audio_RnnoiseProcessor_nativeDestroy(
    JNIEnv * /* env */,
    jobject /* thiz */,
    jlong handle
) {
    if (handle == 0) {
        return;
    }

    DenoiseState *state = reinterpret_cast<DenoiseState *>(handle);
    rnnoise_destroy(state);
    LOGI("RNNoise instance %p destroyed successfully", state);
}

}
