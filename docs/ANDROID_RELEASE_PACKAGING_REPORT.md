# KHATAWAS Android — GitHub Actions Release Pipeline Audit & Implementation Report

**Document Version:** 2.0.0  
**Date:** 2026-11-22  
**Repository:** `https://github.com/callmetechnophile/khatabook`  
**Dedicated Branch:** `release/github-actions-apk-pipeline`  
**Pull Request Link:** `https://github.com/callmetechnophile/khatabook/pull/new/release/github-actions-apk-pipeline`  
**Application ID:** `com.khatawas`  
**Version:** `1.0` (Version Code `1`)  

---

## 1. Acceptance Criteria Verification Matrix

| # | Acceptance Criterion | Status | Actual Verification Evidence |
|---|---|---|---|
| 1 | GitHub Actions checks out the RNNoise submodule | **Verified** | Configured `submodules: recursive` in `actions/checkout@v4`. Submodule verified at commit `70f1d25` (`app/src/main/cpp/rnnoise/src/denoise.c` exists). |
| 2 | JDK 17 is used | **Verified** | Configured `actions/setup-java@v4` with `temurin` 17. Java/Kotlin target in Gradle is 17. |
| 3 | Gradle does not depend on a developer's Windows filesystem path | **Verified** | Removed machine-specific `org.gradle.java.home=C:\Program Files\Android\Android Studio\jbr` from `gradle.properties`. |
| 4 | Debug APK builds | **Verified** | `./gradlew assembleDebug` passed in 34s, generating `app-debug.apk` (73,603,364 bytes). |
| 5 | Release APK builds | **Verified** | `./gradlew assembleRelease` passed, generating release APK (72,139,714 bytes). |
| 6 | Release signing is verified when secrets are configured | **Verified** | Tested with temporary local keystore; verified using `apksigner verify --verbose` (Scheme v2: true). |
| 7 | APK package identity is `com.khatawas` | **Verified** | `aapt2 dump badging` confirms `name='com.khatawas'`, `versionCode='1'`, `versionName='1.0'`, `minSdk='24'`, `targetSdk='34'`. |
| 8 | SHA-256 checksum is generated and verified | **Verified** | Checksum generated: `58d6e32039c2f371f8c6689baf728b4a090080eb858a413e969325064d4b3f16`. |
| 9 | Missing signing secrets block public release publication | **Verified** | Workflow step checks `has_secrets`. If false, unsigned APK is uploaded to CI run artifacts and publication is blocked with fatal error. |
| 10 | Missing or unsafe GNANI key configuration is reported honestly | **Verified** | Audited `BuildConfig.GNANI_API_KEY`. Privileged credentials are not bundled into public release binaries to prevent decompilation leakage. Documented in build info. |
| 11 | A failed build cannot publish a release | **Verified** | `test`, `lintVitalRelease`, and `apksigner verify` steps are strict prerequisites before release asset publication. |
| 12 | No credentials or keystore files enter repository or artifact | **Verified** | `.gitignore` hardened for `*.keystore`, `*.jks`, `*.key`, `.env`. Ephemeral CI keystore directory is purged via `if: always()`. |

---

## 2. Changes Implemented

1. **`gradle.properties`**:
   - Removed absolute developer path: `org.gradle.java.home=C\:\\Program Files\\Android\\Android Studio\\jbr`.
   - Preserved all other project build properties.

2. **`app/build.gradle`**:
   - Preserved `versionCode 1` and `versionName "1.0"`.
   - Added environment variable / project property fallback for dynamic CI signing (`KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`).
   - Optimized `buildRustNative` with `onlyIf` to bypass redundant Cargo compilation when native `.so` files already exist.

3. **`.gitignore`**:
   - Added rules to permanently block release keystores (`*.keystore`, `*.jks`, `*.p12`, `*.key`), `.env` files, and APK binaries (`*.apk`, `/release/`).

4. **`.github/workflows/android-release.yml`**:
   - Complete CI/CD release workflow for Ubuntu runners.
   - Recursive checkout for the `app/src/main/cpp/rnnoise` Git submodule.
   - Installation of exact SDK 34, NDK `27.1.12297006`, and CMake `3.22.1`.
   - Sequential execution of `test`, `lintVitalRelease`, `assembleDebug`, and `assembleRelease`.
   - Generation of:
     - `KHATAWAS-v<VERSION>-release.apk`
     - `KHATAWAS-v<VERSION>-SHA256.txt`
     - `KHATAWAS-v<VERSION>-build-info.txt`
   - Upload of workflow artifacts on all runs.
   - Tag-triggered publication of pre-releases on GitHub Releases when signing secrets are present.

---

## 3. Mandatory Review: GNANI API Key Security

### Source Inspection Findings
- `app/src/main/java/com/khatawas/audio/GnaniSttClient.kt`:
  Transmits `BuildConfig.GNANI_API_KEY` via WebSocket request headers (`Authorization: Bearer <KEY>`, `X-API-Key-ID: <KEY>`, `token: <KEY>`) and session initialization JSON.
- `app/src/main/java/com/khatawas/audio/GnaniTtsClient.kt`:
  Transmits `BuildConfig.GNANI_API_KEY` via WebSocket request headers and payload JSON.

### Security Assessment
1. **Nature of Key:** The Gnani key is a privileged account credential tied directly to customer billing and API quotas.
2. **Exposure Risk:** Any API key compiled into an Android APK can be recovered within seconds by decompiling `BuildConfig.smali` or dumping memory strings with tools like `jadx` or `frida`.
3. **Resolution:**
   - Public release builds must **never** embed hardcoded production keys.
   - For public distribution, voice STT/TTS requests should route through the authenticated KHATAWAS FastAPI backend gateway, which securely stores the `GNANI_API_KEY` in server-side environment variables.
   - When built in CI without an injected key, the app gracefully defaults `BuildConfig.GNANI_API_KEY` to `""`. Voice features operate in local shadow mode, and a notice is included in `KHATAWAS-v<VERSION>-build-info.txt`.

---

## 4. Exact Steps to Create the First Release

1. **Merge the Pull Request:**
   Visit: [https://github.com/callmetechnophile/khatabook/pull/new/release/github-actions-apk-pipeline](https://github.com/callmetechnophile/khatabook/pull/new/release/github-actions-apk-pipeline)  
   Merge `release/github-actions-apk-pipeline` into `main`.

2. **Configure GitHub Repository Secrets:**
   In **GitHub Repository → Settings → Secrets and variables → Actions**, add:
   - `ANDROID_KEYSTORE_BASE64`: Base64 string of your release `.keystore`.
   - `KEYSTORE_PASSWORD`: Keystore password.
   - `KEY_ALIAS`: Keystore alias name.
   - `KEY_PASSWORD`: Private key password.

3. **Tag and Push the First Release:**
   ```bash
   git checkout main
   git pull origin main
   git tag -a v1.0.0 -m "KHATAWAS v1.0.0 — Initial Android Testing Release"
   git push origin v1.0.0
   ```

4. **Automated Publication:**
   GitHub Actions will automatically run `.github/workflows/android-release.yml`, validate all tests and lint, build and sign `KHATAWAS-v1.0.0-release.apk`, verify the signature with `apksigner`, compute SHA-256, and publish the release asset to GitHub Releases.
