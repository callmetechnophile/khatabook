# KHATAWAS — Database Boundary & Security Architecture (Milestone 8)

## 1. Executive Summary

Milestone 8 defines the boundary between the native Rust computational engine and the Android persistent storage subsystem.

As mandated by project rules:
1. **Cryptographic ledger hashing (HMAC, Merkle trees, hash chains, signed integrity checkpoints) is strictly deferred to a future milestone.**
2. **SQLCipher database encryption remains required and is fully operational.**
3. Android Keystore key management remains in Kotlin (`DatabaseKeyManager.kt`).
4. Rust performs pure, deterministic numerical computations, balance aggregations, and entity validation without directly touching raw disk drivers.

---

## 2. Division of Responsibilities

| Responsibility | Owning Component | Language | Justification |
|---|---|---|---|
| Hardware Key Generation | `DatabaseKeyManager.kt` | Kotlin | Uses Android Keystore (`KeyStore` / `KeyGenParameterSpec`) hardware-backed security (StrongBox / TEE). |
| AES-256 Database Encryption | `LedgerDatabase.kt` (SQLCipher) | Kotlin / C | `net.zetetic:android-database-sqlcipher:4.5.4` manages page-level AES-256-CBC encryption on disk. |
| Transaction Persistence & ACID | `LedgerDatabase.kt` | Kotlin | Android `SQLiteDatabase` transactions handle commits, rollbacks, and cursor lifecycles. |
| Net Balance & Integer Math | `RustEngine.nativeCalculateBalance` | Rust | 64-bit integer signed arithmetic eliminates rounding bugs and provides high-speed aggregation. |
| Financial Intent Validation | `RustEngine.nativeParseUtterance` | Rust | Zero-allocation string parsing, pronoun rejection guard, and Indic numeral mapping. |
| Cloud Sync Queue | `SyncManager.kt` | Kotlin | Manages offline-first event synchronization with remote SurrealDB backend. |

---

## 3. Data Flow Across the Boundary

```
      Microphone (48 kHz PCM16)
                 │
                 ▼
       AudioPipeline.kt
                 │
                 ▼
     [JNI nativeFirProcess]
                 │
                 ▼
      Rust FIR Decimator (16 kHz)
                 │
                 ▼
       Gnani STT WebSocket
                 │
                 ▼
         Raw Hindi Speech
                 │
                 ▼
   [JNI nativeParseUtterance]
                 │
                 ▼
      Rust LedgerEngine
    ┌────────────┴────────────┐
    ▼                         ▼
[Ambiguous/Pronoun]       [Valid Transaction]
    │                         │
    ▼                         ▼
VoiceFeedback.kt        AppOrchestrator.kt
(TTS Clarification)           │
                              ▼
                        LedgerDatabase.kt
                      (SQLCipher Encrypted)
                              │
                              ▼
                   [JNI nativeCalculateBalance]
                              │
                              ▼
                     Updated Net Balance (₹)
                              │
                              ▼
                     Voice Confirmation
```

---

## 4. Security & Privacy Guarantees

1. **Zero Logging of Plaintext Financial Transcripts**: Production logs never output raw financial amounts, customer names, or API credentials.
2. **Key Security**: Encryption keys are generated via Android Keystore `AES/GCM/NoPadding` and never stored in plaintext on shared storage.
3. **No Unencrypted Staging**: Audio buffers in `AudioPipeline` are processed in ephemeral memory and discarded immediately after streaming.
4. **No Cryptographic Hashing Premature Optimization**: In strict compliance with guidelines, no HMAC hash chains or Merkle audit tables were introduced in this milestone.
