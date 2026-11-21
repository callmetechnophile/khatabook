# KHATAWAS — खातावास

**A Rural-First, Privacy-Focused Digital Udhaar Ledger for Indian Shopkeepers.**

KHATAWAS is a digital replacement for the traditional paper udhaar (credit) notebook, purpose-built for small-scale merchants, kirana stores, and rural retailers across India. Built offline-first with SQLCipher AES-256 local database encryption, on-device AI transaction intelligence, Gnani voice recognition in regional languages (Hindi, Kannada, and Hinglish), and cloud sync backed by SurrealDB.

---

## 📥 Download Latest Android APK

Get the verified release APK directly from GitHub Releases:

- **Latest Release:** [Download KHATAWAS on GitHub Releases](https://github.com/callmetechnophile/khatabook/releases)
- **Direct Asset:** [`KHATAWAS-v0.1.0-release.apk`](https://github.com/callmetechnophile/khatabook/releases/download/v0.1.0/KHATAWAS-v0.1.0-release.apk)
- **SHA-256 Checksum:** [`KHATAWAS-v0.1.0-SHA256.txt`](https://github.com/callmetechnophile/khatabook/releases/download/v0.1.0/KHATAWAS-v0.1.0-SHA256.txt)

```bash
# Verify checksum on Linux/macOS
sha256sum -c KHATAWAS-v0.1.0-SHA256.txt

# Verify checksum on Windows PowerShell
(Get-FileHash -Algorithm SHA256 ./KHATAWAS-v0.1.0-release.apk).Hash -eq "58d6e32039c2f371f8c6689baf728b4a090080eb858a413e969325064d4b3f16"
```

---

## 📱 Android Application Overview

- **Rural-First Design:** High-contrast 60dp touch targets, clear card hierarchy, and large readable Hindi/Kannada numerals designed for readability in bright sunlight and one-handed operation.
- **Offline-First Resilience:** Zero-dependency local operation. Every transaction is committed immediately to encrypted local SQLite storage before any cloud operations.
- **Voice-to-Ledger:** Spoken transaction entry (*e.g., "Ramesh ko 250 ka udhaar diya"*) processed through RNNoise edge noise cancellation and on-device INT8 language models.
- **Privacy Lock by Default:** Financial figures and customer balances remain masked (`###XXX`) until explicitly authenticated via Application PIN or Android BiometricPrompt (fingerprint/face).
- **SurrealDB Native Graph:** Resolves complex customer and family relationship queries (*e.g., "Arun ke papa"*) while maintaining strict multi-tenant isolation.

---

## 🛠️ System Requirements & Permissions

### Compatibility

| Attribute | Specification |
|---|---|
| **Package Name** | `com.khatawas` |
| **Minimum Android Version** | Android 7.0 (Nougat, API Level 24) |
| **Target Android Version** | Android 14 (API Level 34) |
| **Supported Architectures** | `arm64-v8a`, `armeabi-v7a`, `x86_64` |

### Required Permissions

| Permission | Purpose |
|---|---|
| `android.permission.RECORD_AUDIO` | Required for spoken voice ledger entries and voice assistant queries. |
| `android.permission.INTERNET` | Optional; required only when syncing encrypted ledger events to SurrealDB Cloud or streaming Gnani voice services. |
| `android.permission.MODIFY_AUDIO_SETTINGS` | Real-time audio gain calibration during microphone input. |
| `android.permission.USE_BIOMETRIC` | Biometric fingerprint/face unlock to unmask sensitive balance figures. |

---

## 📲 Step-by-Step Installation Instructions

1. Open the [KHATAWAS GitHub Releases page](https://github.com/callmetechnophile/khatabook/releases).
2. Download `KHATAWAS-v0.1.0-release.apk` (or the latest release).
3. Tap the downloaded `.apk` file in your notifications or download folder.
4. If Android displays an **"Install unknown apps"** prompt:
   - Tap **Settings**.
   - Enable **"Allow from this source"** for your browser or file manager.
   - *Note: Android does this for all apps installed outside Google Play. Do not disable system protections globally.*
5. Tap **Install** and wait for installation to complete.
6. Tap **Open** to launch KHATAWAS.
7. Grant the **Microphone** permission when prompted if you plan to use voice entry.

---

## 🔒 Security & Privacy Architecture

- **No Remote Telemetry:** KHATAWAS does not contain third-party ad trackers or behavioural analytics SDKs.
- **AES-256 SQLCipher Encryption:** The local database file on your device is encrypted with SQLCipher using keys managed through Android Keystore.
- **Tamper-Evident Ledger:** Local events form an append-only, cryptographic hash-linked chain (`event_uid`, `prev_event_hash`, `payload_hash`). Past transactions cannot be retroactively modified or deleted without invalidating the chain.
- **Deterministic Disambiguation:** Spoken references to relatives (*e.g., "Arun ke papa"*) are never auto-assigned to financial accounts. The system requires merchant confirmation before attributing credit or debit.

---

## 🚀 Setting Up Release Keystore for GitHub Actions CI/CD

To automate APK packaging and publishing on GitHub Releases when pushing a tag (`v0.1.0`), configure these secrets in **GitHub Repository → Settings → Secrets and variables → Actions**:

| Secret Name | Description |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | Base64-encoded string of your release `.keystore` / `.jks` file. |
| `KEYSTORE_PASSWORD` | Password protecting the keystore file. |
| `KEY_ALIAS` | Alias name of the signing key inside the keystore. |
| `KEY_PASSWORD` | Password protecting the specific key alias. |

### Generating a Release Keystore Locally:

```bash
# Generate a new 2048-bit RSA release keystore (valid for 25+ years)
keytool -genkeypair -v \
  -keystore khatawas_release.keystore \
  -alias khatawas_release \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -storepass "YOUR_SECURE_PASSWORD" \
  -keypass "YOUR_SECURE_PASSWORD" \
  -dname "CN=KHATAWAS, OU=Mobile, O=Khatawas, L=Bangalore, ST=Karnataka, C=IN"

# Encode keystore to base64 for GitHub Actions secret
# On Linux / macOS:
base64 -w 0 khatawas_release.keystore > keystore_base64.txt

# On Windows PowerShell:
[Convert]::ToBase64String([IO.File]::ReadAllBytes("khatawas_release.keystore")) | Out-File -Encoding utf8 keystore_base64.txt
```

> **Warning:** NEVER commit keystore files (`.keystore`, `.jks`) or passwords to git. Store your release keystore safely in a password manager or offline backup.

---

## 📜 Release History

- **v0.1.0** *(Pre-release)* — Initial Android Testing Release:
  - Complete Rural-First UI with Hindi and Kannada localization.
  - SQLCipher encrypted local ledger with append-only hash chains.
  - On-device INT8 student model & voice transcription pipeline.
  - SurrealDB native graph relationship model & entity resolution.
  - Verified on physical Android hardware (Vivo / ARM64).

---

## 🐛 Bug Reporting & Contributions

Found an issue or want to suggest an improvement?
- Report bugs: [GitHub Issues](https://github.com/callmetechnophile/khatabook/issues)
- Include your Android version, device model, and steps to reproduce.
