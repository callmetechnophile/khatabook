# KHATAWAS — Database Schema Specification

**Document Version:** 1.0.0  
**Date:** 2026-11-21  
**Scope:** Local SQLCipher (SQLite) Schema & Cloud SurrealDB Schema  

---

## 1. Local SQLite + SQLCipher Schema

The local database runs on Android using SQLCipher 4.5.4 with AES-256 encryption. All monetary values are represented as 64-bit integer paise ($1 \text{ Rupee} = 100 \text{ paise}$).

### 1.1 Table: `customers`
Stores customer records for the Kirana store.

```sql
CREATE TABLE IF NOT EXISTS customers (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT UNIQUE NOT NULL COLLATE NOCASE,
    phone_suffix TEXT DEFAULT '',
    locality TEXT DEFAULT '',
    created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_customer_name ON customers(name);
```

### 1.2 Table: `transactions`
Stores immutable financial transactions.

```sql
CREATE TABLE IF NOT EXISTS transactions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    event_id TEXT UNIQUE NOT NULL,            -- UUID v4 generated on client
    customer_id INTEGER NOT NULL,
    amount_paise INTEGER NOT NULL,            -- Signed or unsigned 64-bit integer
    type TEXT NOT NULL CHECK(type IN ('CREDIT', 'PAYMENT', 'REVERSAL')),
    timestamp INTEGER NOT NULL,               -- Epoch millis
    reversal_target_id INTEGER DEFAULT NULL,  -- If REVERSAL, points to original transaction id
    note TEXT DEFAULT '',
    FOREIGN KEY(customer_id) REFERENCES customers(id) ON DELETE RESTRICT,
    FOREIGN KEY(reversal_target_id) REFERENCES transactions(id) ON DELETE RESTRICT
);

CREATE INDEX IF NOT EXISTS idx_transaction_event_id ON transactions(event_id);
CREATE INDEX IF NOT EXISTS idx_transaction_customer ON transactions(customer_id);
CREATE INDEX IF NOT EXISTS idx_transaction_timestamp ON transactions(timestamp);
```

### 1.3 Table: `sync_queue`
Manages offline-first replication to the cloud backend.

```sql
CREATE TABLE IF NOT EXISTS sync_queue (
    queue_id INTEGER PRIMARY KEY AUTOINCREMENT,
    event_id TEXT UNIQUE NOT NULL,            -- Matches transactions.event_id
    payload_json TEXT NOT NULL,               -- Serialized JSON representation
    status TEXT NOT NULL CHECK(status IN ('PENDING', 'SYNCING', 'SYNCED', 'FAILED')),
    retry_count INTEGER NOT NULL DEFAULT 0,
    last_attempt_at INTEGER DEFAULT NULL,
    error_message TEXT DEFAULT NULL,
    created_at INTEGER NOT NULL,
    FOREIGN KEY(event_id) REFERENCES transactions(event_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_sync_queue_status ON sync_queue(status);
```

### 1.4 Versioned Migration Path
- **v1 $\to$ v2:** Added `amount_paise INTEGER` migrating old `amount` float to paise.
- **v2 $\to$ v3:** Added `event_id` column to `transactions`, created `sync_queue` table, added `phone_suffix` and `locality` to `customers`, and expanded `type` constraint to include `REVERSAL`.

---

## 2. Cloud SurrealDB Schema

SurrealDB provides permanent document storage, ACID transactional consistency, and temporary graph projections for real-time Kirana analytics.

### 2.1 Table: `shop`
Defines the shopkeeper account.

```surql
DEFINE TABLE shop SCHEMAFULL;

DEFINE FIELD name ON TABLE shop TYPE string;
DEFINE FIELD owner_phone ON TABLE shop TYPE string;
DEFINE FIELD address ON TABLE shop TYPE option<string>;
DEFINE FIELD created_at ON TABLE shop TYPE datetime DEFAULT time::now();

DEFINE INDEX idx_shop_phone ON TABLE shop COLUMNS owner_phone UNIQUE;
```

### 2.2 Table: `customer`
Defines a customer attached to a specific shop.

```surql
DEFINE TABLE customer SCHEMAFULL;

DEFINE FIELD shop_id ON TABLE customer TYPE record<shop>;
DEFINE FIELD name ON TABLE customer TYPE string;
DEFINE FIELD phone_suffix ON TABLE customer TYPE option<string>;
DEFINE FIELD locality ON TABLE customer TYPE option<string>;
DEFINE FIELD balance_paise ON TABLE customer TYPE int DEFAULT 0;
DEFINE FIELD updated_at ON TABLE customer TYPE datetime DEFAULT time::now();
DEFINE FIELD created_at ON TABLE customer TYPE datetime DEFAULT time::now();

DEFINE INDEX idx_customer_shop_name ON TABLE customer COLUMNS shop_id, name;
```

### 2.3 Table: `transaction`
Immutable permanent financial ledger in the cloud.

```surql
DEFINE TABLE transaction SCHEMAFULL;

DEFINE FIELD event_id ON TABLE transaction TYPE string;
DEFINE FIELD shop_id ON TABLE transaction TYPE record<shop>;
DEFINE FIELD customer_id ON TABLE transaction TYPE record<customer>;
DEFINE FIELD amount_paise ON TABLE transaction TYPE int;
DEFINE FIELD type ON TABLE transaction TYPE string ASSERT $value IN ['CREDIT', 'PAYMENT', 'REVERSAL'];
DEFINE FIELD timestamp ON TABLE transaction TYPE datetime;
DEFINE FIELD note ON TABLE transaction TYPE option<string>;
DEFINE FIELD client_created_at ON TABLE transaction TYPE datetime;
DEFINE FIELD server_ingested_at ON TABLE transaction TYPE datetime DEFAULT time::now();

DEFINE INDEX idx_tx_event_id ON TABLE transaction COLUMNS event_id UNIQUE;
DEFINE INDEX idx_tx_customer ON TABLE transaction COLUMNS customer_id;
DEFINE INDEX idx_tx_shop ON TABLE transaction COLUMNS shop_id;
```

### 2.4 Graph Relationships
Graph relations between customers and shops for fast traversal.

```surql
-- Graph Edge: customer -> OWES -> shop
DEFINE TABLE owes SCHEMAFULL TYPE RELATION FROM customer TO shop;
DEFINE FIELD amount_paise ON TABLE owes TYPE int;
DEFINE FIELD last_transaction ON TABLE owes TYPE datetime;

-- Graph Edge: customer -> PAID -> shop
DEFINE TABLE paid SCHEMAFULL TYPE RELATION FROM customer TO shop;
DEFINE FIELD amount_paise ON TABLE paid TYPE int;
DEFINE FIELD last_payment ON TABLE paid TYPE datetime;
```

### 2.5 Temporary Graph Projections (24-Hour Expiry)
Used for fast daily rollups, recent transaction feeds, and Kirana summary dashboards.

```surql
DEFINE TABLE temporary_event SCHEMAFULL;

DEFINE FIELD shop_id ON TABLE temporary_event TYPE record<shop>;
DEFINE FIELD event_id ON TABLE temporary_event TYPE string;
DEFINE FIELD customer_name ON TABLE temporary_event TYPE string;
DEFINE FIELD amount_paise ON TABLE temporary_event TYPE int;
DEFINE FIELD type ON TABLE temporary_event TYPE string;
DEFINE FIELD created_at ON TABLE temporary_event TYPE datetime DEFAULT time::now();
DEFINE FIELD expires_at ON TABLE temporary_event TYPE datetime;

DEFINE INDEX idx_temp_expiry ON TABLE temporary_event COLUMNS expires_at;

-- Graph relation for temporary projections
DEFINE TABLE recent_activity SCHEMAFULL TYPE RELATION FROM shop TO customer;
DEFINE FIELD event_id ON TABLE recent_activity TYPE string;
DEFINE FIELD expires_at ON TABLE recent_activity TYPE datetime;
```

### 2.6 Expiry Worker Logic (SurrealQL Query)
The background worker executes this query periodically (e.g. every hour or via cron):

```surql
-- Delete expired temporary projection events
DELETE temporary_event WHERE expires_at < time::now();

-- Delete expired recent activity edges
DELETE recent_activity WHERE expires_at < time::now();
```
*Note: Under no circumstances does the expiry worker delete records from `transaction`, `customer`, or `shop`.*
