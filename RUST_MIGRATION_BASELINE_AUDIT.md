# KHATAWAS — Rust-First Computational Engine: Baseline Migration Audit

**Document Version:** 1.0.0  
**Date:** 2026-11-21  
**Milestone:** Phase A / Milestone 1  
**Branch:** `feat/rust-first-migration`  
**Base Commit:** `6430777` (on `feat/complete-application-stack`)  
**Target Hardware:** Vivo / iQOO Z9x, ARM64, Android 15 (Min SDK 24)  

---

## 1. Executive Summary

This audit establishes the baseline for migrating computational workloads in KHATAWAS to a Rust-first architecture while preserving working Android platform, storage, speech, and backend workflows.

### Governing Architectural Boundaries:
- **Rust (Target Computational Core):** Numerical DSP, anti-alias FIR filtering, audio buffer management, streaming noise suppression, transcript normalization, Indic transaction parsing, entity extraction, integer paise calculations, and deterministic financial validation.
- **Kotlin (Android Platform & Lifecycle):** Android UI (`MainActivity.kt`), microphone capture (`AudioRecord`), app orchestration (`AppOrchestrator.kt`), hardware-backed encryption keys (`DatabaseKeyManager.kt`), SQLCipher database transactions, Android TTS voice feedback, and network connectivity state monitoring.
- **Python (Offline Training & Cloud Backend):** Distillation training pipeline (`ml/`) and FastAPI SurrealDB backend service (`backend/`).
- **External Speech Cloud:** Gnani WebSocket streaming STT (`wss://api.vachana.ai/stt/v3/stream`).
- **Cryptographic Ledger Integrity:** Strictly deferred to a separate post-acceptance release milestone (no HMAC tags, Merkle trees, or signed checkpoints in this phase).

---

## 2. Inventory of Existing Implementation

### 2.1 Actual File Paths & Responsibilities

| Subsystem | File Path | Existing Language | Primary Responsibility |
|---|---|:---:|---|
| **Android UI** | `app/src/main/java/com/khatawas/MainActivity.kt` | Kotlin | Activity lifecycle, PTT interaction, UI state, AI disclosure dialog, data purge. |
| **Orchestrator** | `app/src/main/java/com/khatawas/core/AppOrchestrator.kt` | Kotlin | Coordinates AudioPipeline, LedgerParser, SQLCipher, and VoiceFeedback. |
| **Audio Capture** | `app/src/main/java/com/khatawas/audio/AudioPipeline.kt` | Kotlin | `AudioRecord` thread, 480-sample frame reading, 8-slot ring buffer pool. |
| **FIR Filter** | `app/src/main/java/com/khatawas/audio/FirDecimator3.kt` | Kotlin | 31-tap FIR low-pass filter, 48 kHz $\to$ 16 kHz decimation. |
| **RNNoise Wrapper**| `app/src/main/java/com/khatawas/audio/RnnoiseProcessor.kt` | Kotlin | JNI bridge to native C++ denoiser handle. |
| **Native DSP** | `app/src/main/cpp/rnnoise_wrapper.cpp` | C++ | Native JNI wrapper holding 64-bit `DenoiseState` pointer, bounds clamping. |
| **Upstream Denoiser**| `app/src/main/cpp/rnnoise/` | C | Upstream RNNoise neural network model weights and feature extractor. |
| **CMake Build** | `app/src/main/cpp/CMakeLists.txt` | CMake | NDK CMake build configuration for `librnnoise_wrapper.so`. |
| **STT Client** | `app/src/main/java/com/khatawas/audio/GnaniSttClient.kt` | Kotlin | Java-WebSocket client, `SttState` machine, backpressure queue, trailing silence flush. |
| **Voice Feedback** | `app/src/main/java/com/khatawas/audio/VoiceFeedback.kt` | Kotlin | Android `TextToSpeech` engine, Indic localized voice confirmation. |
| **Key Manager** | `app/src/main/java/com/khatawas/db/DatabaseKeyManager.kt` | Kotlin | Android Keystore 256-bit AES master key via `EncryptedSharedPreferences`. |
| **Local Database**| `app/src/main/java/com/khatawas/db/LedgerDatabase.kt` | Kotlin | SQLCipher v3 SQLite helper, paise storage, `sync_queue`, atomic transactions. |
| **Offline Sync** | `app/src/main/java/com/khatawas/sync/SyncManager.kt` | Kotlin | Background sync worker, network monitoring, exponential backoff HTTP client. |
| **Transaction NLU**| `app/src/main/java/com/khatawas/logic/LedgerParser.kt` | Kotlin | Indic token parser, Devanagari numerals, pronoun guard, query and reversal detector. |
| **Backend API** | `backend/app/main.py` | Python | FastAPI REST service at `/api/v1` with Bearer auth and idempotency. |
| **Cloud Storage** | `backend/app/surreal_client.py` | Python | SurrealDB cloud store with permanent records, graph edges, and 24h expiry worker. |
| **Model Pipeline**| `ml/student_model.py`, `train_distillation.py` | Python | PyTorch BiGRU multi-task model and distillation trainer from Nandi-Mini-150M. |
| **Model Artifact**| `ml/student_khatawas.onnx` | ONNX | 2.11 MB multi-task student model (0.61 ms median latency). |

---

## 3. Verified Build & Test Status

Prior to any migration modifications, the baseline repository was verified:
- **Git Commit:** `6430777` on `feat/complete-application-stack`
- **Android Unit Tests:** `./gradlew testDebugUnitTest` $\to$ **BUILD SUCCESSFUL** (23 tests passed, 0 failed in 29s).
- **Backend API Tests:** `pytest backend/tests/test_api.py` $\to$ **100% PASS** (6 passed in 1.75s).
- **Model Evaluation:** `python -m ml.evaluate_model` $\to$ **100% Intent/Direction Accuracy, 0.61 ms Median Latency**.
- **Android APK Build:** `./gradlew assembleDebug` $\to$ **BUILD SUCCESSFUL** (outputs `app-debug.apk` in 11s).

---

## 4. Migration Candidacy & Boundary Analysis

```
+-----------------------------------------------------------------------------------------------+
| Computational Domain       | Current Implementation  | Target Implementation | Migration Path |
+----------------------------+-------------------------+-----------------------+----------------+
| 1. FIR Decimator           | FirDecimator3.kt        | rust-engine/dsp/fir   | Phase C (P0)   |
| 2. Audio Processing Buffer | AudioPipeline.kt        | rust-engine/audio     | Phase D (P1)   |
| 3. Speech Denoising        | rnnoise_wrapper.cpp (C) | rust-engine/dsp/denoise|Phase E (P2)   |
| 4. Model Inference Adapter | On-device ONNX runtime  | rust-engine/inference | Phase F (P2)   |
| 5. Transaction Intelligence| LedgerParser.kt         | rust-engine/ledger    | Phase G (P1)   |
| 6. Financial Calculations  | LedgerDatabase.kt math  | rust-engine/ledger    | Phase H (P1)   |
| 7. Android Lifecycle & UI  | MainActivity.kt         | Retain in Kotlin      | Boundary Retain|
| 8. SQLCipher DB Lifecycle  | LedgerDatabase.kt       | Retain in Kotlin      | Boundary Retain|
| 9. Cloud Backend & DB      | FastAPI + SurrealDB     | Retain in Python      | Boundary Retain|
+-----------------------------------------------------------------------------------------------+
```

### 4.1 Candidates for Migration to Rust
1. **FIR Low-Pass Decimator (`FirDecimator3.kt` $\to$ `dsp/fir.rs`):**
   - High compute density: processes every incoming 10 ms audio frame (480 samples).
   - Direct numerical equivalence to verify: 31 coefficients, state history across frame boundaries, $3:1$ downsampling.
2. **Audio Buffer Processing & PCM Conversion (`AudioPipeline.kt` $\to$ `audio/`):**
   - Eliminates JNI array marshalling overhead; Rust receives raw buffers, converts PCM16 to float, applies filtering, and produces 16 kHz output.
3. **Noise Suppression (`rnnoise_wrapper.cpp` $\to$ `dsp/denoise.rs`):**
   - Streaming spectral denoiser (STFT, Wiener filtering, spectral gain flooring, overlap-add synthesis).
   - Evaluated comparatively against baseline RNNoise.
4. **Transaction Intent Parsing & Validation (`LedgerParser.kt` $\to$ `ledger/`):**
   - Tokenization, Devanagari normalization, pronoun rejection, multi-clause segmentation, integer paise calculations, and query classification.
5. **Model Inference Execution Adapter (`ml/student_khatawas.onnx` $\to$ `inference/`):**
   - Fast native ONNX Runtime C / Rust bindings executing the 2.11 MB student model on ARM64.

### 4.2 Modules That MUST Remain in Kotlin
1. **Android Application Lifecycle (`MainActivity.kt`):** Activity lifecycle, window management, permissions, and push-to-talk button UI.
2. **Microphone Hardware Management (`AudioRecord` in `AudioPipeline.kt`):** Android framework hardware audio capture must remain managed by Android platform APIs.
3. **Android Keystore Cryptography (`DatabaseKeyManager.kt`):** Hardware security module (TEE/StrongBox) key generation and `EncryptedSharedPreferences` master key unwrapping.
4. **SQLCipher Database Lifecycle & Transactions (`LedgerDatabase.kt`):** SQLite connection open/close, encrypted schema migrations, and SQLite transaction boundaries.
5. **Gnani STT WebSocket Client (`GnaniSttClient.kt`):** Active, verified streaming WebSocket connection to `api.vachana.ai`.
6. **Android TextToSpeech (`VoiceFeedback.kt`):** Local Android TTS engine for Kirana shopkeeper confirmation.

### 4.3 Modules That MUST Remain in Python
1. **Offline Distillation Pipeline (`ml/`):** Teacher synthetic data generation and PyTorch training.
2. **Backend API Service (`backend/`):** FastAPI cloud ingestion service and SurrealDB persistence layer.

---

## 5. Android Toolchain Compatibility & Target Configuration

### 5.1 NDK & Compilers
- **Installed NDK Version:** `27.1.12297006` at `C:\Users\worka\AppData\Local\Android\Sdk\ndk\27.1.12297006`.
- **Clang Compiler Available:** `aarch64-linux-android24-clang.cmd` (API 24+) and `armv7a-linux-androideabi24-clang.cmd`.
- **Rust Toolchain:** `rustc 1.99.0`, `cargo 1.99.0`.
- **Target Triples:**
  - `aarch64-linux-android` (arm64-v8a — Primary production target for Vivo / iQOO Z9x).
  - `armv7-linux-androideabi` (armeabi-v7a — Secondary compatibility target).

### 5.2 Build Integration Strategy
- **Shared Library Output:** `librust_khatawas.so`.
- **Gradle Packaging:** Cargo builds native libraries into `app/src/main/jniLibs/arm64-v8a/librust_khatawas.so` and `app/src/main/jniLibs/armeabi-v7a/librust_khatawas.so`.
- **JNI Interface Package:** `com.khatawas.rust.RustEngine` loading `System.loadLibrary("rust_khatawas")`.

---

## 6. Rust Dependency Selection (Minimal & Mobile-Compatible)

| Dependency | Purpose | Justification |
|---|---|---|
| `jni = "0.21"` | Java Native Interface | Standard, safe, battle-tested Rust JNI bindings. |
| `serde = { version = "1.0", features = ["derive"] }` | Structured Serialization | Safe, typed JSON/binary serialization across JNI. |
| `serde_json = "1.0"` | JSON Deserialization | Interoperability with structured transaction payloads. |
| `realfft = "3.3"` | Real-to-Complex FFT | High-performance, zero-allocation FFT for audio DSP. |

---

## 7. Migration Risks & Mitigation Plan

| Risk | Severity | Mitigation Strategy |
|---|:---:|---|
| **Numerical divergence in FIR filter** | High | Test identical input arrays (impulse, DC, tones, random noise) against Kotlin reference with $<10^{-5}$ tolerance. |
| **JNI boundary overhead on 10ms audio frames** | Medium | Pass primitive buffers or direct byte buffers; avoid per-frame object instantiation or serialization in the audio loop. |
| **Denoising quality regression** | High | Retain RNNoise baseline during Phase E; benchmark against synthetic Kirana shop noise before switching default denoiser. |
| **Android NDK linker mismatch** | Medium | Use NDK 27.1 llvm toolchain with explicit `.cargo/config.toml` targeting `aarch64-linux-android24-clang.cmd`. |
| **Silent transaction commitment on ambiguity** | Critical | Enforce pronoun guard and deterministic financial validation in Rust; fail closed with explicit clarification prompts. |

---

## 8. Milestone Execution Order

- **Milestone 1 (Current):** Baseline Repository Audit (`RUST_MIGRATION_BASELINE_AUDIT.md`) — **COMPLETE**.
- **Milestone 2:** Rust Workspace Setup & Android Toolchain Integration (`rust-engine/`, JNI bridge, Gradle build).
- **Milestone 3:** FIR Decimator Migration & Numerical Equivalence Validation.
- **Milestone 4:** Rust Audio Processing Engine & Buffer Management.
- **Milestone 5:** Rust Classical Denoiser & RNNoise Comparative Benchmark.
- **Milestone 6:** Native ONNX Model Inference Integration.
- **Milestone 7:** Multilingual Transaction Engine & Financial Validation.
- **Milestone 8:** Financial Calculations & SQLCipher Database Boundary Integration.
- **Milestone 9:** Gnani STT, Voice Feedback & Cloud Sync End-to-End Wiring.
- **Milestone 10:** Physical Device Verification on Vivo / iQOO Z9x.
