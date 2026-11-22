# KHATAWAS — Android Application

> **Note:** This document provides Android-specific technical instructions. For the complete, authoritative documentation of the entire KHATAWAS product ecosystem (Web, Android, Cloud Backend, AI Models, and SurrealDB Architecture), please refer to the [**Master Unified README**](../README.md).

---

## Overview

The **KHATAWAS Android Application** (`com.khatawas`) is an offline-first, voice-enabled digital ledger engineered specifically for Indian Kirana shopkeepers. It provides real-time transaction recording, speech-to-text processing, on-device AI edge inference, biometric protection, and AES-256 encrypted local persistence.

---

## Key Android Architecture Highlights

1. **Cash-Flow Visual Semantics (Shopkeeper Perspective)**:
   - **GIVE UDHAAR / ADD CREDIT**: **RED** (`#D32F2F`) — Shopkeeper gives goods/credit $\implies$ customer debt increases.
   - **GOT PAYMENT / RECEIVE PAYMENT**: **GREEN** (`#2E7D32`) — Shopkeeper receives cash $\implies$ customer debt decreases.
   - **Outstanding Debt Owed**: Displayed in **RED**.
   - **Advance Credit**: Displayed in **GREEN**.
2. **Encrypted Local Database**:
   - Built on **SQLCipher 4.5.4** (`net.zetetic:android-database-sqlcipher`).
   - 256-bit AES encryption key dynamically generated and held securely in **Android Keystore** (`DatabaseKeyManager.kt`).
3. **Biometric Security**:
   - Utilizes AndroidX BiometricPrompt (`BiometricAuthHelper.kt`) for device owner authentication on app launch or sensitive data access.
4. **Native Audio Processing & Denoising**:
   - C++ / JNI integration of **RNNoise** (`app/src/main/cpp/`) for ambient shop noise reduction.
   - Software FIR Decimator for high-fidelity audio downsampling to 16 kHz PCM for STT streaming.
5. **Speech & Cloud Sync**:
   - Native WebSocket streaming to **Gnani Prisma v3.0 STT** and **Timbre v3.0 TTS**.
   - Offline-first operation: transactions are committed locally instantly; manual cloud backup trigger via `SyncManager.kt`.
6. **Edge AI Inference**:
   - **ONNX Runtime Android** (`com.microsoft.onnxruntime:onnxruntime-android:1.17.0`) running quantized multitask student model for local query understanding.

---

## Build & Installation

### Prerequisites
- Android Studio Hedgehog (2023.1.1) or later
- JDK 17
- Android SDK 34 (minSdk 24, targetSdk 34)
- Android NDK 27.1.12297006 and CMake 3.22.1

### Build APK via Command Line
```bash
# Debug build
./gradlew assembleDebug

# Release build (requires signing config in local.properties or environment)
./gradlew assembleRelease
```
The resulting debug APK is located at:
`app/build/outputs/apk/debug/app-debug.apk`

### Install onto Device via ADB
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Run Unit & Verification Tests
```bash
./gradlew testDebugUnitTest
```
Runs 26 automated unit tests including `ShopkeeperPerspectiveColorTest` validating cash-flow color mappings and arithmetic integrity.

---

## Project Structure

```text
app/src/main/
├── AndroidManifest.xml
├── cpp/                     # C++ native audio DSP (RNNoise wrapper & CMake)
├── java/com/khatawas/
│   ├── MainActivity.kt      # Core UI activity with 5-tab navigation & mic button
│   ├── ai/                  # ONNX Runtime inference & query planners
│   ├── audio/               # Audio recording, RNNoise filter, Gnani STT/TTS clients
│   ├── auth/                # BiometricAuthHelper & PrivacyManager
│   ├── db/                  # SQLCipher LedgerDatabase & DatabaseKeyManager
│   ├── logic/               # Transaction models & parser bridge
│   ├── sync/                # SyncManager (manual batch backup)
│   ├── ui/                  # Adapters, currency formatters, and dialogs
│   └── util/                # LanguageManager (EN, HI, KN)
└── res/                     # Layouts, drawables, strings (EN, HI, KN), colors
```

---

## Documentation Links

- **[Master Unified Project README](../README.md)** — Complete product documentation.
- **[Shopkeeper Color Verification Report](../docs/ANDROID_FINAL_TEST_REPORT.md)** — Acceptance test results & screenshots.
- **[Brand Identity Guidelines](../brand/BRAND_IDENTITY_GUIDELINES.md)** — Icon source of truth and sizing.
