/*
 * Copyright (c) 2026 KHATAWAS Project
 * Simplified Technical English (ASD STE-100) compliant.
 * Header file for RNNoise JNI wrapper.
 */

#ifndef KHATAWAS_RNNOISE_WRAPPER_H
#define KHATAWAS_RNNOISE_WRAPPER_H

#include <jni.h>
#include <rnnoise.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Initialize the RNNoise engine with embedded neural network weights.
 */
JNIEXPORT void JNICALL
Java_com_khatawas_audio_RnnoiseProcessor_nativeInit(
    JNIEnv *env,
    jobject thiz
);

/*
 * Process a single audio frame of 480 float32 PCM samples.
 * Parameter input: FloatArray containing 480 input samples.
 * Parameter output: FloatArray receiving 480 cleaned samples.
 */
JNIEXPORT void JNICALL
Java_com_khatawas_audio_RnnoiseProcessor_nativeProcessFrame(
    JNIEnv *env,
    jobject thiz,
    jfloatArray input,
    jfloatArray output
);

/*
 * Destroy the RNNoise engine and release native memory.
 */
JNIEXPORT void JNICALL
Java_com_khatawas_audio_RnnoiseProcessor_nativeDestroy(
    JNIEnv *env,
    jobject thiz
);

#ifdef __cplusplus
}
#endif

#endif /* KHATAWAS_RNNOISE_WRAPPER_H */
