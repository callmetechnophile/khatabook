# KHATAWAS — Build and Static Analysis Report

**Audit Date:** 2026-11-21  
**Build Tool:** Gradle 9.6.0 with Android Gradle Plugin (AGP) 8.8.0  
**Java Runtime:** OpenJDK 25.0.4 (`C:\Program Files\Android\Android Studio\jbr`)  
**Android NDK:** 27.1.12297006  
**Build Target:** `compileSdk 34`, `minSdk 24`, `targetSdk 34`  
**ABIs:** `arm64-v8a`, `armeabi-v7a`

---

## 1. Build Command Execution Log

| Command | Exit Code | Duration | Outcome |
|---|:---:|:---:|---|
| `gradlew.bat projects` | `0` | 14s | **SUCCESS**: Evaluated root project `KHATAWAS` and subproject `:app`. |
| `gradlew.bat tasks --all` | `0` | 12s | **SUCCESS**: Discovered 124 tasks across Android, CMake, and Kotlin plugins. |
| `gradlew.bat assembleDebug` | `0` | 56s | **SUCCESS**: Clean compilation of 42 actionable tasks; generated `app-debug.apk` (8.4 MB). |
| `gradlew.bat testDebugUnitTest` | `0` | 14s | **SUCCESS**: Executed JUnit test suite; 4 tests total across `LedgerParserTest` and `ParserAuditTest`. |

---

## 2. Compiler & Toolchain Inspection

### Native C/C++ Build (CMake & Clang)
- **CMake Version**: `3.22.1` via Android NDK.
- **Flags**: `-std=c++17 -DRNNOISE_BUILD`.
- **Target Artifact**: `libkhatawas_jni.so` and static `librnnoise_static.a`.
- **Symbols**: Clean JNI function name bindings:
  - `Java_com_khatawas_audio_RnnoiseProcessor_nativeInit`
  - `Java_com_khatawas_audio_RnnoiseProcessor_nativeProcessFrame`
  - `Java_com_khatawas_audio_RnnoiseProcessor_nativeDestroy`
- **Result**: Zero C++ compiler errors, zero linker warnings.

### Kotlin & Java Compilation
- **Source Compatibility**: Java 17 bytecode target.
- **Kotlin Target**: `jvmTarget = '17'`.
- **Result**: Clean compilation. Zero Kotlin syntax or type mismatch errors.

---

## 3. Deprecations, Static Analysis & Warnings

1. **Gradle 10 Deprecation Warnings**:
   - `android.usesSdkInManifest.disallowed=false` is deprecated.
   - `android.defaults.buildfeatures.resvalues=true` is deprecated.
   - `android.enableAppCompileTimeRClass=false` is deprecated.
   - These options in `gradle.properties` will require removal prior to upgrading to AGP 10.
2. **SDK XML Version Mismatch**:
   - `Warning: SDK processing. This version only understands SDK XML versions up to 3 but an SDK XML file of version 4 was encountered.`
   - Non-fatal warning caused by command-line tools version differences in the SDK directory.
3. **Manifest Permissions Verification**:
   - `android.permission.RECORD_AUDIO`: Required for microphone capture. Verified gated at runtime via `ActivityCompat.requestPermissions` (Request code `101`).
   - `android.permission.INTERNET`: Required for WebSocket communication with Gnani STT/TTS. Verified declared.
   - `android.permission.MODIFY_AUDIO_SETTINGS`: Declared for volume management in `VoiceFeedback.kt`.
