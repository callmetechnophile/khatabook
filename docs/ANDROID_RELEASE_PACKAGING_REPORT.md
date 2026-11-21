# KHATAWAS — Android APK Packaging & GitHub Release Report

**Document Version:** 1.0.0  
**Date:** 2026-11-22  
**Target Release:** `v0.1.0` (Initial Android Testing Pre-release)  
**Package Name:** `com.khatawas`  
**Lead Engineer:** Senior Android Release Engineer & GitHub Actions CI/CD Specialist  

---

## 1. Executive Summary

This report documents the packaging, release signing configuration, pre-release validation, device installation testing, and GitHub Actions CI/CD distribution automation for the **KHATAWAS** Android application.

The release build has been compiled, signed with a 2048-bit RSA key, verified via Android `apksigner` (v2 signature scheme), validated with `aapt2` package inspection, checksummed with SHA-256, and successfully smoke-tested on real physical Android hardware (**Vivo I2407 / ARM64**). A complete, production-grade GitHub Actions CI/CD workflow (`.github/workflows/android-release.yml`) has been established to automate future tag-driven releases (`v*`).

---

## 2. Release Status Matrix

| Check | Status | Verification Detail |
|---|---|---|
| **Release build** | **Passed** | Gradle `assembleRelease` executed with no errors; Proguard disabled as specified; release APK generated. |
| **Unit tests** | **Passed** | 11 unit test suites passed (including `PrivacyManagerTest`, `EventIntegrityTest`, `LedgerParserTest`, `IndianCurrencyFormatterTest`, `TransactionCategoryTest`). |
| **Lint** | **Passed** | `lintVitalRelease` completed cleanly with zero fatal lint errors. |
| **APK signing** | **Verified** | Verified using `apksigner verify --verbose` (v2 scheme valid, 1 signer). |
| **SHA-256** | **Generated** | `58d6e32039c2f371f8c6689baf728b4a090080eb858a413e969325064d4b3f16` recorded in checksum manifest. |
| **APK installation** | **Tested** | Installed successfully on physical device `10BF8P109X003X2` (Vivo I2407) via `adb install`. |
| **Application launch** | **Tested** | Launched `com.khatawas/.MainActivity` on physical hardware (PID `19955`); confirmed active process and screenshot captured. |
| **GitHub Release Workflow** | **Configured** | Workflow `.github/workflows/android-release.yml` created with fail-safe secret guards and asset publication. |

---

## 3. Package & Environment Metadata

### Application Specifications
- **Application ID:** `com.khatawas`
- **Version Code:** `1`
- **Version Name:** `0.1.0`
- **Min SDK:** `24` (Android 7.0 Nougat)
- **Target SDK:** `34` (Android 14)
- **Compile SDK:** `34`
- **Android Gradle Plugin:** `9.0.0`
- **Kotlin Version:** `2.2.10`
- **Java Toolchain:** OpenJDK 17 / Temurin 17

### Target Architectures & Native Libraries
The application packages native shared libraries for all major Android ABIs:
1. `arm64-v8a`: `libkhatawas_android.so` (Rust engine) + `librnnoise.so` (C++ RNNoise)
2. `armeabi-v7a`: `libkhatawas_android.so` + `librnnoise.so`
3. `x86_64`: `libkhatawas_android.so` + `librnnoise.so`

### On-Device Assets & Intelligence
- `assets/models/khatawas_chatbot_int8.onnx` (2.57 MB)
- `assets/models/khatawas_student_int8.onnx` (9.34 MB)
- `assets/models/vocab.json` & `vocab.txt`
- Encrypted SQLite database engine via SQLCipher AES-256 (`net.zetetic:android-database-sqlcipher:4.5.4`).

---

## 4. Release Asset Deliverables

| File | Size (Bytes) | Human Size | SHA-256 Checksum |
|---|---|---|---|
| `KHATAWAS-v0.1.0-release.apk` | 72,139,714 B | ~68.80 MB | `58d6e32039c2f371f8c6689baf728b4a090080eb858a413e969325064d4b3f16` |
| `KHATAWAS-v0.1.0-SHA256.txt` | 94 B | < 1 KB | Text manifest verifying APK authenticity |

### Integrity Verification Commands

#### Linux / macOS:
```bash
sha256sum -c KHATAWAS-v0.1.0-SHA256.txt
```

#### Windows PowerShell:
```powershell
(Get-FileHash -Algorithm SHA256 ./KHATAWAS-v0.1.0-release.apk).Hash -eq "58d6e32039c2f371f8c6689baf728b4a090080eb858a413e969325064d4b3f16"
```

---

## 5. Security & Secret Protection Architecture

1. **No Hardcoded Keystores or Passwords:**  
   Signing credentials are never stored in `build.gradle` or any repository files. All signing properties are dynamically ingested from system environment variables (`KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`).
2. **Git Ignore Hardening:**  
   `.gitignore` was updated to explicitly block:
   - `*.jks`, `*.keystore`, `*.p12`, `*.key`
   - `.env`, `.env.*` (while keeping `.env.example`)
   - `*.apk`, `*.aab`, `/release/`
3. **CI Ephemeral Keystore Lifecycles:**  
   The GitHub Actions workflow decodes `secrets.ANDROID_KEYSTORE_BASE64` to an ephemeral temporary directory (`$(mktemp -d)/release.keystore`) with permissions `0600`, and securely wipes the directory immediately after Gradle completes (`if: always()`).
4. **Fail-Safe Release Guards:**  
   If signing secrets are missing in GitHub repository settings, the CI workflow halts immediately with an error and **refuses** to publish an unsigned or debug APK.

---

## 6. Real-Device Smoke Test Evidence

- **Test Device:** Physical Smartphone `10BF8P109X003X2`
- **Device Model:** Vivo `I2407` (ARM64)
- **Installation Command:** `adb -s 10BF8P109X003X2 install release\KHATAWAS-v0.1.0-release.apk`
- **Install Result:** `Success`
- **Launch Command:** `adb -s 10BF8P109X003X2 shell am start -n com.khatawas/.MainActivity`
- **Process ID (PID):** `19955`
- **Status:** Application successfully booted, loaded encrypted SQLCipher schema, initialized Rural-First UI with Hindi/Kannada localization, and rendered the masked privacy home dashboard without crashing.

---

## 7. Instructions for Creating Future Releases

To release a new version (e.g. `v0.1.1` or `v0.2.0`):

1. **Tag the Commit:**
   ```bash
   git tag -a v0.1.1 -m "Release KHATAWAS v0.1.1"
   git push origin v0.1.1
   ```
2. **GitHub Actions Automation:**
   The tag push automatically triggers `.github/workflows/android-release.yml`.
   - Runs unit tests and release lint.
   - Builds and signs `KHATAWAS-v0.1.1-release.apk`.
   - Verifies the APK with `apksigner`.
   - Computes SHA-256 and attaches both assets to the GitHub Release.
3. **Manual Dispatch Option:**
   Go to **GitHub Actions → KHATAWAS Android Release → Run workflow**, specify `v0.1.1`, and click Run.
