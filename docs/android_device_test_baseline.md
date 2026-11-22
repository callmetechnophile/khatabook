# KHATAWAS — Android Device Test Baseline Report

**Date:** November 22, 2026  
**Auditor:** Principal Mobile Architect & QA Lead  
**Repository:** `callmetechnophile/khatabook`  
**Package:** `com.khatawas`  
**Target Branch:** `release/github-actions-apk-pipeline` / `main`  

---

## 1. Physical Device Hardware & System Specification

| Property | Value | Evidence / Command Source |
|---|---|---|
| **Device Serial** | `10BF8P109X003X2` | `adb devices -l` (`transport_id:9`) |
| **Manufacturer** | `vivo` | `getprop ro.product.manufacturer` |
| **Model** | `I2407` (vivo V40 / T3 series) | `getprop ro.product.model` |
| **Chipset Architecture** | `arm64-v8a` | `getprop ro.product.cpu.abi` |
| **Android Version** | **Android 16** (Baklava Preview) | `getprop ro.build.version.release` |
| **API Level** | **API 36** | `getprop ro.build.version.sdk` |
| **Physical Screen Size** | **$1080 \times 2392$ px** | `wm size` |
| **Screen Density** | **440 dpi (xxhdpi)** | `wm density` |
| **Total System RAM** | **7,579,092 kB (~8 GB RAM)** | `cat /proc/meminfo` |
| **Available RAM** | **2,498,588 kB (~2.5 GB available)** | `cat /proc/meminfo` |
| **Internal Storage (/data)**| **102 GB Total, 50 GB Used, 52 GB Free (49% used)** | `df -h /data` |
| **Biometric Capabilities** | `android.hardware.biometrics.face`, `vendor.vivo.hardware.biometrics.face` | `pm list features` |
| **Microphone Permission** | **`RECORD_AUDIO: allow`** (foreground granted) | `appops get com.khatawas RECORD_AUDIO` |

---

## 2. Architecture & Technology Stack Audit

### 2.1 Kotlin Architecture & UI Framework
- **Application Structure:** Single Activity architecture centered on `MainActivity.kt`.
- **UI Framework:** Android XML layouts with ViewBinding and Material 3 design tokens.
  - Native views: `ConstraintLayout`, `MaterialCardView`, `RecyclerView`, `BottomNavigationView`.
  - XML layouts: `activity_main.xml`, `dialog_fast_transaction.xml`, `item_customer.xml`.
- **Theme & Palette:** High-contrast rural kirana theme.
  - Primary Credit (`#1E7E34` / Deep Khata Green)
  - Primary Debit (`#D32F2F` / Deep Kirana Red)
  - High Contrast Surfaces (`#F8F9FA` Light, `#121212` Dark)
- **Launcher Icon:** Preserved mipmap launcher icon (`ic_launcher.png`, `ic_launcher_round.png`) across all densities (`mdpi` to `xxxhdpi`).

### 2.2 Audio Capture & Signal Processing Pipeline
- **Microphone Capture:** `AudioRecord` at 48,000 Hz, 16-bit PCM Mono (`AudioPipeline.kt`).
- **Noise Suppression:** C++ RNNoise neural network state wrapper (`rnnoise_wrapper.cpp`, `RnnoiseProcessor.kt`) with thread safety and per-instance state handles.
- **FIR Decimation:** 31-tap linear-phase FIR low-pass filter with factor $M = 3$ decimation to 16,000 Hz (`FirDecimator3.kt`).
  - Bit-exact unity DC gain: $\sum h = 1.0000$.
  - Stopband attenuation: $> 55\text{ dB}$ across frequencies above 8 kHz Nyquist limit.
- **Speech Recognition:** Streaming WebSocket integration with Gnani Prisma STT (`GnaniSttClient.kt`).

### 2.3 Database, Encryption & Offline Financial Invariants
- **Local Database:** Encrypted SQLite via SQLCipher (`LedgerDatabase.kt`).
- **Key Derivation & Protection:** Android Keystore with Hardware-backed AES-256-GCM (`DatabaseKeyManager.kt`).
- **Financial Invariants:**
  - 100% Integer Paise monetary representation (zero float math).
  - Append-only event model with cryptographic SHA-256 chaining.
  - Local-first commit before cloud sync.
- **Cloud Database:** SurrealDB Cloud via FastAPI gateway (`SyncManager.kt`).

### 2.4 Localization & Language Support
- **Supported Locales:**
  - English (`values/strings.xml`)
  - हिन्दी (Hindi - `values-hi/strings.xml`)
  - ಕನ್ನಡ (Kannada - `values-kn/strings.xml`)
- **Persistence:** Local language selection stored in encrypted preferences / database config and applied at runtime via AppCompatDelegate / Context locale wrapper.

---

## 3. Planned Implementation Steps for Full UI Polish & Physical ADB Validation

1. **Brand & Navigation Refinement:**
   - Center KHATAWAS branding on home screen with microphone symbol immediately adjacent to the brand title.
   - Clean 5-tab bottom navigation: Home, Customers, History, Assistant, Settings.
2. **Tap-to-Start / Tap-to-Stop Voice Interaction:**
   - Refactor `MainActivity.kt` recording interaction from push-and-hold to tap-to-start, tap-to-stop.
   - Implement state machine: `IDLE → RECORDING → PROCESSING → REVIEW → COMPLETE`.
   - Real amplitude waveform visualizer from captured audio buffers.
   - Modern voice-recorder-style playback interface with Play/Pause, Replay, Discard, and Transcribed Text preview.
3. **Language Selector:**
   - Dedicated Settings screen with instantaneous locale switching (English, Hindi, Kannada) and persistence across app restarts.
4. **Physical Device ADB Validation Suite:**
   - Install APK on vivo I2407.
   - Execute complete UI navigation, audio capture, playback, and language switching tests.
   - Capture real-device screenshots for verification report.
