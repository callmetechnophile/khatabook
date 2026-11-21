# KHATAWAS — Database Architecture & Financial Security Audit Report

**Audit Date:** 2026-11-21  
**Lead Auditor:** Database & Cryptographic Security Architect  
**Engine:** SQLCipher (v4.5.4) AES-256 encrypted SQLite database  
**Target File:** `khatawas.db` stored in internal storage (`/data/data/com.khatawas/databases/`)  

---

## 1. Cryptographic Key Management Audit

### 1.1 Key Generation & Storage (`DatabaseKeyManager.kt`)
- **Master Key**: Generated using `MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()`.
- **Passphrase Storage**: Stored inside `khatawas_secure_vault.xml` encrypted via `EncryptedSharedPreferences` (`AES256_SIV` keys, `AES256_GCM` values).
- **Key Entropy**: 256 bits (32 bytes) cryptographically generated via `java.security.SecureRandom`.
- **Passphrase Ingestion**:
  ```kotlin
  val passphrase = DatabaseKeyManager.getOrCreatePassphrase(context)
  return getWritableDatabase(passphrase)
  ```
  Verified: The passphrase is used as a `ByteArray` directly to unlock the encrypted database without plaintext logging or exposure.
- **Reopening Verification**: On subsequent app launches, the existing encrypted key is read from `EncryptedSharedPreferences`, successfully unlocking the existing `khatawas.db`.

---

## 2. Schema & Financial Model Audit (`LedgerDatabase.kt`)

### 2.1 Table Structure
```sql
CREATE TABLE customers (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT UNIQUE NOT NULL
);

CREATE TABLE transactions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    customer_id INTEGER,
    amount INTEGER,
    type TEXT,
    timestamp INTEGER,
    FOREIGN KEY(customer_id) REFERENCES customers(id)
);
```

### 2.2 Financial & Relational Vulnerabilities

1. **Denomination Unit — Rupees vs. Integer Paise (Defect)**:
   - `amount` is stored as `INTEGER` representing whole rupees.
   - For complete financial correctness (e.g. transactions involving paise such as ₹140.50), the database must store amounts strictly in **integer paise** ($1\text{ Rupee} = 100\text{ Paise}$).
2. **Missing Balance & Calculation Logic (Defect)**:
   - There is NO method to compute customer balances:
     $$\text{Outstanding} = \sum(\text{CREDIT}) - \sum(\text{DEBIT})$$
   - Any query regarding balance requires manual SQL execution outside the current database helper class.
3. **Customer Collisions & Ambiguity**:
   - `name TEXT UNIQUE NOT NULL` forces every customer name to be a unique string.
   - If two distinct customers share the same first name (e.g., two distinct individuals named *"Ramesh"*), the database lumps all their credit and debit transactions into the single existing record with zero disambiguation (no phone number, nickname, or unique alias).
4. **Missing Ledger Aggregation Queries**:
   - No queries exist for:
     - Total daily credit issued: `SELECT SUM(amount) FROM transactions WHERE type='CREDIT' AND timestamp >= ?`
     - Total daily payments collected: `SELECT SUM(amount) FROM transactions WHERE type='DEBIT' AND timestamp >= ?`
     - Customer with highest outstanding debt: `SELECT customer_id, ... ORDER BY balance DESC LIMIT 1`
5. **No Transaction Rollback / Amendment Support**:
   - The table does not support updates, deletions, or status flags (`PENDING`, `COMMITTED`, `VOID`). A mistaken entry cannot be corrected via voice.
