# KHATAWAS — SurrealDB Cloud Architecture & Repository Audit

**Document Version:** 1.0.0  
**Date:** 2026-11-22  
**System:** KHATAWAS Cloud Database & Synchronization Layer  
**Author:** Senior Backend Architect & Database Security Engineer  

---

## 1. Executive Summary

This architecture audit evaluates the state of the **KHATAWAS** codebase regarding offline-first financial ledger storage, cloud synchronization, database persistence, and read-only AI query boundaries.

KHATAWAS is a voice-first, multilingual digital ledger designed specifically for Indian Kirana shopkeepers. It couples an Android offline-first client (SQLite + SQLCipher AES-256, Android Keystore, local NandiBERT intelligence) with a FastAPI backend persisting records to **SurrealDB Cloud**.

This document benchmarks the current implementation against the **Non-Negotiable Architectural Rules**, identifies missing components and architectural gaps, assesses migration risks, and defines the target SurrealDB Cloud schema and synchronization protocol.

---

## 2. Existing Architecture Analysis

### 2.1 Android Client (`app/src/main/java/com/khatawas/`)
- **Local Storage Engine:** [LedgerDatabase.kt](file:///D:/bwb2/app/src/main/java/com/khatawas/db/LedgerDatabase.kt) utilizes SQLCipher 4.5.4 with AES-256 encryption. The encryption passphrase is dynamically generated and secured via Android Keystore ([DatabaseKeyManager.kt](file:///D:/bwb2/app/src/main/java/com/khatawas/db/DatabaseKeyManager.kt)).
- **Cryptographic Event Integrity:** [EventIntegrity.kt](file:///D:/bwb2/app/src/main/java/com/khatawas/db/EventIntegrity.kt) provides deterministic canonical JSON serialization and SHA-256 digest computation for events. Genesis hash is defined as 64 zeros (`0000000000000000000000000000000000000000000000000000000000000000`).
- **Synchronization Policy:** [SyncManager.kt](file:///D:/bwb2/app/src/main/java/com/khatawas/sync/SyncManager.kt) enforces user-initiated cloud backup via the UI refresh/sync action (`performManualBackup`). Background auto-sync on transaction creation or app startup has been strictly deprecated and disabled to respect Kirana bandwidth constraints and offline autonomy.
- **Unified Querying:** [UnifiedLedgerQueryService.kt](file:///D:/bwb2/app/src/main/java/com/khatawas/ai/UnifiedLedgerQueryService.kt) provides read-only query capabilities, merging local SQLCipher records with FastAPI cloud records, deduplicating by stable event UUID, detecting conflicting digests, and calculating integer paise aggregates.

### 2.2 Backend (`backend/app/`)
- **FastAPI Application:** [main.py](file:///D:/bwb2/backend/app/main.py) exposes REST endpoints (`/api/v1/health`, `/api/v1/auth/shop`, `/api/v1/sync/batch`, `/api/v1/customers`, `/api/v1/customers/{customer_name}/ledger`, `/api/v1/projections/daily`).
- **Data Models:** [models.py](file:///D:/bwb2/backend/app/models.py) specifies Pydantic request/response structures.
- **Persistence Prototype:** [surreal_client.py](file:///D:/bwb2/backend/app/surreal_client.py) houses an in-memory prototype (`SurrealCloudStore`) with thread locks. While it implements canonical hashing and idempotent deduplication, it does not connect to a real SurrealDB instance, lacks a formal versioned migration runner, and does not model tenant memberships, customer relationships, device registries, or conflict records.

---

## 3. Database Schema Audit: Current vs. Target

| Table / Concept | Current Implementation Status | Target Requirement | Gap / Remediation |
|---|---|---|---|
| `app_user` | Not present (phone stored on shop) | SCHEMAFULL table with external UID, phone, status | Create `app_user` table and isolate user identity from shop entities. |
| `shop` | Present in memory (`name`, `owner_phone`) | SCHEMAFULL table with `owner_uid`, `shop_name`, `preferred_language`, `currency = 'INR'`, `timezone` | Formalize SurrealQL schema with INR currency constraint and owner foreign key. |
| `shop_member` | Not present | Role-based tenant membership (`owner`, `admin`, `operator`, `viewer`) | Implement explicit membership table to enforce server-side tenant isolation. |
| `customer` | In-memory key `shop_id:name` | SCHEMAFULL table with `customer_uid`, `shop_uid`, `display_name`, `normalized_name`, `phone_e164` | Never use customer names as primary identifiers. Use stable `customer_uid`. |
| `customer_alias` | Not present | Tracks phonetic and spoken aliases for voice recognition | Create `customer_alias` table to support Indian Kirana voice queries. |
| `customer_relationship` | Not present | Tracks family relations without destructive customer merging | Create `customer_relationship` with confirmation states (`confirmed`, `unconfirmed`, `rejected`). |
| `device_registry` | Not present | Tracks registered Android devices per shop | Create `device_registry` table with platform `android`. Enforce device authorization. |
| `ledger_event` | In-memory `transactions` dict | Immutable append-only financial event table with SHA-256 hash chaining | Full SCHEMAFULL implementation with `balance_effect_paise`, sequence, and previous digest. |
| `event_chain_head` | Not present | Tracks latest device sequence, event UID, and digest per device/shop | Create `event_chain_head` with unique index `(shop_uid, device_uid)`. |
| `device_sync_state` | Not present | Tracks high-water mark of accepted sync per device | Create `device_sync_state` table. |
| `sync_receipt` | In-memory status list | Permanent receipt table with unique `(shop_uid, event_uid)` | Create `sync_receipt` recording acceptance result (`accepted`, `duplicate`, `rejected`, `conflict`). |
| `conversation` & `chat_message` | Not present in cloud DB | Stores persistent chatbot conversations and allowlisted query history | Implement read-only chat persistence tables with role, input modality, and retrieved event IDs. |
| `audit_event` | Not present | Records security and operational audits | Create `audit_event` recording actor, action, device, and outcome. |
| `conflict_record` | Not present | Durable evidence store for conflicting events (same UUID, differing digest) | Create `conflict_record` table to prevent silent data loss or overwrite. |

---

## 4. Existing Synchronization & Canonicalization Audit

1. **Deterministic Serialization:**
   - Android's `EventIntegrity.canonicalSerialize()` formats:
     `{"amount_paise":<long>,"customer_name":"<str>","event_id":"<str>","note":"<str>","previous_event_hash":"<str>","schema_version":<int>,"timestamp":<long>,"type":"<str>"}`
   - Python's `compute_canonical_hash()` formats identical JSON keys sorted alphabetically with no whitespace (`separators=(',', ':')`).
   - Both produce bit-exact identical SHA-256 hashes.
2. **Hash Chaining Formula:**
   - Formula: $H(n) = \text{SHA256}(H(n-1) \parallel \text{CanonicalJSON}(\text{Event}(n)))$
   - Both utilize genesis hash `0000000000000000000000000000000000000000000000000000000000000000`.
3. **Idempotency & Conflict Policy:**
   - **Same UUID + Same Digest:** Identified as duplicate; original record preserved; returns `duplicate` receipt without modifying balance.
   - **Same UUID + Different Digest:** Identified as conflict; original event preserved; conflict evidence stored in `conflict_record`; returns `conflict` receipt; balance remains unchanged.
   - **Different UUIDs:** Handled as distinct transactions, even if amounts and timestamps match.

---

## 5. Security & Tenant Isolation Audit

1. **Authentication Boundary:**
   - Existing code verifies token presence via `verify_auth`, but does not decode JWT claims or enforce shop membership.
   - **Remediation:** Implement cryptographically verified JWT tokens containing `user_uid`, `shop_uid`, and `role`. Reject cross-tenant access at the dependency level.
2. **Device Registration:**
   - Client sync requests must validate that `originating_device_uid` is authorized for the authenticated `shop_uid`.
3. **Sensitive Credential Protection:**
   - No Android client code or APK package contains SurrealDB credentials or root keys.
   - No plaintext passwords, PINs, or biometric templates are stored in SurrealDB.
   - SQLCipher encryption keys reside solely in Android Keystore on the physical device.

---

## 6. Migration Risks & Backward Compatibility

1. **Existing Android Sync Client Compatibility:**
   - The deployed Android client calls `POST /api/v1/sync/batch` with `SyncBatchRequest`.
   - **Action:** Retain `POST /api/v1/sync/batch` as a fully backward-compatible wrapper while simultaneously exposing the canonical `POST /api/v1/sync/events` endpoint.
2. **Customer Name vs. Customer UID:**
   - The legacy Android model identifies customers by name strings.
   - **Action:** Maintain an automatic deterministic customer resolver mapping `(shop_uid, normalized_name)` to a stable `customer_uid` while populating both `customer` and `customer_alias` tables.
3. **Zero Data Loss Guarantee:**
   - All migrations are additive (version-controlled `.surql` scripts). No `DROP TABLE` or destructive resets will run in production environments.

---

## 7. Implementation Roadmap

- **Phase 2:** Version-controlled SurrealQL schema migrations (`001_initial_schema.surql`, `002_integrity_constraints.surql`, `003_query_indexes.surql`).
- **Phase 3:** Automated migration runner (`migration_runner.py`) with `schema_version` tracking in SurrealDB.
- **Phase 4:** Append-only event processing & multi-version canonicalization service.
- **Phase 5:** SHA-256 event integrity and hash-chain verification engine.
- **Phase 6:** FastAPI synchronization endpoints (`/api/v1/sync/*`, `/api/v1/customers/*`, `/api/v1/ledger/*`).
- **Phase 7:** Deterministic integer-paise financial balance engine.
- **Phase 8:** Read-only allowlisted chatbot query service with persistent conversation logging.
- **Phase 9:** Security controls, JWT middleware, tenant isolation, and audit logging.
- **Phase 10:** Integration test suite using embedded SurrealDB engine (`mem://`) and performance benchmarks.

