# KHATAWAS — Master Test Strategy & Quality Gates

**Document Version:** 1.0.0  
**Date:** 2026-11-21  
**Scope:** Automated Unit, Integration, System, Backend, and On-Device Verification  

---

## 1. Quality Gates & Non-Negotiable Rules

1. **No Silent Financial Commits:** Any test case with missing amount, missing customer, or ambiguous direction must fail to commit and return `ParseResult.Ambiguous`.
2. **Zero Plaintext Fallback:** Database encryption tests must assert that failure to access Keystore raises `SecurityException` and never creates an unencrypted database.
3. **Paise Precision:** Monetary tests must verify integer paise math ($₹340 = 34000\text{ paise}$) with zero floating-point drift.
4. **Offline Preservation:** Transactions recorded while network is disabled must persist locally and sync once network returns without data loss.
5. **No Cryptographic Hashing in this Phase:** HMAC ledger tags and Merkle checkpoints are deferred.

---

## 2. Test Suites Overview

### 2.1 Audio DSP Test Suite (`FirDecimator3Test.kt`, C++ Unit Tests)
- **Impulse Response:** Verify 31 filter coefficients produce symmetric response.
- **Passband Flatness:** 1 kHz tone at 48 kHz decimates to 1 kHz at 16 kHz with $< 0.2\text{ dB}$ attenuation.
- **Passband Edge:** 7 kHz tone experiences $< 3\text{ dB}$ attenuation.
- **Stopband Rejection:** 8 kHz, 12 kHz, and 16 kHz tones experience $> 50\text{ dB}$ attenuation.
- **DC & Digital Silence:** Verify zero DC offset and zero noise injection.
- **Sample Clamping:** Verify floating-point samples outside $[-32768.0, 32767.0]$ are clamped without NaN/Infinity propagation.

### 2.2 Speech STT & Transport Suite (`GnaniSttClientTest.kt`)
- **State Machine Transitions:** Verify valid path `DISCONNECTED` $\to$ `CONNECTING` $\to$ `INITIALIZING` $\to$ `READY` $\to$ `STREAMING` $\to$ `CLOSING`.
- **Audio Transmission Gate:** Assert audio chunks sent while in `CONNECTING` or `INITIALIZING` are buffered in queue and not dropped.
- **Trailing Silence Flush:** Verify `flushAndFinish()` sends 500 ms zero-padding to trigger cloud VAD `speech_end`.
- **Mock Transport Fallback:** Verify `MockSttBridge` delivers synthetic transcripts for offline automated UI tests.

### 2.3 Multilingual Parser & NLU Suite (`LedgerParserTest.kt`, `ParserAuditTest.kt`)
- **Hindi Devanagari:**
  - `रमेश का 340 उधार` $\to$ CREDIT, 34000 paise, Ramesh
  - `सुरेश ने 200 दिया` $\to$ PAYMENT, 20000 paise, Suresh
  - `रमेश का ३४० उधार` $\to$ CREDIT, 34000 paise (Devanagari numerals)
- **Hinglish Latin:**
  - `Ramesh ka 340 udhaar, kal 200 diya tha` $\to$ Compound 2 clauses: Clause 1 (Credit ₹340 today), Clause 2 (Payment ₹200 yesterday).
  - `Ramesh ko 500 jama kiya` $\to$ PAYMENT ₹500.
- **Pronoun Rejection:**
  - `इसका 340 उधार` $\to$ Ambiguous ("Kiska hisaab hai?")
  - `uska 200 jama` $\to$ Ambiguous.
- **Spoken Queries:**
  - `Ramesh ka total kitna hai?` $\to$ ParseResult.Query(customer: "Ramesh")
  - `Aaj kitna udhaar gaya?` $\to$ ParseResult.QueryAll()
- **Spoken Corrections:**
  - `Nahi, 430` $\to$ ParseResult.Correction(newAmount: 43000)

### 2.4 Financial Ledger & SQLCipher Suite (`LedgerDatabaseTest.kt`)
- **Paise Integrity:** Verify ₹340.50 is stored as 34050 paise.
- **Balance Aggregation:** Verify $Net = \sum CREDIT - \sum PAYMENT \pm REVERSAL$.
- **Atomic Batch Rollback:** If 2nd clause of a compound transaction fails, verify 1st clause is also rolled back.
- **Reversal Audit Trail:** Reversal of a ₹100 credit inserts a `REVERSAL` row; does not delete the original row.
- **Foreign Keys:** Inserting a transaction with an invalid customer ID throws `SQLiteConstraintException`.

### 2.5 Backend API & SurrealDB Cloud Suite (`test_backend_api.py`)
- **Authentication:** Reject unauthenticated requests with `401 Unauthorized`.
- **Idempotent Ingestion:** Sending identical `event_id` twice returns `DUPLICATE_ACCEPTED` with zero double-counting.
- **Authoritative Balance:** Server ledger recalculates customer balance correctly.
- **Graph Projections:** Verify `customer->OWES->shop` edge creation.
- **24-Hour Expiry Worker:** Trigger cleanup worker and verify `temporary_event` older than 24 hours are removed while `transaction` rows remain.

### 2.6 Offline-First Synchronization Suite (`SyncManagerTest.kt`)
- **Offline Persistence:** Transactions queued in `sync_queue` table with status `PENDING`.
- **Retry Backoff:** On network timeout, verify retry interval increases exponentially ($1\text{s}, 2\text{s}, 4\text{s}, 8\text{s}$).
- **Reconciliation:** Once online, batch is ingested and marked `SYNCED`.

### 2.7 Device Performance & Physical Benchmarking
- **Device Target:** Vivo / iQOO Z9x (`10BF8P109X003X2`), Android 15, ARM64.
- **Metrics to Measure:**
  - Cold startup latency ($< 1.5\text{s}$).
  - Audio capture latency ($< 50\text{ms}$).
  - STT round-trip latency ($< 1.2\text{s}$).
  - Parser & DB commit latency ($< 25\text{ms}$).
  - Heap RAM usage in steady state ($< 35\text{MB}$).
