# KHATAWAS — Repository Component Inventory

**Audit Date:** 2026-11-21  
**Auditor:** Senior Android Systems, Embedded Audio/DSP & Speech AI Architect  
**Repository:** `https://github.com/callmetechnophile/khatabook`  
**Target Environment:** Android API 24–34, ARMv7/ARM64 physical devices (< 2GB RAM Kirana terminals)

---

## 1. Complete Component Inventory

| Component | File | Purpose | Implementation Status | Dependencies |
|---|---|---|---|---|
| **Audio Capture & DSP** | `app/src/main/java/com/khatawas/audio/AudioPipeline.kt` | 48kHz `AudioRecord` capture, RNNoise invocation, 3:1 linear decimation to 16kHz, callback dispatch. | **IMPLEMENTED WITH DEFECTS** | Android AudioRecord, `RnnoiseProcessor` |
| **DSP Native Binding** | `app/src/main/java/com/khatawas/audio/RnnoiseProcessor.kt` | JNI interface loading `khatawas_jni` shared library for 480-sample frame processing. | **IMPLEMENTED AND VERIFIED** | C++ JNI (`khatawas_jni`) |
| **DSP Native Wrapper** | `app/src/main/cpp/rnnoise_wrapper.cpp` | C++ implementation wrapping upstream RNNoise with mutex and static buffers. | **IMPLEMENTED WITH DEFECTS** | RNNoise C library, Android log |
| **RNNoise Static Engine** | `app/src/main/cpp/rnnoise/` (submodule/headers) & `app/src/main/cpp/rnnoise_data.c` | Static neural network weights and RNN inference code for denoiser. | **IMPLEMENTED AND VERIFIED** | C99 math / stdlib |
| **STT Transport** | `app/src/main/java/com/khatawas/audio/GnaniSttClient.kt` | Java-WebSocket streaming client to Gnani STT API (`wss://asr.gnani.ai/stt/v3/stream`). | **IMPLEMENTED WITH DEFECTS** | `org.java-websocket:Java-WebSocket`, `BuildConfig` |
| **TTS Transport** | `app/src/main/java/com/khatawas/audio/GnaniTtsClient.kt` | Java-WebSocket client for streaming Gnani TTS synthesis with AudioTrack. | **IMPLEMENTED BUT UNVERIFIED** | `org.java-websocket:Java-WebSocket`, `AudioTrack`, `BuildConfig` |
| **Local TTS Feedback** | `app/src/main/java/com/khatawas/audio/VoiceFeedback.kt` | Android native `TextToSpeech` engine with volume management. | **IMPLEMENTED WITH DEFECTS** | `android.speech.tts.TextToSpeech`, `AudioManager` |
| **Mock STT** | `app/src/main/java/com/khatawas/audio/MockSttBridge.kt` | Test harness for mocking speech transcripts. | **UNUSED / DEAD CODE** | `AppOrchestrator` |
| **Application Orchestrator** | `app/src/main/java/com/khatawas/core/AppOrchestrator.kt` | Central pipeline coordinating STT transcripts, LedgerParser, single-threaded SQLite executor, and TTS. | **IMPLEMENTED WITH DEFECTS** | `LedgerParser`, `LedgerDatabase`, `VoiceFeedback`, `RnnoiseProcessor` |
| **Key Vault** | `app/src/main/java/com/khatawas/db/DatabaseKeyManager.kt` | Derives/persists 256-bit passphrase via Android Keystore & EncryptedSharedPreferences. | **IMPLEMENTED AND VERIFIED** | `androidx.security:security-crypto` |
| **Encrypted Ledger DB** | `app/src/main/java/com/khatawas/db/LedgerDatabase.kt` | SQLCipher AES-256 SQLite database (`customers` and `transactions` tables). | **PARTIALLY IMPLEMENTED** | `net.zetetic:android-database-sqlcipher`, `DatabaseKeyManager` |
| **Natural Language Parser** | `app/src/main/java/com/khatawas/logic/LedgerParser.kt` | Regex + token dictionary parser for spoken Kirana Hinglish utterances. | **IMPLEMENTED WITH DEFECTS** | Kotlin Standard Library |
| **Parser Unit Tests** | `app/src/test/java/com/khatawas/logic/LedgerParserTest.kt` | Baseline JUnit unit test verifying 3 basic utterances. | **IMPLEMENTED AND VERIFIED** | JUnit 4 |
| **Main User Interface** | `app/src/main/java/com/khatawas/MainActivity.kt` | High-contrast PTT UI, runtime permission gate, RNNoise toggle. | **IMPLEMENTED WITH DEFECTS** | Android SDK, `AudioPipeline`, `GnaniSttClient`, `AppOrchestrator` |
| **UI XML Layout** | `app/src/main/res/layout/activity_main.xml` | Minimal black/yellow high-contrast UI (PTT Button, Status TextView, Switch). | **IMPLEMENTED AND VERIFIED** | Android Layout Resources |
| **Native CMake Build** | `app/src/main/cpp/CMakeLists.txt` | Builds `khatawas_jni` shared library and `rnnoise_static` static library. | **IMPLEMENTED AND VERIFIED** | Android NDK CMake |
| **App Gradle Script** | `app/build.gradle` | Android application plugin, NDK/ABI filters, BuildConfig injection, dependencies. | **IMPLEMENTED AND VERIFIED** | AGP 8.8.0, Kotlin Gradle Plugin |

---

## 2. Redundancies, Dead Code, and Architecture Inconsistencies

1. **Dead Code — `MockSttBridge.kt`**:
   - `MockSttBridge.kt` was created during Phase 3 to inject test sentences. Following Phase 4 and Phase 5 UI wiring, it is never called by `MainActivity` or any active runtime component.
2. **Conflicting TTS Architecture**:
   - Two parallel TTS engines exist:
     - `VoiceFeedback.kt` (Android native `android.speech.tts.TextToSpeech`, utilized by `AppOrchestrator`).
     - `GnaniTtsClient.kt` (Gnani cloud streaming WebSocket TTS, created in Phase 5).
   - `AppOrchestrator.kt` has not been integrated with `GnaniTtsClient.kt`. `GnaniTtsClient.kt` is currently orphaned from the main application flow.
3. **Hardcoded Log VAD**:
   - In `AudioPipeline.kt` line 124: `Log.i(TAG, "RNNoise frame processed, VAD prob = 1.0")`.
   - The C++ JNI function `nativeProcessFrame` returns `void`, discarding the actual VAD probability returned by `rnnoise_process_frame`. The logcat output hardcodes `1.0`.
