# KHATAWAS — System Architecture Specification

**Document Version:** 1.0.0  
**Date:** 2026-11-21  
**Status:** Approved for Implementation  

---

## 1. System Overview

KHATAWAS is a voice-first, offline-first digital ledger built specifically for Indian Kirana store owners. It captures noisy shop speech in Hindi, Kannada, English, and romanized code-mixing, processes audio through an on-device DSP pipeline, transcribes speech via streaming ASR, interprets financial transactions through an Indic language intelligence layer guarded by a deterministic safety validator, stores records locally in an encrypted SQLCipher database, and synchronizes transactions idempotently with a SurrealDB cloud backend with temporary 24-hour graph projections.

---

## 2. High-Level Architecture Diagram

```
+------------------------------------------------------------------------------------+
|                                ANDROID CLIENT                                      |
|                                                                                    |
|  +---------------------+        +--------------------+       +------------------+  |
|  |  AudioRecord (Mic)  |  --->  | RNNoise C++ Engine | --->  |  FIR Decimator   |  |
|  |  48 kHz Mono PCM16  |        | Instance State     |       |  48 kHz -> 16 kHz|  |
|  +---------------------+        +--------------------+       +------------------+  |
|                                                                       |            |
|                                                               160-sample 10ms frame|
|                                                                       v            |
|  +---------------------+        +--------------------+       +------------------+  |
|  |   UI & TTS Voice    |  <---  |   AppOrchestrator  | <---  |  Gnani STT Client|  |
|  |   Feedback Engine   |        |   Coordination     |       |  WebSocket Client|  |
|  +---------------------+        +--------------------+       +------------------+  |
|            ^                              |                           |            |
|            |                              v                     Final Transcript   |
|            |                    +--------------------+                |            |
|            |                    | Deterministic & ML | <--------------+            |
|            |                    | Language Parsing   |                             |
|            |                    +--------------------+                             |
|            |                              |                                        |
|            |                     Valid Transaction                                 |
|            |                              v                                        |
|  +---------------------------------------------------+                             |
|  |         Local Encrypted SQLCipher Database         |                             |
|  |   - customers                                     |                             |
|  |   - transactions (integer paise, CREDIT/PAY/REV)  |                             |
|  |   - sync_queue (offline events, retry backoff)    |                             |
|  +---------------------------------------------------+                             |
|                           |                                                        |
|                           v                                                        |
|  +---------------------------------------------------+                             |
|  |            Offline-First SyncManager              |                             |
|  |   - Network monitoring                            |                             |
|  |   - Idempotent UUID event batching                |                             |
|  +---------------------------------------------------+                             |
+---------------------------|--------------------------------------------------------+
                            | HTTPS / REST (Idempotent Event Ingestion)
                            v
+------------------------------------------------------------------------------------+
|                                 BACKEND API                                        |
|                                                                                    |
|  +---------------------------------------------------+                             |
|  |          FastAPI Service (/api/v1/sync)           |                             |
|  |   - Shop authentication & token verification      |                             |
|  |   - Payload validation & schema enforcement       |                             |
|  |   - Idempotent deduplication via event_id         |                             |
|  |   - Conflict resolution & authoritative balance   |                             |
|  +---------------------------------------------------+                             |
|                           |                                                        |
|                           v                                                        |
|  +---------------------------------------------------+      +-------------------+  |
|  |                 SurrealDB Cloud                   |      | 24-Hour Temporary |  |
|  |  Permanent Records:                               | ---> | Graph Projection  |  |
|  |   - shop, customer, transaction                   |      | Expiry Worker     |  |
|  |  Graph Relationships:                              |      +-------------------+  |
|  |   - customer->OWES->shop, customer->PAID->shop    |                             |
|  |  Temporary 24h Projections:                       |                             |
|  |   - daily_projection, recent_event                |                             |
|  +---------------------------------------------------+                             |
+------------------------------------------------------------------------------------+
```

---

## 3. Subsystem Breakdown

### 3.1 Audio Capture & DSP
- **Sample Rate:** 48,000 Hz input downsampled to 16,000 Hz.
- **Channels:** 1 (Mono), PCM16 little-endian.
- **Frame Duration:** 10 ms (480 input samples $\to$ 160 output samples = 320 bytes).
- **RNNoise Denoiser:** Pinned upstream RNNoise C library. Native instance handle (`jlong`) tied to Kotlin `RnnoiseProcessor`. Clamped outputs in range `[-32768.0f, +32767.0f]`.
- **FIR Decimator:** 31-tap symmetric FIR low-pass filter with 7 kHz cutoff and >50 dB stopband attenuation. Decimates $3:1$ without aliasing.
- **Buffer Pool:** 8-slot circular ring buffer pool in `AudioPipeline` to avoid per-frame GC allocations.

### 3.2 Streaming Speech Recognition (Gnani STT)
- **Protocol:** WebSocket connection over TLS (`wss://api.vachana.ai/stt/v3/stream`).
- **Headers:** `X-API-Key-ID`, `language_code: hi-IN`, `model: prisma-v2.5`, `encoding: pcm`, `sampling_rate: 16000`.
- **State Machine:** `DISCONNECTED` $\to$ `CONNECTING` $\to$ `INITIALIZING` $\to$ `READY` $\to$ `STREAMING` $\to$ `CLOSING`.
- **Microphone Synchronization:** Microphone captures audio only when socket state is `READY`, preventing loss of the first word/syllable.
- **Graceful Termination:** On PTT release, 500 ms of trailing silence is flushed to ensure cloud VAD triggers `speech_end` and returns the final transcript.

### 3.3 Indic Language Intelligence & NLU
- **Supported Languages:** Hindi (Devanagari & Latin), Kannada, English, and Hinglish code-mixing.
- **Teacher Model:** Nandi-Mini-150M as Indic teacher for synthetic dataset generation, soft-target distillation, and entity boundary labelling.
- **Student Model:** Task-specific compact Transformer / BiLSTM encoder (~5M parameters) with multi-task prediction heads:
  1. Intent classification (TRANSACTION, QUERY, CORRECTION, UNKNOWN).
  2. Direction classification (CREDIT, PAYMENT).
  3. Customer entity extraction (token classification / BIO tagging).
  4. Amount value extraction.
  5. Date offset classification (TODAY, YESTERDAY, DAY_BEFORE).
- **Deterministic Financial Safety Layer:**
  - Evaluates student output.
  - Rejects pronouns (`इसका`, `उसका`, `iska`, `uska`) as customer names.
  - Rejects transactions with missing direction or ambiguous amounts.
  - Converts numbers from words and digits into exact integer paise.
  - Prompts shopkeeper for voice clarification if confidence is low or ambiguity is present.

### 3.4 Local Financial Ledger (SQLite + SQLCipher)
- **Encryption:** AES-256 via SQLCipher 4.5.4.
- **Key Storage:** Android Keystore hardware-backed 256-bit AES master key via `EncryptedSharedPreferences`.
- **Currency Unit:** 64-bit integer paise (`amount_paise INTEGER NOT NULL`), eliminating floating-point rounding errors.
- **Transaction Types:**
  - `CREDIT`: Goods given on credit (increases customer outstanding debt).
  - `PAYMENT`: Money received from customer (decreases customer outstanding debt).
  - `REVERSAL`: Explicit reversal of a previous transaction (undoes effect without deleting audit trail).
- **Tables:** `customers`, `transactions`, `sync_queue`.
- **Foreign Keys:** Enabled on every connection (`PRAGMA foreign_keys = ON;`).

### 3.5 Offline-First Synchronization
- **Sync Architecture:** Local-first. When an action is performed, it is committed immediately to the local encrypted SQLite ledger and placed in the `sync_queue` with status `PENDING`.
- **Idempotency:** Every transaction generated on the client receives a cryptographically random UUID (`event_id`).
- **Network Resilience:** Android `SyncManager` monitors connectivity. When online, pending events are sent in batches to the backend. On failure, exponential backoff (1s, 2s, 4s, 8s, up to 60s) is applied.
- **Conflict Handling:** Server is the authority for global sequencing, but client events are ingested idempotently using `event_id`. Server re-computes customer balance from the ledger stream.

### 3.6 Backend API & Cloud Persistence (SurrealDB)
- **Framework:** Python FastAPI with Pydantic validation.
- **Authentication:** Shop API Key or JWT Bearer token in `Authorization` header.
- **Database:** SurrealDB multi-model database.
- **Permanent Entities:**
  - `shop`: Stores shopkeeper profile, phone, language preferences.
  - `customer`: Stores customer profile, shop relation, current balance.
  - `transaction`: Stores immutable financial events (`event_id`, `amount_paise`, `type`, `timestamp`).
  - Graph edges: `customer->OWES->shop`, `customer->PAID->shop`.
- **Temporary Graph Projections:**
  - Graph records representing 24-hour activity (`recent_activity`, `daily_projection`).
  - Automatically pruned by a background 24-hour expiry worker.
  - Never deletes underlying permanent `transaction` records.

### 3.7 Shopkeeper UI & Accessibility
- **Push-to-Talk Interaction:** Physical hold-to-talk button minimizes ambient noise capture and battery drain.
- **Visual Feedback:** Real-time STT partial transcripts, connection status indicator, and structured transaction confirmation cards.
- **Auditory Feedback:** Offline Android TTS confirms customer name, transaction type, and amount in Hindi/English.
- **Privacy & Compliance:**
  - First-launch AI disclosure dialog explaining speech processing.
  - One-tap data purge option to clear local database and cache.
  - Zero raw audio or unredacted transcript logging.

---

## 4. Engineering Invariants & Rules
1. **Never Silently Guess Transaction Direction:** If an utterance lacks explicit credit or payment markers, reject it and request clarification.
2. **Never Treat Pronouns as Customer Names:** Pronouns (`इसका`, `उसका`, `iska`, `uska`) must trigger a disambiguation prompt.
3. **No Float Arithmetic for Money:** All financial calculations are executed in integer paise ($₹1.00 = 100 \text{ paise}$).
4. **Offline Resilience:** All transactions must be fully committable and queryable even when the device is completely offline.
5. **No Cryptographic Hashing in this Phase:** HMAC ledger tags and Merkle tree checkpoints are deferred to the future integrity milestone.
