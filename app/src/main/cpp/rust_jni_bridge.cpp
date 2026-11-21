/*
 * Copyright (c) 2026 KHATAWAS Project
 * JNI bridge linking Android Kotlin runtime to Rust-first computational engine.
 */

#include <jni.h>
#include <string>
#include <vector>

extern "C" {
    const char* rust_engine_version();
    int rust_fir_process(const float* input, size_t in_len, float* output, size_t out_len);
    void rust_fir_reset();
    int rust_parse_utterance(const char* input_utf8, char* out_buf, size_t out_capacity);
    int64_t rust_calculate_balance(const int64_t* credits, size_t c_len,
                                   const int64_t* payments, size_t p_len,
                                   const int64_t* reversals, size_t r_len);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_khatawas_rust_RustEngine_nativeGetVersion(JNIEnv* env, jobject /* this */) {
    return env->NewStringUTF(rust_engine_version());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_khatawas_rust_RustEngine_nativeHealthCheck(JNIEnv* /* env */, jobject /* this */) {
    return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_khatawas_rust_RustEngine_nativeFirProcess(JNIEnv* env, jobject /* this */,
                                                  jfloatArray inputArr, jfloatArray outputArr) {
    if (!inputArr || !outputArr) return 0;
    jsize inLen = env->GetArrayLength(inputArr);
    jsize outLen = env->GetArrayLength(outputArr);
    if (inLen == 0 || outLen == 0) return 0;

    std::vector<float> inBuf(inLen);
    std::vector<float> outBuf(outLen);
    env->GetFloatArrayRegion(inputArr, 0, inLen, inBuf.data());

    int written = rust_fir_process(inBuf.data(), inLen, outBuf.data(), outLen);
    if (written > 0) {
        env->SetFloatArrayRegion(outputArr, 0, written, outBuf.data());
    }
    return written;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_khatawas_rust_RustEngine_nativeParseUtterance(JNIEnv* env, jobject /* this */,
                                                      jstring utterance) {
    if (!utterance) return env->NewStringUTF("{\"status\":\"Failure\",\"error_message\":\"Null utterance\"}");
    const char* nativeStr = env->GetStringUTFChars(utterance, nullptr);
    if (!nativeStr) return env->NewStringUTF("{\"status\":\"Failure\",\"error_message\":\"Failed to read string\"}");

    char outBuf[8192];
    int res = rust_parse_utterance(nativeStr, outBuf, sizeof(outBuf));
    env->ReleaseStringUTFChars(utterance, nativeStr);

    if (res < 0) {
        return env->NewStringUTF("{\"status\":\"Failure\",\"error_message\":\"Parsing error in native engine\"}");
    }
    return env->NewStringUTF(outBuf);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_khatawas_rust_RustEngine_nativeCalculateBalance(JNIEnv* env, jobject /* this */,
                                                        jlongArray credits, jlongArray payments, jlongArray reversals) {
    jsize cLen = credits ? env->GetArrayLength(credits) : 0;
    jsize pLen = payments ? env->GetArrayLength(payments) : 0;
    jsize rLen = reversals ? env->GetArrayLength(reversals) : 0;

    std::vector<int64_t> cBuf(cLen);
    std::vector<int64_t> pBuf(pLen);
    std::vector<int64_t> rBuf(rLen);

    if (cLen > 0) env->GetLongArrayRegion(credits, 0, cLen, cBuf.data());
    if (pLen > 0) env->GetLongArrayRegion(payments, 0, pLen, pBuf.data());
    if (rLen > 0) env->GetLongArrayRegion(reversals, 0, rLen, rBuf.data());

    return rust_calculate_balance(cBuf.data(), cLen, pBuf.data(), pLen, rBuf.data(), rLen);
}
