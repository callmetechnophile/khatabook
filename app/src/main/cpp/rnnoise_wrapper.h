/*
 * Copyright (c) 2026 KHATAWAS Project
 * Simplified Technical English (ASD STE-100) compliant.
 * Header file for RNNoise JNI wrapper with per-instance state handle.
 */

#ifndef KHATAWAS_RNNOISE_WRAPPER_H
#define KHATAWAS_RNNOISE_WRAPPER_H

#include <jni.h>
#include <rnnoise.h>
#include <cmath>
#include <cstdint>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Initialize an RNNoise instance with embedded neural network weights.
 * Returns 64-bit native pointer to DenoiseState as jlong.
 */
JNIEXPORT jlong JNICALL
Java_com_khatawas_audio_RnnoiseProcessor_nativeInit(
    JNIEnv *env,
    jobject thiz
);

/*
 * Process a single audio frame of 480 float32 PCM samples for a specific instance.
 * Parameter handle: 64-bit native pointer to DenoiseState.
 * Parameter input: FloatArray containing at least 480 input samples.
 * Parameter output: FloatArray receiving at least 480 cleaned samples.
 */
JNIEXPORT void JNICALL
Java_com_khatawas_audio_RnnoiseProcessor_nativeProcessFrame(
    JNIEnv *env,
    jobject thiz,
    jlong handle,
    jfloatArray input,
    jfloatArray output
);

/*
 * Destroy the RNNoise instance and release native memory.
 * Parameter handle: 64-bit native pointer to DenoiseState.
 */
JNIEXPORT void JNICALL
Java_com_khatawas_audio_RnnoiseProcessor_nativeDestroy(
    JNIEnv *env,
    jobject thiz,
    jlong handle
);

#ifdef __cplusplus
}
#endif

#endif /* KHATAWAS_RNNOISE_WRAPPER_H */
