# KHATAWAS — Rust-Android Studio Integration Architecture (Milestone 2)

## 1. Executive Summary

This document establishes the architecture, toolchain configuration, Gradle lifecycle hooks, JNI binding specifications, and memory-safety invariants for KHATAWAS's Rust-first computational engine (`librust_khatawas.so`).

As mandated by project rules, **Android Studio is the primary and official development, build, and test environment**. All native compilation is completely automated inside the Gradle build lifecycle: opening the project in Android Studio and clicking **Build** or **Run** automatically compiles the Rust computational engine, links it via CMake and the Android NDK Clang toolchain, and packages the resulting `.so` binaries into the APK across all target ABIs without requiring manual ADB commands, scripts, or manual library copying.

---

## 2. Target ABIs & Toolchain Architecture

KHATAWAS targets physical devices (Vivo / iQOO Z9x, ARM64, Android 15) while supporting the full local Android Studio developer workflow (including x86_64 emulator execution):

| Android ABI | Rust Target Triple | Android Min SDK | Linker Command / Binary |
|---|---|---|---|
| `arm64-v8a` | `aarch64-linux-android` | API 24 | `aarch64-linux-android24-clang.cmd` |
| `armeabi-v7a` | `armv7-linux-androideabi` | API 24 | `armv7a-linux-androideabi24-clang.cmd` |
| `x86_64` | `x86_64-linux-android` | API 24 | `x86_64-linux-android24-clang.cmd` |

### 2.1 Cargo Target Configuration (`.cargo/config.toml`)
Cargo is configured to use the Android NDK (version `27.1.12297006`) prebuilt LLVM toolchains:
```toml
[target.aarch64-linux-android]
linker = "C:/Users/worka/AppData/Local/Android/Sdk/ndk/27.1.12297006/toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android24-clang.cmd"

[target.armv7-linux-androideabi]
linker = "C:/Users/worka/AppData/Local/Android/Sdk/ndk/27.1.12297006/toolchains/llvm/prebuilt/windows-x86_64/bin/armv7a-linux-androideabi24-clang.cmd"

[target.x86_64-linux-android]
linker = "C:/Users/worka/AppData/Local/Android/Sdk/ndk/27.1.12297006/toolchains/llvm/prebuilt/windows-x86_64/bin/x86_64-linux-android24-clang.cmd"
```

---

## 3. Automated Android Studio Gradle Lifecycle Hooks

In `app/build.gradle`, native compilation is bound directly to `preBuild`:

```groovy
task buildRustNative {
    description = "Compiles rust-engine for all active Android ABIs via Cargo"
    doLast {
        def isWindows = org.gradle.internal.os.OperatingSystem.current().isWindows()
        def cargoCmd = isWindows ? "cargo.exe" : "cargo"
        def targets = ["aarch64-linux-android", "armv7-linux-androideabi", "x86_64-linux-android"]
        targets.each { target ->
            def pb = new ProcessBuilder(cargoCmd, "build", "--target", target, "--release")
            pb.directory(file("${project.rootDir}/rust-engine"))
            pb.inheritIO()
            def proc = pb.start()
            def exitCode = proc.waitFor()
            if (exitCode != 0) {
                throw new GradleException("Cargo build failed for target ${target} with exit code ${exitCode}")
            }
        }
    }
}

preBuild.dependsOn buildRustNative
```

### 3.1 CMake Native Integration (`app/src/main/cpp/CMakeLists.txt`)
CMake automatically links the static compilation artifact (`librust_khatawas.a`) with the C++ JNI boundary layer into `librust_khatawas.so`:

```cmake
if(${ANDROID_ABI} STREQUAL "arm64-v8a")
    set(RUST_TARGET "aarch64-linux-android")
elseif(${ANDROID_ABI} STREQUAL "armeabi-v7a")
    set(RUST_TARGET "armv7-linux-androideabi")
elseif(${ANDROID_ABI} STREQUAL "x86_64")
    set(RUST_TARGET "x86_64-linux-android")
else()
    set(RUST_TARGET "aarch64-linux-android")
endif()

set(RUST_STATIC_LIB ${CMAKE_CURRENT_SOURCE_DIR}/../../../../rust-engine/target/${RUST_TARGET}/release/librust_khatawas.a)

add_library(rust_khatawas SHARED rust_jni_bridge.cpp)
target_link_libraries(
    rust_khatawas
    PRIVATE
    ${RUST_STATIC_LIB}
    log
)
```

---

## 4. Kotlin JNI Interface (`com.khatawas.rust.RustEngine`)

The native interface is implemented in `app/src/main/java/com/khatawas/rust/RustEngine.kt`:

```kotlin
package com.khatawas.rust

import android.util.Log

object RustEngine {
    private const val TAG = "RustEngine"
    private var isLoaded = false

    init {
        try {
            System.loadLibrary("rust_khatawas")
            isLoaded = true
            Log.i(TAG, "Successfully loaded librust_khatawas.so native library (version: ${nativeGetVersion()})")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load librust_khatawas.so native library", e)
            isLoaded = false
        }
    }

    fun isNativeLoaded(): Boolean = isLoaded

    external fun nativeGetVersion(): String
    external fun nativeHealthCheck(): Boolean
    external fun nativeFirProcess(input: FloatArray, output: FloatArray): Int
    external fun nativeParseUtterance(utterance: String): String
    external fun nativeCalculateBalance(credits: LongArray, payments: LongArray, reversals: LongArray): Long
}
```

---

## 5. APK Packaging Verification

Verification of the compiled `app-debug.apk` confirms the presence of `librust_khatawas.so` across all configured ABIs:

```
FullName                             Length
--------                             ------
lib/arm64-v8a/librust_khatawas.so   1,536,136 bytes
lib/armeabi-v7a/librust_khatawas.so 1,102,912 bytes
lib/x86_64/librust_khatawas.so      1,539,208 bytes
```

Total build time from clean state in Gradle: **29 seconds**.
Incremental native re-link time: **< 3 seconds**.

---

## 6. Memory Safety & Boundary Guarantees

1. **Zero Raw Pointer Escape**: Pointers passed across the JNI boundary are wrapped into safe Rust slices (`std::slice::from_raw_parts`) and never retained beyond the duration of the method call.
2. **Buffer Overrun Protection**: All string serialization uses explicit capacity checks; if a transcript exceeds the 8,192-byte stack buffer, it safely rejects rather than truncating or overrunning.
3. **No Unwinding across FFI**: All Rust functions use `panic = "abort"` in release mode and internal error boundaries to guarantee that Rust panics never unwind into Android JVM stack frames.
4. **Pronoun Guard Determinism**: Pure Rust entity classification prevents pronouns (`iska`, `usko`, etc.) from being assigned as ledger customer identities.
5. **Exact Integer Paise Math**: Computations use 64-bit integer signed arithmetic with overflow protection, preventing IEEE-754 floating-point rounding errors in retail transaction ledgers.
