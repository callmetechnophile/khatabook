# KHATAWAS — Database Migrations & SurrealDB Schema Management

This directory contains versioned SurrealQL migrations and runner tooling for the KHATAWAS SurrealDB Cloud database.

---

## 1. Directory Structure

```text
backend/database/
├── migrations/
│   ├── 001_initial_schema.surql          # SCHEMAFULL table definitions & fields
│   ├── 002_integrity_constraints.surql   # Primary unique identification indexes
│   └── 003_query_indexes.surql           # Composite query performance indexes
├── migration_runner.py                   # Automated sequential migration runner
├── schema_version.py                     # Version metadata structures
├── surreal_client.py                     # Connection lifecycle & pooling
└── README.md                             # Documentation & operational procedures
```

---

## 2. Versioned Migrations

Migrations follow strict sequential numbering (`001_*.surql`, `002_*.surql`, etc.).

1. **`001_initial_schema.surql`**:
   - Defines SCHEMAFULL tables: `app_user`, `shop`, `shop_member`, `customer`, `customer_alias`, `customer_relationship`, `device_registry`, `ledger_event`, `event_chain_head`, `device_sync_state`, `sync_receipt`, `conversation`, `chat_message`, `audit_event`, `conflict_record`, and `schema_version`.
   - Defines typed fields and business assertions (`amount_paise > 0`, `currency = 'INR'`, valid roles, categories, and event types).
2. **`002_integrity_constraints.surql`**:
   - Enforces unique identification constraints:
     - `ledger_event.event_uid` (Global uniqueness)
     - `(shop_uid, originating_device_uid, device_sequence)` (Strict sequence continuity)
     - `(shop_uid, customer_uid)` (Customer identity uniqueness)
     - `(shop_uid, user_uid)` (Tenant membership uniqueness)
     - `(shop_uid, device_uid)` (Device registration uniqueness)
     - `(shop_uid, event_uid)` (Sync receipt uniqueness)
3. **`003_query_indexes.surql`**:
   - Composite indexes for low-latency ledger filtering, customer lookups, audit searches, and chatbot conversation retrieval.

---

## 3. Running Migrations

Migrations are automatically checked and applied upon FastAPI application startup, or can be run via Python:

```python
from surrealdb import Surreal
from backend.database.migration_runner import MigrationRunner

db = Surreal("wss://your-cloud-instance.surrealdb.earth")
db.use("khatawas_ns", "khatawas_db")
db.signin({"username": "...", "password": "..."})

runner = MigrationRunner(db)
applied = runner.apply_pending_migrations()
print(f"Applied migrations: {applied}")
```

---

## 4. Rollback Policy & Limitations

- **Append-Only Immutability:** Financial ledger tables (`ledger_event`, `sync_receipt`, `conflict_record`) cannot be rolled back or destructively dropped in production.
- **Additive Migrations:** Production schema changes must always be forward-only additive migrations. If an index or field is retired, it must be removed in a subsequent numbered migration.
- **Development Reset:** The migration runner provides `reset_development_database(confirm=True, environment='development')` for local/testing teardowns. It is strictly disabled in production.

