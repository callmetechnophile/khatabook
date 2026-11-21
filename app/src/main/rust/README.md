KHATAWAS Rust DSP — Stage 1
============================

This crate ports the existing stateful 31-tap FIR decimator and PCM16 little-endian encoder to Rust. Kotlin still captures audio and runs the current RNNoise stage. It passes one 480-sample float frame to Rust; Rust emits 320 bytes (160 PCM16 samples) at 16 kHz.

Initial Android target: ARM64 (`aarch64-linux-android`). ARMv7 is deferred until ARM64 correctness and device performance are verified.

Build prerequisites: Rust toolchain, Android NDK, Android ARM64 target, and cargo-ndk.

    rustup target add aarch64-linux-android
    cargo install cargo-ndk
    cd app/src/main/rust
    cargo test
    cargo ndk -t arm64-v8a -o ../../jniLibs build --release

Expected shared library: `app/src/main/jniLibs/arm64-v8a/libkhatawas_dsp.so`.

Integration status: this crate and JNI wrapper are scaffolding only. Gradle is not yet configured to invoke Cargo, and `AudioPipeline` still uses Kotlin `FirDecimator3`. Do not enable the Rust path until Rust tests, Android native build, and old-vs-new output comparison pass. The JNI handle is per recording session; do not call process/reset/destroy concurrently.
