# KHATAWAS — Requirement Gap Analysis & Implementation Roadmap

**Audit Date:** 2026-11-21  
**Scope:** Gap analysis against Hackathon Core Requirements (R1–R15) and engineering implementation backlog.

---

## 1. Requirement Gap Analysis Matrix

| ID | Requirement | Current Status | Existing Implementation | Evidence & Gap | Recommended Fix |
|---|---|:---:|---|---|---|
| **R1** | **Streaming STT** | **IMPLEMENTED WITH DEFECTS** | `GnaniSttClient.kt` via Java-WebSocket to `wss://asr.gnani.ai/stt/v3/stream`. | Connects on PTT press; silently drops initial 200–500ms of audio frames during TLS handshake. Drops final transcription if user releases button immediately. | Pre-warm / maintain persistent WebSocket connection; add flush & wait buffer on PTT release before closing. |
| **R2** | **Amount Recognition Under Noise** | **IMPLEMENTED WITH DEFECTS** | RNNoise 48kHz C++ inference + 3:1 decimation. | Direct decimation causes high-frequency noise aliasing into 0–8kHz band. VAD return value discarded. | Add polyphase anti-aliasing low-pass filter; propagate VAD probability back to Kotlin pipeline. |
| **R3** | **Indic & English Code-Mixing** | **PARTIALLY IMPLEMENTED** | `HINDI_NUMBERS` dictionary + digits regex. | Hindi number words work for basic tokens (*"teen sau"*), but English number words (*"three hundred forty"*) and compound tokens (*"barah sau"*) fail. | Implement hybrid token normalizer supporting Hindi, Hinglish, and English numerals. |
| **R4** | **Credit / Payment Direction** | **IMPLEMENTED WITH DEFECTS** | `TYPE_DICTIONARY` mapping. | Defaults all unmatched inputs to `CREDIT`. Matches `"diya"` to `DEBIT` even when customer received goods (*"samaan diya"* $\to$ inverted to payment). | Replace single-word match with two-token grammatical context analyzer; never default ambiguous intents to CREDIT. |
| **R5** | **Relative Dates** | **IMPLEMENTED WITH DEFECTS** | `DATE_DICTIONARY` mapping. | Correctly parses offset for `"kal"` (-1) and `"parso"` (-2), but first-word name extractor captures the date word as the customer name (*"Kal"*). | Extract dates first, remove date tokens from sentence buffer before resolving customer entity. |
| **R6** | **Multiple Transactions per Utterance** | **NOT IMPLEMENTED** | None. `parseUtterance` returns single `Transaction?`. | Utterances like *"Ramesh ka 340 udhaar, kal 200 diya"* are merged into a single corrupt transaction. | Implement sentence clause splitting by punctuation and conjunctions (*"aur"*, *","*, *"tha"*); return `List<Transaction>`. |
| **R7** | **Spoken Corrections** | **NOT IMPLEMENTED** | None. | *"Nahi, 430"* creates a bogus transaction under customer name `"Nahi,"`. | Maintain a 5-second session state for pending transactions; detect correction negation markers (*"nahi"*, *"galat"*). |
| **R8** | **Customer Disambiguation** | **NOT IMPLEMENTED** | None. Database enforces unique single-word name. | If multiple Rameshs exist, database merges them with no interactive voice query. | Add customer disambiguation table (phone suffix / locality); trigger TTS clarification question if multiple matches exist. |
| **R9** | **Spoken Ledger Queries** | **NOT IMPLEMENTED** | None. | Queries (*"Aaj kitna udhaar gaya?"*, *"Ramesh ka total?"*) return `null` and are discarded. | Implement query intent classifier and SQL aggregation methods in `LedgerDatabase.kt`. |
| **R10**| **Response < 2s After Question** | **IMPLEMENTED AND VERIFIED** | In-memory parser + single-thread SQLite executor. | Database insert + TTS trigger takes < 80 ms once transcript is received. Meets < 2s budget. | Retain current single-threaded executor architecture. |
| **R11**| **Low-Memory (<2GB RAM) Support** | **PARTIALLY IMPLEMENTED** | Pure XML UI, no Jetpack Compose, no Coroutines. | Steady state heap is ~22 MB. However, `AudioPipeline` generates 32 KB/sec allocation churn via `ByteArray.clone()`. | Replace `clone()` with double-buffered byte array pool. |
| **R12**| **Encrypted Local Ledger** | **IMPLEMENTED AND VERIFIED** | SQLCipher AES-256 + Android Keystore 256-bit key. | Database is genuinely encrypted. Key is managed via `EncryptedSharedPreferences`. | Add balance computation and aggregation methods. |
| **R13**| **AI Disclosure on First Interaction** | **NOT IMPLEMENTED** | None. | No UI or audio disclosure exists. | Add first-launch bilingual disclosure modal and audio announcement. |
| **R14**| **Transcript Deletion After Judging** | **NOT IMPLEMENTED** | None. | No mechanism to purge database or cache. | Implement "Purge All Data" action in UI. |
| **R15**| **Secure API Key Management** | **IMPLEMENTED AND VERIFIED** | Injected via `local.properties` $\to$ `BuildConfig`. | No hardcoded keys exist in source control; `.gitignore` verified. | Compliant. |

---

## 2. Prioritized Engineering Backlog

### Phase 1: Build & Data Integrity Fixes (P0)
- **Task 1.1**: Fix WebSocket lifecycle in `GnaniSttClient` — pre-connect or buffer audio chunks during TLS handshake to stop early speech truncation.
- **Task 1.2**: Implement graceful socket closure delay (flush 600 ms) on PTT release to allow server to return final transcripts.
- **Task 1.3**: Fix Anti-Aliasing Resampling — implement low-pass filtering prior to 3:1 decimation to eliminate high-frequency noise foldback.
- **Task 1.4**: Fix Financial Inversion in `LedgerParser` — distinguish *"samaan diya"* (Credit) from *"paise diya"* (Debit). Stop default fallback to CREDIT.

### Phase 2: Core Kirana Intelligence (P1)
- **Task 2.1**: Refactor Customer Name Extraction — remove first-word heuristic; match entities using token grammatical markers (*"ka"*, *"ko"*, *"ne"*, *"ke"*).
- **Task 2.2**: Multi-Transaction Clause Splitter — support multiple transactions per utterance (*"X ka 340 udhaar, kal 200 diya"*).
- **Task 2.3**: Number Parser Normalization — add English numerals and compound Hindi terms (*"dhai sau"*, *"dedh sau"*).
- **Task 2.4**: Spoken Corrections Engine — support conversational corrections (*"Nahi, 430"*).

### Phase 3: Financial Aggregations & Queries (P2)
- **Task 3.1**: Add SQLCipher aggregation queries for total daily credit, total daily payments, and individual customer balances.
- **Task 3.2**: Implement Spoken Query Parser for balance and total inquiries (*"Ramesh ka total?"*, *"Aaj kitna udhaar gaya?"*).
- **Task 3.3**: Customer Disambiguation workflow with voice prompts.

### Phase 4: Compliance & Polish (P3)
- **Task 4.1**: First-launch AI disclosure dialog and Hindi audio confirmation.
- **Task 4.2**: One-tap data purge functionality.
- **Task 4.3**: Remove heap allocations in `AudioPipeline` using a pre-allocated circular ring buffer.
