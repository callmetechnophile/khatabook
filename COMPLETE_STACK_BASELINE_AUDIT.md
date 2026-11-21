# KHATAWAS — Complete Stack Baseline Audit (Phase A)

**Date:** 2026-11-21  
**Branch:** `feat/complete-application-stack`  
**Base Commit:** `bf2241c` (on `fix/production-readiness-remediation`)  
**Scope:** Complete repository inventory, defect classification, architectural gap analysis across Android, Audio DSP, Speech STT/TTS, NLU/Distillation, SQLite/SQLCipher, Backend API, SurrealDB, Offline Sync, and Security.

---

## 1. Repository Inventory & Environmental Baseline

### 1.1 Git and Branch Status
- **Current Branch:** `feat/complete-application-stack`
- **Upstream Origin:** `https://github.com/callmetechnophile/khatabook`
- **Recent Commits:**
  - `bf2241c`: fix(parser): support native Devanagari Hindi and strictly reject pronouns as customer names
  - `bbabc09`: docs: add comprehensive production-readiness remediation report
  - `f7c20ba`: fix(orchestration): synchronize UI with STT state machine and handle compound entries, queries, and corrections
  - `79f3ab6`: fix(db): enforce fail-closed Keystore key generation, foreign-key constraints, and atomic batch insertions
  - `095a9af`: fix(parser): represent money as integer paise, support compound transactions and Hindi number words
  - `6613b3e`: fix(stt): implement explicit SttState connection state machine, bounded backpressure queue, and trailing flush
  - `0c871f6`: fix(dsp): replace global DenoiseState with 64-bit instance pointer, validate JNI bounds, fix AudioRecord shutdown race

### 1.2 Build & Native Tooling
- **Android Gradle Plugin / Gradle:** AGP 8.x / Gradle 9.6.0
- **Java Target:** Java 17 (`compileOptions` & `kotlinOptions`)
- **Android SDK:** `compileSdk 34`, `minSdk 24`, `targetSdk 34`
- **NDK Version:** `27.1.12297006`, CMake 3.22.1, C++17 (`-std=c++17`)
- **Target ABIs:** `armeabi-v7a`, `arm64-v8a`
- **Hardware Target:** Physical device tested: Vivo / iQOO Z9x (`10BF8P109X003X2`), Android 15 (API 35), ARM64.

---

## 2. Component-by-Component Baseline Audit

### 2.1 Audio Capture & Native DSP (`app/src/main/cpp/`, `AudioPipeline.kt`, `RnnoiseProcessor.kt`, `FirDecimator3.kt`)
- **Status:** Functional on-device, stabilized in prior remediation.
- **Confirmed Correct Behavior:**
  - `rnnoise_wrapper.cpp`: Global static state eliminated; each `RnnoiseProcessor` holds a 64-bit native pointer to an isolated `DenoiseState`. JNI array bounds checked, outputs clamped to `[-32768.0f, +32767.0f]`.
  - `FirDecimator3.kt`: 31-tap symmetric low-pass filter with cutoff at 7 kHz. Provides >50 dB attenuation in the 8–24 kHz stopband, decimating 48 kHz $\to$ 16 kHz without spectral foldback.
  - `AudioPipeline.kt`: Pre-allocated 8-slot circular buffer pool for 320-byte PCM16 chunks eliminates GC churn. Deterministic shutdown sequence (`stop() -> thread.join() -> release()`) prevents native/AudioRecord race conditions.
- **Remaining Gaps for Complete Stack:**
  - Need explicit telemetry reporting for audio pipeline drop counters and frame duration jitter under extreme CPU load.

### 2.2 Speech STT & Cloud Transport (`GnaniSttClient.kt`, `MockSttBridge.kt`)
- **Status:** Functional with official Gnani / Vachana streaming endpoint.
- **Confirmed Correct Behavior:**
  - WebSocket connects to `wss://api.vachana.ai/stt/v3/stream` with correct HTTP handshake headers (`X-API-Key-ID`, `language_code: hi-IN`, `model: prisma-v2.5`, `encoding: pcm`, `sampling_rate: 16000`).
  - State machine (`SttState`: `DISCONNECTED`, `CONNECTING`, `INITIALIZING`, `READY`, `STREAMING`, `CLOSING`, `ERROR`).
  - Bounded `ArrayBlockingQueue(100)` prevents OOM on slow network.
  - `flushAndFinish()` sends 500 ms trailing silence to finalize transcript cleanly.
- **Identified Gaps:**
  - While live credentials work when configured, offline operation requires an automated seamless fallback to offline queuing and local transaction entry when network fails or times out.
  - STT partial transcripts must be visually demarcated from finalized recognized text in the UI to prevent shopkeeper confusion.

### 2.3 Multilingual Transaction Intelligence & NLU (`LedgerParser.kt`)
- **Status:** Substantially enhanced with Devanagari & Latin support, rule-based deterministic parser.
- **Confirmed Correct Behavior:**
  - Distinguishes CREDIT (`उधार`, `बाकी`, `बकाया`, `udhaar`, `baaki`) vs PAYMENT (`जमा`, `दिया`, `दिए`, `jama`, `diya`).
  - Rejects pronouns (`इसका`, `उसका`, `इनका`, `उनका`, `मेरा`, `अपना`, `iska`, `uska`) as customer names, prompting clarification: *"Kiska hisaab hai? Kripya customer ka naam bataiye."*
  - Normalizes Devanagari digits (`०-९`) and number words (`एक`, `दो`, `तीन`, `सौ`, `हजार`).
  - Supports multi-clause compound sentences, spoken queries (*"Ramesh ka total kitna hai?"*), and relative dates (`kal` = -1, `parso` = -2).
- **Gaps for Complete Stack:**
  - Requires the Nandi-Mini-150M teacher & compact student model strategy: dataset curation, distillation framework, task-specific student encoder architecture with multi-task prediction heads (intent, entities, direction, amount, dates), ONNX Mobile runtime export, and mobile benchmark evaluation.
  - Deterministic safety layer must sit on top of the student model's structured output.

### 2.4 Local Encrypted Financial Ledger (`LedgerDatabase.kt`, `DatabaseKeyManager.kt`)
- **Status:** Functional and encrypted with SQLCipher 4.5.4.
- **Confirmed Correct Behavior:**
  - Database schema uses 64-bit integer paise (`amount_paise INTEGER NOT NULL`) avoiding float rounding.
  - `PRAGMA foreign_keys = ON;` enforced on every connection.
  - Foreign key constraint: `transactions.customer_id REFERENCES customers(id) ON DELETE RESTRICT`.
  - Android Keystore backed 256-bit AES master key with fail-closed security.
  - Atomic batch inserts with transaction rollback.
- **Gaps for Complete Stack:**
  - Need `REVERSAL` transaction type support to handle cancellations without mutating historical rows.
  - Need persistent `sync_queue` table with UUID `event_id`, sync status (`PENDING`, `SYNCED`, `FAILED`), retry count, and timestamp for offline-first synchronization.
  - Need customer disambiguation metadata (e.g. phone suffix or locality).

### 2.5 Backend API & SurrealDB Cloud Stack
- **Status:** NOT YET IMPLEMENTED IN REPOSITORY.
- **Gaps:**
  - No backend service exists in `d:\bwb2`.
  - Missing authenticated REST/WebSocket ingestion API for shops, customers, and financial events.
  - Missing SurrealDB cloud persistence schema for `shop`, `customer`, `transaction`, and graph relations (`OWES`, `PAID`, `PERFORMED_BY`).
  - Missing temporary graph projection subsystem with 24-hour TTL expiry worker.
  - Missing idempotency guarantees based on client `event_id`.

### 2.6 Offline-First Synchronization Subsystem
- **Status:** NOT YET IMPLEMENTED.
- **Gaps:**
  - Android client currently only writes locally to SQLCipher.
  - Missing background sync orchestrator with network state monitoring, exponential backoff, conflict detection, and reconciliation.
  - Cloud balance must be authoritatively calculated from events rather than blindly accepting client totals.

### 2.7 UI & Shopkeeper Workflow
- **Status:** Functional push-to-talk UI, but needs polish and compliance features.
- **Gaps:**
  - Missing first-launch AI disclosure dialog and audio announcement (R13).
  - Missing data purge / reset functionality for test privacy (R14).
  - Ambiguous transactions need an interactive voice prompt and confirmation button.

### 2.8 Security & Privacy Baseline
- **Confirmed Compliant:**
  - Passphrase stored in Android Keystore, not plain SharedPreferences.
  - `.gitignore` excludes `local.properties`, keystores, and build outputs.
  - Fail-closed security on keystore errors.
- **Deferred By Contract:**
  - Cryptographic ledger hashing (HMAC, Merkle trees, signed checkpoints) is strictly deferred to future milestones.

---

## 3. Defect Classification Summary

| Component | Status | Classification | Remediation Plan |
|---|---|---|---|
| Audio Pipeline | Operational | Confirmed Correct | Maintain current native handles, FIR decimation, and ring buffer. |
| STT Transport | Operational | Confirmed Correct | Add offline detection and fallback queue. |
| NLU / Intelligence | Rule-based | Incomplete Architecture | Implement Nandi-Mini-150M teacher/student training & ONNX evaluation pipeline. |
| Local Ledger | Operational | Missing Features | Add `REVERSAL` type, `sync_queue` table, customer disambiguation. |
| Backend & SurrealDB | Missing | Major Gap | Implement FastAPI backend, SurrealDB schemas, and 24h graph expiry worker. |
| Offline Sync | Missing | Major Gap | Implement Android SyncManager with exponential backoff & idempotent upload. |
| UI & Privacy | Functional | Missing Features | Add First-Launch AI disclosure, one-tap data purge, disambiguation UI. |

---

## 4. Execution Phases

- **Phase B:** Android Audio Pipeline verification & telemetry.
- **Phase C:** Gnani STT offline resiliency & mock fallback for test coverage.
- **Phase D:** Multilingual NLU — Teacher (Nandi-Mini-150M) & Compact Student distillation pipeline, ONNX runtime evaluation.
- **Phase E & F:** Financial Ledger & SQLCipher — Add `REVERSAL`, customer disambiguation, and `sync_queue` schema.
- **Phase G:** Backend API & SurrealDB — FastAPI service, SurrealDB client, graph projections, and 24-hour expiry worker.
- **Phase H:** Offline-First Synchronization — Android client sync worker, idempotent event ingestion, conflict resolution.
- **Phase I:** Voice Feedback & UI — First-launch AI disclosure, data purge, voice confirmation.
- **Phase J & K:** Privacy, Comprehensive Testing (Audio, STT, NLU, Financial, Cloud, Device) & Final Documentation.
