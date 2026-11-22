/*
 * Copyright (c) 2026 KHATAWAS Project
 * Simplified Technical English (ASD STE-100) compliant.
 * Local encrypted SQLite database using SQLCipher (AES-256).
 * Enforces foreign-key constraints, integer paise precision, atomic transactions, offline sync queue, and reversals.
 */

package com.khatawas.db

import android.content.ContentValues
import android.content.Context
import android.util.Log
import com.khatawas.logic.Transaction
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SQLiteOpenHelper
import org.json.JSONObject
import java.util.UUID

/**
 * Encapsulates an offline sync queue item for replication to the cloud.
 */
data class SyncQueueItem(
    val queueId: Long,
    val eventId: String,
    val payloadJson: String,
    val status: String,
    val retryCount: Int,
    val createdAt: Long,
    val eventHash: String = "",
    val previousEventHash: String = ""
)

/**
 * Read-only transaction record for UI display.
 */
data class TransactionEntity(
    val id: Long,
    val eventId: String,
    val customerName: String,
    val amountPaise: Long,
    val type: String,
    val timestamp: Long,
    val note: String = "",
    val reversalTargetId: Long? = null,
    val eventHash: String = "",
    val previousEventHash: String = ""
)

/**
 * Encrypted chat conversation record.
 */
data class ChatMessageEntity(
    val id: Long = 0,
    val conversationId: String,
    val messageId: String,
    val role: String, // 'user', 'assistant', 'system'
    val content: String,
    val modality: String, // 'TEXT' or 'AUDIO'
    val timestamp: Long,
    val sourceMetadata: String = "",
    val retrievedEventIds: String = "",
    val isConflict: Boolean = false
)

/**
 * Encrypted ledger database using SQLCipher.
 * Stores Kirana customer records, ledger transactions with AES-256 encryption,
 * SHA-256 cryptographic event hashing and hash-chaining,
 * and maintains an offline-first synchronization queue.
 */
class LedgerDatabase(private val context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {

    companion object {
        private const val TAG = "KHATAWAS_DB"
        const val DATABASE_NAME = "khatawas.db"
        const val DATABASE_VERSION = 5

        const val TABLE_CUSTOMERS = "customers"
        const val TABLE_TRANSACTIONS = "transactions"
        const val TABLE_SYNC_QUEUE = "sync_queue"
        const val TABLE_CONVERSATIONS = "conversation_history"

        const val COLUMN_ID = "id"
        const val COLUMN_NAME = "name"
        const val COLUMN_PHONE_SUFFIX = "phone_suffix"
        const val COLUMN_LOCALITY = "locality"
        const val COLUMN_CREATED_AT = "created_at"

        const val COLUMN_CUSTOMER_ID = "customer_id"
        const val COLUMN_EVENT_ID = "event_id"
        const val COLUMN_AMOUNT_PAISE = "amount_paise"
        const val COLUMN_TYPE = "type"
        const val COLUMN_TIMESTAMP = "timestamp"
        const val COLUMN_REVERSAL_TARGET_ID = "reversal_target_id"
        const val COLUMN_NOTE = "note"
        const val COLUMN_EVENT_HASH = "event_hash"
        const val COLUMN_PREVIOUS_EVENT_HASH = "previous_event_hash"
        const val COLUMN_SCHEMA_VERSION = "schema_version"

        const val COLUMN_QUEUE_ID = "queue_id"
        const val COLUMN_PAYLOAD_JSON = "payload_json"
        const val COLUMN_STATUS = "status"
        const val COLUMN_RETRY_COUNT = "retry_count"
        const val COLUMN_LAST_ATTEMPT_AT = "last_attempt_at"
        const val COLUMN_ERROR_MESSAGE = "error_message"

        // Conversation history columns
        const val COLUMN_CONV_ID = "conversation_id"
        const val COLUMN_MESSAGE_ID = "message_id"
        const val COLUMN_ROLE = "role"
        const val COLUMN_CONTENT = "content"
        const val COLUMN_MODALITY = "modality"
        const val COLUMN_CONV_TIMESTAMP = "timestamp"
        const val COLUMN_SOURCE_METADATA = "source_metadata"
        const val COLUMN_RETRIEVED_EVENT_IDS = "retrieved_event_ids"
        const val COLUMN_IS_CONFLICT = "is_conflict"

        private const val MILLIS_PER_DAY = 86_400_000L
    }

    init {
        // Load SQLCipher native binaries
        SQLiteDatabase.loadLibs(context)
    }

    /**
     * Get writable encrypted database using Keystore-derived passphrase.
     */
    fun getWritableEncryptedDatabase(): SQLiteDatabase {
        val passphrase = DatabaseKeyManager.getOrCreatePassphrase(context)
        val db = getWritableDatabase(passphrase)
        db.execSQL("PRAGMA foreign_keys = ON;")
        return db
    }

    /**
     * Get readable encrypted database using Keystore-derived passphrase.
     */
    fun getReadableEncryptedDatabase(): SQLiteDatabase {
        val passphrase = DatabaseKeyManager.getOrCreatePassphrase(context)
        val db = getReadableDatabase(passphrase)
        db.execSQL("PRAGMA foreign_keys = ON;")
        return db
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.execSQL("PRAGMA foreign_keys = ON;")
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("PRAGMA foreign_keys = ON;")

        // Table 1: customers
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_CUSTOMERS (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_NAME TEXT UNIQUE NOT NULL COLLATE NOCASE,
                $COLUMN_PHONE_SUFFIX TEXT DEFAULT '',
                $COLUMN_LOCALITY TEXT DEFAULT '',
                $COLUMN_CREATED_AT INTEGER NOT NULL DEFAULT 0
            );
            """.trimIndent()
        )

        // Table 2: transactions (storing 64-bit integer paise, stable UUID event_id, and SHA-256 hash chaining)
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_TRANSACTIONS (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_EVENT_ID TEXT UNIQUE NOT NULL,
                $COLUMN_CUSTOMER_ID INTEGER NOT NULL,
                $COLUMN_AMOUNT_PAISE INTEGER NOT NULL,
                $COLUMN_TYPE TEXT NOT NULL CHECK($COLUMN_TYPE IN ('CREDIT', 'PAYMENT', 'REVERSAL')),
                $COLUMN_TIMESTAMP INTEGER NOT NULL,
                $COLUMN_REVERSAL_TARGET_ID INTEGER DEFAULT NULL,
                $COLUMN_NOTE TEXT DEFAULT '',
                $COLUMN_EVENT_HASH TEXT NOT NULL,
                $COLUMN_PREVIOUS_EVENT_HASH TEXT NOT NULL,
                $COLUMN_SCHEMA_VERSION INTEGER NOT NULL DEFAULT 1,
                FOREIGN KEY($COLUMN_CUSTOMER_ID) REFERENCES $TABLE_CUSTOMERS($COLUMN_ID) ON DELETE RESTRICT,
                FOREIGN KEY($COLUMN_REVERSAL_TARGET_ID) REFERENCES $TABLE_TRANSACTIONS($COLUMN_ID) ON DELETE RESTRICT
            );
            """.trimIndent()
        )

        // Table 3: sync_queue for offline-first replication
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_SYNC_QUEUE (
                $COLUMN_QUEUE_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_EVENT_ID TEXT UNIQUE NOT NULL,
                $COLUMN_PAYLOAD_JSON TEXT NOT NULL,
                $COLUMN_STATUS TEXT NOT NULL CHECK($COLUMN_STATUS IN ('PENDING', 'SYNCING', 'SYNCED', 'FAILED')),
                $COLUMN_RETRY_COUNT INTEGER NOT NULL DEFAULT 0,
                $COLUMN_LAST_ATTEMPT_AT INTEGER DEFAULT NULL,
                $COLUMN_ERROR_MESSAGE TEXT DEFAULT NULL,
                $COLUMN_CREATED_AT INTEGER NOT NULL,
                $COLUMN_EVENT_HASH TEXT NOT NULL,
                $COLUMN_PREVIOUS_EVENT_HASH TEXT NOT NULL,
                $COLUMN_SCHEMA_VERSION INTEGER NOT NULL DEFAULT 1,
                FOREIGN KEY($COLUMN_EVENT_ID) REFERENCES $TABLE_TRANSACTIONS($COLUMN_EVENT_ID) ON DELETE CASCADE
            );
            """.trimIndent()
        )

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_customer_name ON $TABLE_CUSTOMERS($COLUMN_NAME);")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tx_event_id ON $TABLE_TRANSACTIONS($COLUMN_EVENT_ID);")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_transaction_customer ON $TABLE_TRANSACTIONS($COLUMN_CUSTOMER_ID);")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sync_queue_status ON $TABLE_SYNC_QUEUE($COLUMN_STATUS);")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tx_event_hash ON $TABLE_TRANSACTIONS($COLUMN_EVENT_HASH);")

        // Table 4: conversation_history for chatbot persistence
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_CONVERSATIONS (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_CONV_ID TEXT NOT NULL,
                $COLUMN_MESSAGE_ID TEXT UNIQUE NOT NULL,
                $COLUMN_ROLE TEXT NOT NULL CHECK($COLUMN_ROLE IN ('user', 'assistant', 'system')),
                $COLUMN_CONTENT TEXT NOT NULL,
                $COLUMN_MODALITY TEXT NOT NULL CHECK($COLUMN_MODALITY IN ('TEXT', 'AUDIO')),
                $COLUMN_CONV_TIMESTAMP INTEGER NOT NULL,
                $COLUMN_SOURCE_METADATA TEXT DEFAULT '',
                $COLUMN_RETRIEVED_EVENT_IDS TEXT DEFAULT '',
                $COLUMN_IS_CONFLICT INTEGER DEFAULT 0
            );
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_conv_id ON $TABLE_CONVERSATIONS($COLUMN_CONV_ID);")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_conv_timestamp ON $TABLE_CONVERSATIONS($COLUMN_CONV_TIMESTAMP);")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Log.i(TAG, "Upgrading database from version $oldVersion to $newVersion")
        if (oldVersion < 2) {
            try {
                db.beginTransaction()
                db.execSQL("ALTER TABLE $TABLE_TRANSACTIONS ADD COLUMN $COLUMN_AMOUNT_PAISE INTEGER DEFAULT 0;")
                db.execSQL("UPDATE $TABLE_TRANSACTIONS SET $COLUMN_AMOUNT_PAISE = amount * 100 WHERE $COLUMN_AMOUNT_PAISE = 0;")
                db.setTransactionSuccessful()
            } catch (e: Exception) {
                Log.e(TAG, "Migration to v2 failed: ${e.message}", e)
            } finally {
                db.endTransaction()
            }
        }
        if (oldVersion < 3) {
            try {
                db.beginTransaction()
                // Add v3 columns to customers
                db.execSQL("ALTER TABLE $TABLE_CUSTOMERS ADD COLUMN $COLUMN_PHONE_SUFFIX TEXT DEFAULT '';")
                db.execSQL("ALTER TABLE $TABLE_CUSTOMERS ADD COLUMN $COLUMN_LOCALITY TEXT DEFAULT '';")
                db.execSQL("ALTER TABLE $TABLE_CUSTOMERS ADD COLUMN $COLUMN_CREATED_AT INTEGER DEFAULT 0;")

                // Add v3 columns to transactions
                db.execSQL("ALTER TABLE $TABLE_TRANSACTIONS ADD COLUMN $COLUMN_EVENT_ID TEXT;")
                db.execSQL("ALTER TABLE $TABLE_TRANSACTIONS ADD COLUMN $COLUMN_REVERSAL_TARGET_ID INTEGER DEFAULT NULL;")
                db.execSQL("ALTER TABLE $TABLE_TRANSACTIONS ADD COLUMN $COLUMN_NOTE TEXT DEFAULT '';")

                // Backfill event_id for existing rows
                val cursor = db.rawQuery("SELECT $COLUMN_ID FROM $TABLE_TRANSACTIONS WHERE $COLUMN_EVENT_ID IS NULL", null)
                cursor.use {
                    while (it.moveToNext()) {
                        val rowId = it.getLong(0)
                        val randomUuid = UUID.randomUUID().toString()
                        db.execSQL("UPDATE $TABLE_TRANSACTIONS SET $COLUMN_EVENT_ID = ? WHERE $COLUMN_ID = ?", arrayOf(randomUuid, rowId.toString()))
                    }
                }

                // Create sync_queue table
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS $TABLE_SYNC_QUEUE (
                        $COLUMN_QUEUE_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                        $COLUMN_EVENT_ID TEXT UNIQUE NOT NULL,
                        $COLUMN_PAYLOAD_JSON TEXT NOT NULL,
                        $COLUMN_STATUS TEXT NOT NULL CHECK($COLUMN_STATUS IN ('PENDING', 'SYNCING', 'SYNCED', 'FAILED')),
                        $COLUMN_RETRY_COUNT INTEGER NOT NULL DEFAULT 0,
                        $COLUMN_LAST_ATTEMPT_AT INTEGER DEFAULT NULL,
                        $COLUMN_ERROR_MESSAGE TEXT DEFAULT NULL,
                        $COLUMN_CREATED_AT INTEGER NOT NULL
                    );
                    """.trimIndent()
                )

                db.execSQL("CREATE INDEX IF NOT EXISTS idx_tx_event_id ON $TABLE_TRANSACTIONS($COLUMN_EVENT_ID);")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_sync_queue_status ON $TABLE_SYNC_QUEUE($COLUMN_STATUS);")

                db.setTransactionSuccessful()
            } catch (e: Exception) {
                Log.e(TAG, "Migration to v3 failed: ${e.message}", e)
            } finally {
                db.endTransaction()
            }
        }
        if (oldVersion < 4) {
            try {
                db.beginTransaction()
                db.execSQL("ALTER TABLE $TABLE_TRANSACTIONS ADD COLUMN $COLUMN_EVENT_HASH TEXT DEFAULT '';")
                db.execSQL("ALTER TABLE $TABLE_TRANSACTIONS ADD COLUMN $COLUMN_PREVIOUS_EVENT_HASH TEXT DEFAULT '';")
                db.execSQL("ALTER TABLE $TABLE_TRANSACTIONS ADD COLUMN $COLUMN_SCHEMA_VERSION INTEGER DEFAULT 1;")

                db.execSQL("ALTER TABLE $TABLE_SYNC_QUEUE ADD COLUMN $COLUMN_EVENT_HASH TEXT DEFAULT '';")
                db.execSQL("ALTER TABLE $TABLE_SYNC_QUEUE ADD COLUMN $COLUMN_PREVIOUS_EVENT_HASH TEXT DEFAULT '';")
                db.execSQL("ALTER TABLE $TABLE_SYNC_QUEUE ADD COLUMN $COLUMN_SCHEMA_VERSION INTEGER DEFAULT 1;")

                // Backfill hash chain in sequential ID order
                var prevHash = EventIntegrity.GENESIS_HASH
                val query = """
                    SELECT t.$COLUMN_ID, t.$COLUMN_EVENT_ID, c.$COLUMN_NAME, t.$COLUMN_AMOUNT_PAISE, t.$COLUMN_TYPE, t.$COLUMN_TIMESTAMP, t.$COLUMN_NOTE
                    FROM $TABLE_TRANSACTIONS t
                    INNER JOIN $TABLE_CUSTOMERS c ON t.$COLUMN_CUSTOMER_ID = c.$COLUMN_ID
                    ORDER BY t.$COLUMN_ID ASC
                """.trimIndent()

                val cursor = db.rawQuery(query, null)
                cursor.use {
                    while (it.moveToNext()) {
                        val rowId = it.getLong(0)
                        val eventId = it.getString(1) ?: UUID.randomUUID().toString()
                        val custName = it.getString(2) ?: ""
                        val amountPaise = it.getLong(3)
                        val txType = it.getString(4) ?: "CREDIT"
                        val timestamp = it.getLong(5)
                        val note = it.getString(6) ?: ""

                        val (canonicalJson, currentHash) = EventIntegrity.computeEventDigest(
                            eventId = eventId,
                            customerName = custName,
                            amountPaise = amountPaise,
                            type = txType,
                            timestamp = timestamp,
                            previousEventHash = prevHash,
                            note = note,
                            schemaVersion = EventIntegrity.CURRENT_SCHEMA_VERSION
                        )

                        db.execSQL(
                            "UPDATE $TABLE_TRANSACTIONS SET $COLUMN_EVENT_HASH = ?, $COLUMN_PREVIOUS_EVENT_HASH = ?, $COLUMN_SCHEMA_VERSION = 1 WHERE $COLUMN_ID = ?",
                            arrayOf(currentHash, prevHash, rowId.toString())
                        )
                        db.execSQL(
                            "UPDATE $TABLE_SYNC_QUEUE SET $COLUMN_EVENT_HASH = ?, $COLUMN_PREVIOUS_EVENT_HASH = ?, $COLUMN_SCHEMA_VERSION = 1, $COLUMN_PAYLOAD_JSON = ? WHERE $COLUMN_EVENT_ID = ?",
                            arrayOf(currentHash, prevHash, canonicalJson, eventId)
                        )

                        prevHash = currentHash
                    }
                }

                db.execSQL("CREATE INDEX IF NOT EXISTS idx_tx_event_hash ON $TABLE_TRANSACTIONS($COLUMN_EVENT_HASH);")
                db.setTransactionSuccessful()
            } catch (e: Exception) {
                Log.e(TAG, "Migration to v4 failed: ${e.message}", e)
            } finally {
                db.endTransaction()
            }
        }
        if (oldVersion < 5) {
            try {
                db.beginTransaction()
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS $TABLE_CONVERSATIONS (
                        $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                        $COLUMN_CONV_ID TEXT NOT NULL,
                        $COLUMN_MESSAGE_ID TEXT UNIQUE NOT NULL,
                        $COLUMN_ROLE TEXT NOT NULL CHECK($COLUMN_ROLE IN ('user', 'assistant', 'system')),
                        $COLUMN_CONTENT TEXT NOT NULL,
                        $COLUMN_MODALITY TEXT NOT NULL CHECK($COLUMN_MODALITY IN ('TEXT', 'AUDIO')),
                        $COLUMN_CONV_TIMESTAMP INTEGER NOT NULL,
                        $COLUMN_SOURCE_METADATA TEXT DEFAULT '',
                        $COLUMN_RETRIEVED_EVENT_IDS TEXT DEFAULT '',
                        $COLUMN_IS_CONFLICT INTEGER DEFAULT 0
                    );
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_conv_id ON $TABLE_CONVERSATIONS($COLUMN_CONV_ID);")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_conv_timestamp ON $TABLE_CONVERSATIONS($COLUMN_CONV_TIMESTAMP);")
                db.setTransactionSuccessful()
            } catch (e: Exception) {
                Log.e(TAG, "Migration to v5 failed: ${e.message}", e)
            } finally {
                db.endTransaction()
            }
        }
    }

    /**
     * Retrieve the latest event hash from the ledger to maintain the hash chain.
     * Returns GENESIS_HASH if no previous events exist.
     */
    fun getLatestEventHash(db: SQLiteDatabase): String {
        val cursor = db.rawQuery(
            "SELECT $COLUMN_EVENT_HASH FROM $TABLE_TRANSACTIONS WHERE $COLUMN_EVENT_HASH IS NOT NULL AND $COLUMN_EVENT_HASH != '' ORDER BY $COLUMN_ID DESC LIMIT 1",
            null
        )
        cursor.use {
            if (it.moveToFirst()) {
                val h = it.getString(0)
                if (!h.isNullOrBlank()) return h
            }
        }
        return EventIntegrity.GENESIS_HASH
    }

    /**
     * Insert a transaction into the encrypted database atomically and enqueue for sync.
     * Calculates SHA-256 event digest linked to the previous event hash.
     */
    fun insertTransaction(transaction: Transaction): Long {
        val db = getWritableEncryptedDatabase()
        db.beginTransaction()
        try {
            val customerId = getOrCreateCustomer(db, transaction.name)
            if (customerId < 0) return -1L

            val timestamp = System.currentTimeMillis() + (transaction.dateOffset * MILLIS_PER_DAY)
            val eventId = if (transaction.eventId.isNotBlank()) transaction.eventId else UUID.randomUUID().toString()
            val prevHash = getLatestEventHash(db)

            val (canonicalJson, currentHash) = EventIntegrity.computeEventDigest(
                eventId = eventId,
                customerName = transaction.name,
                amountPaise = transaction.amountPaise,
                type = transaction.type,
                timestamp = timestamp,
                previousEventHash = prevHash,
                note = transaction.note,
                schemaVersion = EventIntegrity.CURRENT_SCHEMA_VERSION
            )

            val values = ContentValues().apply {
                put(COLUMN_EVENT_ID, eventId)
                put(COLUMN_CUSTOMER_ID, customerId)
                put(COLUMN_AMOUNT_PAISE, transaction.amountPaise)
                put(COLUMN_TYPE, transaction.type)
                put(COLUMN_TIMESTAMP, timestamp)
                put(COLUMN_NOTE, transaction.note)
                put(COLUMN_EVENT_HASH, currentHash)
                put(COLUMN_PREVIOUS_EVENT_HASH, prevHash)
                put(COLUMN_SCHEMA_VERSION, EventIntegrity.CURRENT_SCHEMA_VERSION)
            }

            val rowId = db.insert(TABLE_TRANSACTIONS, null, values)
            if (rowId == -1L) return -1L

            // Enqueue into sync_queue with canonical JSON and hash metadata
            val queueValues = ContentValues().apply {
                put(COLUMN_EVENT_ID, eventId)
                put(COLUMN_PAYLOAD_JSON, canonicalJson)
                put(COLUMN_STATUS, "PENDING")
                put(COLUMN_RETRY_COUNT, 0)
                put(COLUMN_CREATED_AT, System.currentTimeMillis())
                put(COLUMN_EVENT_HASH, currentHash)
                put(COLUMN_PREVIOUS_EVENT_HASH, prevHash)
                put(COLUMN_SCHEMA_VERSION, EventIntegrity.CURRENT_SCHEMA_VERSION)
            }
            db.insert(TABLE_SYNC_QUEUE, null, queueValues)

            db.setTransactionSuccessful()
            return rowId
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Insert multiple transactions atomically (e.g. compound sentences).
     * Rolls back entire batch if any insert fails.
     * Maintains strict hash chain across all transactions in the batch.
     */
    fun insertTransactionsAtomic(transactions: List<Transaction>): Boolean {
        if (transactions.isEmpty()) return true
        val db = getWritableEncryptedDatabase()
        db.beginTransaction()
        try {
            var prevHash = getLatestEventHash(db)
            for (t in transactions) {
                val customerId = getOrCreateCustomer(db, t.name)
                if (customerId < 0) return false

                val timestamp = System.currentTimeMillis() + (t.dateOffset * MILLIS_PER_DAY)
                val eventId = if (t.eventId.isNotBlank()) t.eventId else UUID.randomUUID().toString()

                val (canonicalJson, currentHash) = EventIntegrity.computeEventDigest(
                    eventId = eventId,
                    customerName = t.name,
                    amountPaise = t.amountPaise,
                    type = t.type,
                    timestamp = timestamp,
                    previousEventHash = prevHash,
                    note = t.note,
                    schemaVersion = EventIntegrity.CURRENT_SCHEMA_VERSION
                )

                val values = ContentValues().apply {
                    put(COLUMN_EVENT_ID, eventId)
                    put(COLUMN_CUSTOMER_ID, customerId)
                    put(COLUMN_AMOUNT_PAISE, t.amountPaise)
                    put(COLUMN_TYPE, t.type)
                    put(COLUMN_TIMESTAMP, timestamp)
                    put(COLUMN_NOTE, t.note)
                    put(COLUMN_EVENT_HASH, currentHash)
                    put(COLUMN_PREVIOUS_EVENT_HASH, prevHash)
                    put(COLUMN_SCHEMA_VERSION, EventIntegrity.CURRENT_SCHEMA_VERSION)
                }

                val rowId = db.insert(TABLE_TRANSACTIONS, null, values)
                if (rowId == -1L) return false

                val queueValues = ContentValues().apply {
                    put(COLUMN_EVENT_ID, eventId)
                    put(COLUMN_PAYLOAD_JSON, canonicalJson)
                    put(COLUMN_STATUS, "PENDING")
                    put(COLUMN_RETRY_COUNT, 0)
                    put(COLUMN_CREATED_AT, System.currentTimeMillis())
                    put(COLUMN_EVENT_HASH, currentHash)
                    put(COLUMN_PREVIOUS_EVENT_HASH, prevHash)
                    put(COLUMN_SCHEMA_VERSION, EventIntegrity.CURRENT_SCHEMA_VERSION)
                }
                db.insert(TABLE_SYNC_QUEUE, null, queueValues)

                prevHash = currentHash
            }
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Calculate outstanding customer balance in integer paise.
     * Net balance = Total Credit (udhaar) - Total Payment (jama) - Reversals.
     */
    fun getCustomerBalancePaise(customerName: String): Long {
        val db = getReadableEncryptedDatabase()
        val query = """
            SELECT 
                SUM(CASE 
                    WHEN t.$COLUMN_TYPE = 'CREDIT' THEN t.$COLUMN_AMOUNT_PAISE 
                    WHEN t.$COLUMN_TYPE IN ('PAYMENT', 'DEBIT') THEN -t.$COLUMN_AMOUNT_PAISE 
                    WHEN t.$COLUMN_TYPE = 'REVERSAL' THEN -t.$COLUMN_AMOUNT_PAISE
                    ELSE 0 
                END)
            FROM $TABLE_TRANSACTIONS t
            INNER JOIN $TABLE_CUSTOMERS c ON t.$COLUMN_CUSTOMER_ID = c.$COLUMN_ID
            WHERE c.$COLUMN_NAME = ?
        """.trimIndent()

        val cursor = db.rawQuery(query, arrayOf(customerName.trim()))
        cursor.use {
            if (it.moveToFirst() && !it.isNull(0)) {
                return it.getLong(0)
            }
        }
        return 0L
    }

    /**
     * Reverse a transaction for a customer (e.g. "<Customer> ka cancel karo").
     * Inserts an explicit REVERSAL transaction preserving the audit trail.
     */
    fun reverseTransaction(customerName: String, amountPaise: Long = 0L): Boolean {
        val db = getWritableEncryptedDatabase()
        db.beginTransaction()
        try {
            val customerQuery = "SELECT $COLUMN_ID FROM $TABLE_CUSTOMERS WHERE $COLUMN_NAME = ? LIMIT 1"
            val customerId = db.rawQuery(customerQuery, arrayOf(customerName.trim())).use {
                if (it.moveToFirst()) it.getLong(0) else -1L
            }
            if (customerId < 0) return false

            // Find latest active transaction for this customer
            val txQuery = if (amountPaise > 0L) {
                "SELECT $COLUMN_ID, $COLUMN_AMOUNT_PAISE FROM $TABLE_TRANSACTIONS WHERE $COLUMN_CUSTOMER_ID = ? AND $COLUMN_AMOUNT_PAISE = ? AND $COLUMN_TYPE != 'REVERSAL' ORDER BY $COLUMN_ID DESC LIMIT 1"
            } else {
                "SELECT $COLUMN_ID, $COLUMN_AMOUNT_PAISE FROM $TABLE_TRANSACTIONS WHERE $COLUMN_CUSTOMER_ID = ? AND $COLUMN_TYPE != 'REVERSAL' ORDER BY $COLUMN_ID DESC LIMIT 1"
            }

            val targetCursor = if (amountPaise > 0L) {
                db.rawQuery(txQuery, arrayOf(customerId.toString(), amountPaise.toString()))
            } else {
                db.rawQuery(txQuery, arrayOf(customerId.toString()))
            }

            var targetId = -1L
            var targetAmount = 0L
            targetCursor.use {
                if (it.moveToFirst()) {
                    targetId = it.getLong(0)
                    targetAmount = it.getLong(1)
                }
            }

            if (targetId == -1L) return false

            val eventId = UUID.randomUUID().toString()
            val timestamp = System.currentTimeMillis()
            val prevHash = getLatestEventHash(db)

            val (canonicalJson, eventHash) = EventIntegrity.computeEventDigest(
                eventId = eventId,
                customerName = customerName,
                amountPaise = targetAmount,
                type = "REVERSAL",
                timestamp = timestamp,
                previousEventHash = prevHash,
                note = "Reversal of transaction #$targetId",
                schemaVersion = EventIntegrity.CURRENT_SCHEMA_VERSION
            )

            val values = ContentValues().apply {
                put(COLUMN_EVENT_ID, eventId)
                put(COLUMN_CUSTOMER_ID, customerId)
                put(COLUMN_AMOUNT_PAISE, targetAmount)
                put(COLUMN_TYPE, "REVERSAL")
                put(COLUMN_TIMESTAMP, timestamp)
                put(COLUMN_REVERSAL_TARGET_ID, targetId)
                put(COLUMN_NOTE, "Reversal of transaction #$targetId")
                put(COLUMN_EVENT_HASH, eventHash)
                put(COLUMN_PREVIOUS_EVENT_HASH, prevHash)
                put(COLUMN_SCHEMA_VERSION, EventIntegrity.CURRENT_SCHEMA_VERSION)
            }
            val rowId = db.insert(TABLE_TRANSACTIONS, null, values)
            if (rowId == -1L) return false

            val queueValues = ContentValues().apply {
                put(COLUMN_EVENT_ID, eventId)
                put(COLUMN_PAYLOAD_JSON, canonicalJson)
                put(COLUMN_STATUS, "PENDING")
                put(COLUMN_RETRY_COUNT, 0)
                put(COLUMN_CREATED_AT, timestamp)
                put(COLUMN_EVENT_HASH, eventHash)
                put(COLUMN_PREVIOUS_EVENT_HASH, prevHash)
                put(COLUMN_SCHEMA_VERSION, EventIntegrity.CURRENT_SCHEMA_VERSION)
            }
            db.insert(TABLE_SYNC_QUEUE, null, queueValues)

            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Daily total credit given (offsetDays: 0 = today, -1 = yesterday).
     */
    fun getDailyCreditPaise(offsetDays: Int = 0): Long {
        val startOfDay = getStartOfDayMillis(offsetDays)
        val endOfDay = startOfDay + MILLIS_PER_DAY

        val db = getReadableEncryptedDatabase()
        val query = """
            SELECT SUM($COLUMN_AMOUNT_PAISE) 
            FROM $TABLE_TRANSACTIONS 
            WHERE $COLUMN_TYPE = 'CREDIT' AND $COLUMN_TIMESTAMP >= ? AND $COLUMN_TIMESTAMP < ?
        """.trimIndent()

        val cursor = db.rawQuery(query, arrayOf(startOfDay.toString(), endOfDay.toString()))
        cursor.use {
            if (it.moveToFirst() && !it.isNull(0)) {
                return it.getLong(0)
            }
        }
        return 0L
    }

    /**
     * Daily total payments collected (offsetDays: 0 = today, -1 = yesterday).
     */
    fun getDailyPaymentPaise(offsetDays: Int = 0): Long {
        val startOfDay = getStartOfDayMillis(offsetDays)
        val endOfDay = startOfDay + MILLIS_PER_DAY

        val db = getReadableEncryptedDatabase()
        val query = """
            SELECT SUM($COLUMN_AMOUNT_PAISE) 
            FROM $TABLE_TRANSACTIONS 
            WHERE $COLUMN_TYPE = 'PAYMENT' AND $COLUMN_TIMESTAMP >= ? AND $COLUMN_TIMESTAMP < ?
        """.trimIndent()

        val cursor = db.rawQuery(query, arrayOf(startOfDay.toString(), endOfDay.toString()))
        cursor.use {
            if (it.moveToFirst() && !it.isNull(0)) {
                return it.getLong(0)
            }
        }
        return 0L
    }

    /**
     * Retrieve top customers with highest outstanding credit.
     */
    fun getTopDebtors(limit: Int = 5): List<Pair<String, Long>> {
        val db = getReadableEncryptedDatabase()
        val query = """
            SELECT 
                c.$COLUMN_NAME,
                SUM(CASE 
                    WHEN t.$COLUMN_TYPE = 'CREDIT' THEN t.$COLUMN_AMOUNT_PAISE 
                    WHEN t.$COLUMN_TYPE IN ('PAYMENT', 'DEBIT') THEN -t.$COLUMN_AMOUNT_PAISE 
                    WHEN t.$COLUMN_TYPE = 'REVERSAL' THEN -t.$COLUMN_AMOUNT_PAISE
                    ELSE 0 
                END) as balance
            FROM $TABLE_TRANSACTIONS t
            INNER JOIN $TABLE_CUSTOMERS c ON t.$COLUMN_CUSTOMER_ID = c.$COLUMN_ID
            GROUP BY c.$COLUMN_ID
            HAVING balance > 0
            ORDER BY balance DESC
            LIMIT ?
        """.trimIndent()

        val results = ArrayList<Pair<String, Long>>()
        val cursor = db.rawQuery(query, arrayOf(limit.toString()))
        cursor.use {
            while (it.moveToNext()) {
                val name = it.getString(0)
                val balance = it.getLong(1)
                results.add(Pair(name, balance))
            }
        }
        return results
    }

    /**
     * Correct the most recent transaction amount using append-only immutable transactions.
     * Never modifies existing transaction records in place.
     * Records an explicit reversal of the prior entry, followed by an append of the corrected entry.
     */
    fun updateLastTransactionAmount(newAmountPaise: Long): Boolean {
        val db = getWritableEncryptedDatabase()
        db.beginTransaction()
        try {
            val cursor = db.rawQuery(
                """
                SELECT t.$COLUMN_ID, t.$COLUMN_EVENT_ID, t.$COLUMN_CUSTOMER_ID, c.$COLUMN_NAME, 
                       t.$COLUMN_AMOUNT_PAISE, t.$COLUMN_TYPE, t.$COLUMN_NOTE
                FROM $TABLE_TRANSACTIONS t
                INNER JOIN $TABLE_CUSTOMERS c ON t.$COLUMN_CUSTOMER_ID = c.$COLUMN_ID
                WHERE t.$COLUMN_TYPE != 'REVERSAL'
                ORDER BY t.$COLUMN_ID DESC LIMIT 1
                """.trimIndent(),
                null
            )

            var lastId = -1L
            var oldEventId = ""
            var customerId = -1L
            var customerName = ""
            var oldAmountPaise = 0L
            var oldType = ""
            var oldNote = ""

            cursor.use {
                if (it.moveToFirst()) {
                    lastId = it.getLong(0)
                    oldEventId = it.getString(1) ?: ""
                    customerId = it.getLong(2)
                    customerName = it.getString(3) ?: ""
                    oldAmountPaise = it.getLong(4)
                    oldType = it.getString(5) ?: "CREDIT"
                    oldNote = it.getString(6) ?: ""
                }
            }

            if (lastId == -1L || customerId < 0) return false

            val now = System.currentTimeMillis()
            var prevHash = getLatestEventHash(db)

            // Step 1: Append explicit reversal of the incorrect record
            val reversalEventId = UUID.randomUUID().toString()
            val (reversalJson, reversalHash) = EventIntegrity.computeEventDigest(
                eventId = reversalEventId,
                customerName = customerName,
                amountPaise = oldAmountPaise,
                type = "REVERSAL",
                timestamp = now,
                previousEventHash = prevHash,
                note = "Correction reversal for transaction #$lastId ($oldEventId)",
                schemaVersion = EventIntegrity.CURRENT_SCHEMA_VERSION
            )

            val revValues = ContentValues().apply {
                put(COLUMN_EVENT_ID, reversalEventId)
                put(COLUMN_CUSTOMER_ID, customerId)
                put(COLUMN_AMOUNT_PAISE, oldAmountPaise)
                put(COLUMN_TYPE, "REVERSAL")
                put(COLUMN_TIMESTAMP, now)
                put(COLUMN_REVERSAL_TARGET_ID, lastId)
                put(COLUMN_NOTE, "Correction reversal for transaction #$lastId ($oldEventId)")
                put(COLUMN_EVENT_HASH, reversalHash)
                put(COLUMN_PREVIOUS_EVENT_HASH, prevHash)
                put(COLUMN_SCHEMA_VERSION, EventIntegrity.CURRENT_SCHEMA_VERSION)
            }
            if (db.insert(TABLE_TRANSACTIONS, null, revValues) == -1L) return false

            val revQueueValues = ContentValues().apply {
                put(COLUMN_EVENT_ID, reversalEventId)
                put(COLUMN_PAYLOAD_JSON, reversalJson)
                put(COLUMN_STATUS, "PENDING")
                put(COLUMN_RETRY_COUNT, 0)
                put(COLUMN_CREATED_AT, now)
                put(COLUMN_EVENT_HASH, reversalHash)
                put(COLUMN_PREVIOUS_EVENT_HASH, prevHash)
                put(COLUMN_SCHEMA_VERSION, EventIntegrity.CURRENT_SCHEMA_VERSION)
            }
            db.insert(TABLE_SYNC_QUEUE, null, revQueueValues)

            // Step 2: Append the corrected transaction
            prevHash = reversalHash
            val correctedEventId = UUID.randomUUID().toString()
            val correctedNote = if (oldNote.isNotBlank()) "$oldNote (corrected)" else "Corrected amount from #$lastId"

            val (correctedJson, correctedHash) = EventIntegrity.computeEventDigest(
                eventId = correctedEventId,
                customerName = customerName,
                amountPaise = newAmountPaise,
                type = oldType,
                timestamp = now + 1,
                previousEventHash = prevHash,
                note = correctedNote,
                schemaVersion = EventIntegrity.CURRENT_SCHEMA_VERSION
            )

            val corrValues = ContentValues().apply {
                put(COLUMN_EVENT_ID, correctedEventId)
                put(COLUMN_CUSTOMER_ID, customerId)
                put(COLUMN_AMOUNT_PAISE, newAmountPaise)
                put(COLUMN_TYPE, oldType)
                put(COLUMN_TIMESTAMP, now + 1)
                put(COLUMN_NOTE, correctedNote)
                put(COLUMN_EVENT_HASH, correctedHash)
                put(COLUMN_PREVIOUS_EVENT_HASH, prevHash)
                put(COLUMN_SCHEMA_VERSION, EventIntegrity.CURRENT_SCHEMA_VERSION)
            }
            if (db.insert(TABLE_TRANSACTIONS, null, corrValues) == -1L) return false

            val corrQueueValues = ContentValues().apply {
                put(COLUMN_EVENT_ID, correctedEventId)
                put(COLUMN_PAYLOAD_JSON, correctedJson)
                put(COLUMN_STATUS, "PENDING")
                put(COLUMN_RETRY_COUNT, 0)
                put(COLUMN_CREATED_AT, now + 1)
                put(COLUMN_EVENT_HASH, correctedHash)
                put(COLUMN_PREVIOUS_EVENT_HASH, prevHash)
                put(COLUMN_SCHEMA_VERSION, EventIntegrity.CURRENT_SCHEMA_VERSION)
            }
            db.insert(TABLE_SYNC_QUEUE, null, corrQueueValues)

            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Retrieve pending items from the sync queue for upload to the cloud backend.
     */
    fun getPendingSyncItems(limit: Int = 50): List<SyncQueueItem> {
        val db = getReadableEncryptedDatabase()
        val query = """
            SELECT $COLUMN_QUEUE_ID, $COLUMN_EVENT_ID, $COLUMN_PAYLOAD_JSON, $COLUMN_STATUS, $COLUMN_RETRY_COUNT, $COLUMN_CREATED_AT,
                   $COLUMN_EVENT_HASH, $COLUMN_PREVIOUS_EVENT_HASH
            FROM $TABLE_SYNC_QUEUE
            WHERE $COLUMN_STATUS IN ('PENDING', 'FAILED')
            ORDER BY $COLUMN_QUEUE_ID ASC
            LIMIT ?
        """.trimIndent()

        val items = ArrayList<SyncQueueItem>()
        val cursor = db.rawQuery(query, arrayOf(limit.toString()))
        cursor.use {
            while (it.moveToNext()) {
                items.add(
                    SyncQueueItem(
                        queueId = it.getLong(0),
                        eventId = it.getString(1),
                        payloadJson = it.getString(2),
                        status = it.getString(3),
                        retryCount = it.getInt(4),
                        createdAt = it.getLong(5),
                        eventHash = it.getString(6) ?: "",
                        previousEventHash = it.getString(7) ?: ""
                    )
                )
            }
        }
        return items
    }

    /**
     * Return count of unsynced items in sync_queue.
     */
    fun getUnsyncedCount(): Int {
        val db = getReadableEncryptedDatabase()
        val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_SYNC_QUEUE WHERE $COLUMN_STATUS IN ('PENDING', 'FAILED')", null)
        cursor.use {
            if (it.moveToFirst()) return it.getInt(0)
        }
        return 0
    }

    /**
     * Verifies the cryptographic integrity of the entire local ledger hash chain.
     */
    fun verifyLedgerIntegrity(): Pair<Boolean, String> {
        val db = getReadableEncryptedDatabase()
        val query = """
            SELECT t.$COLUMN_ID, t.$COLUMN_EVENT_ID, c.$COLUMN_NAME, t.$COLUMN_AMOUNT_PAISE, 
                   t.$COLUMN_TYPE, t.$COLUMN_TIMESTAMP, t.$COLUMN_NOTE, t.$COLUMN_EVENT_HASH, t.$COLUMN_PREVIOUS_EVENT_HASH, t.$COLUMN_SCHEMA_VERSION
            FROM $TABLE_TRANSACTIONS t
            INNER JOIN $TABLE_CUSTOMERS c ON t.$COLUMN_CUSTOMER_ID = c.$COLUMN_ID
            ORDER BY t.$COLUMN_ID ASC
        """.trimIndent()

        var expectedPrevHash = EventIntegrity.GENESIS_HASH
        val cursor = db.rawQuery(query, null)
        cursor.use {
            var count = 0
            while (it.moveToNext()) {
                count++
                val rowId = it.getLong(0)
                val eventId = it.getString(1) ?: ""
                val custName = it.getString(2) ?: ""
                val amountPaise = it.getLong(3)
                val type = it.getString(4) ?: ""
                val timestamp = it.getLong(5)
                val note = it.getString(6) ?: ""
                val storedHash = it.getString(7) ?: ""
                val storedPrevHash = it.getString(8) ?: ""
                val schemaVer = it.getInt(9)

                if (storedPrevHash != expectedPrevHash) {
                    return Pair(false, "Hash chain link broken at row $rowId (event $eventId): expected prev $expectedPrevHash, got $storedPrevHash")
                }

                val verified = EventIntegrity.verifyDigest(
                    eventId = eventId,
                    customerName = custName,
                    amountPaise = amountPaise,
                    type = type,
                    timestamp = timestamp,
                    previousEventHash = storedPrevHash,
                    note = note,
                    expectedDigest = storedHash,
                    schemaVersion = schemaVer
                )

                if (!verified) {
                    return Pair(false, "Event payload digest mismatch at row $rowId (event $eventId)")
                }

                expectedPrevHash = storedHash
            }
            return Pair(true, "All $count ledger records verified successfully with valid SHA-256 hash chain.")
        }
    }

    /**
     * Mark an event in the sync queue as successfully synced.
     */
    fun markEventSynced(eventId: String): Boolean {
        val db = getWritableEncryptedDatabase()
        val values = ContentValues().apply {
            put(COLUMN_STATUS, "SYNCED")
            put(COLUMN_LAST_ATTEMPT_AT, System.currentTimeMillis())
        }
        return db.update(TABLE_SYNC_QUEUE, values, "$COLUMN_EVENT_ID = ?", arrayOf(eventId)) > 0
    }

    /**
     * Mark an event in the sync queue as failed, incrementing retry count.
     */
    fun markEventFailed(eventId: String, errorMessage: String): Boolean {
        val db = getWritableEncryptedDatabase()
        db.execSQL(
            """
            UPDATE $TABLE_SYNC_QUEUE 
            SET $COLUMN_STATUS = 'FAILED', 
                $COLUMN_RETRY_COUNT = $COLUMN_RETRY_COUNT + 1,
                $COLUMN_LAST_ATTEMPT_AT = ?,
                $COLUMN_ERROR_MESSAGE = ?
            WHERE $COLUMN_EVENT_ID = ?
            """.trimIndent(),
            arrayOf(System.currentTimeMillis().toString(), errorMessage, eventId)
        )
        return true
    }

    /**
     * Retrieve recent transactions across all customers for UI history display.
     */
    fun getRecentTransactions(limit: Int = 100): List<TransactionEntity> {
        val db = getReadableEncryptedDatabase()
        val query = """
            SELECT 
                t.$COLUMN_ID, t.$COLUMN_EVENT_ID, c.$COLUMN_NAME, t.$COLUMN_AMOUNT_PAISE, 
                t.$COLUMN_TYPE, t.$COLUMN_TIMESTAMP, t.$COLUMN_NOTE, t.$COLUMN_REVERSAL_TARGET_ID,
                t.$COLUMN_EVENT_HASH, t.$COLUMN_PREVIOUS_EVENT_HASH
            FROM $TABLE_TRANSACTIONS t
            INNER JOIN $TABLE_CUSTOMERS c ON t.$COLUMN_CUSTOMER_ID = c.$COLUMN_ID
            ORDER BY t.$COLUMN_TIMESTAMP DESC, t.$COLUMN_ID DESC
            LIMIT ?
        """.trimIndent()

        val list = ArrayList<TransactionEntity>()
        val cursor = db.rawQuery(query, arrayOf(limit.toString()))
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    TransactionEntity(
                        id = it.getLong(0),
                        eventId = it.getString(1) ?: "",
                        customerName = it.getString(2) ?: "",
                        amountPaise = it.getLong(3),
                        type = it.getString(4) ?: "CREDIT",
                        timestamp = it.getLong(5),
                        note = it.getString(6) ?: "",
                        reversalTargetId = if (it.isNull(7)) null else it.getLong(7),
                        eventHash = it.getString(8) ?: "",
                        previousEventHash = it.getString(9) ?: ""
                    )
                )
            }
        }
        return list
    }

    /**
     * Retrieve transaction history for a specific customer.
     */
    fun getTransactionsForCustomer(customerName: String, limit: Int = 100): List<TransactionEntity> {
        val db = getReadableEncryptedDatabase()
        val query = """
            SELECT 
                t.$COLUMN_ID, t.$COLUMN_EVENT_ID, c.$COLUMN_NAME, t.$COLUMN_AMOUNT_PAISE, 
                t.$COLUMN_TYPE, t.$COLUMN_TIMESTAMP, t.$COLUMN_NOTE, t.$COLUMN_REVERSAL_TARGET_ID,
                t.$COLUMN_EVENT_HASH, t.$COLUMN_PREVIOUS_EVENT_HASH
            FROM $TABLE_TRANSACTIONS t
            INNER JOIN $TABLE_CUSTOMERS c ON t.$COLUMN_CUSTOMER_ID = c.$COLUMN_ID
            WHERE c.$COLUMN_NAME = ? COLLATE NOCASE
            ORDER BY t.$COLUMN_TIMESTAMP DESC, t.$COLUMN_ID DESC
            LIMIT ?
        """.trimIndent()

        val list = ArrayList<TransactionEntity>()
        val cursor = db.rawQuery(query, arrayOf(customerName.trim(), limit.toString()))
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    TransactionEntity(
                        id = it.getLong(0),
                        eventId = it.getString(1) ?: "",
                        customerName = it.getString(2) ?: "",
                        amountPaise = it.getLong(3),
                        type = it.getString(4) ?: "CREDIT",
                        timestamp = it.getLong(5),
                        note = it.getString(6) ?: "",
                        reversalTargetId = if (it.isNull(7)) null else it.getLong(7),
                        eventHash = it.getString(8) ?: "",
                        previousEventHash = it.getString(9) ?: ""
                    )
                )
            }
        }
        return list
    }

    /**
     * Purge all customer and transaction data from the local database.
     * Complies with Privacy & Testing Data Purge requirements (R14).
     */
    fun purgeAllData(): Boolean {
        val db = getWritableEncryptedDatabase()
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM $TABLE_CONVERSATIONS;")
            db.execSQL("DELETE FROM $TABLE_SYNC_QUEUE;")
            db.execSQL("DELETE FROM $TABLE_TRANSACTIONS;")
            db.execSQL("DELETE FROM $TABLE_CUSTOMERS;")
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Insert a chat message into encrypted conversation history.
     */
    fun insertChatMessage(message: ChatMessageEntity): Long {
        val db = getWritableEncryptedDatabase()
        val values = ContentValues().apply {
            put(COLUMN_CONV_ID, message.conversationId)
            put(COLUMN_MESSAGE_ID, message.messageId)
            put(COLUMN_ROLE, message.role)
            put(COLUMN_CONTENT, message.content)
            put(COLUMN_MODALITY, message.modality)
            put(COLUMN_CONV_TIMESTAMP, message.timestamp)
            put(COLUMN_SOURCE_METADATA, message.sourceMetadata)
            put(COLUMN_RETRIEVED_EVENT_IDS, message.retrievedEventIds)
            put(COLUMN_IS_CONFLICT, if (message.isConflict) 1 else 0)
        }
        return db.insert(TABLE_CONVERSATIONS, null, values)
    }

    /**
     * Retrieve messages for a conversation ordered chronologically.
     */
    fun getConversationMessages(conversationId: String, limit: Int = 100): List<ChatMessageEntity> {
        val db = getReadableEncryptedDatabase()
        val query = """
            SELECT 
                $COLUMN_ID, $COLUMN_CONV_ID, $COLUMN_MESSAGE_ID, $COLUMN_ROLE,
                $COLUMN_CONTENT, $COLUMN_MODALITY, $COLUMN_CONV_TIMESTAMP,
                $COLUMN_SOURCE_METADATA, $COLUMN_RETRIEVED_EVENT_IDS, $COLUMN_IS_CONFLICT
            FROM $TABLE_CONVERSATIONS
            WHERE $COLUMN_CONV_ID = ?
            ORDER BY $COLUMN_CONV_TIMESTAMP ASC, $COLUMN_ID ASC
            LIMIT ?
        """.trimIndent()

        val list = ArrayList<ChatMessageEntity>()
        val cursor = db.rawQuery(query, arrayOf(conversationId, limit.toString()))
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    ChatMessageEntity(
                        id = it.getLong(0),
                        conversationId = it.getString(1),
                        messageId = it.getString(2),
                        role = it.getString(3),
                        content = it.getString(4),
                        modality = it.getString(5),
                        timestamp = it.getLong(6),
                        sourceMetadata = it.getString(7) ?: "",
                        retrievedEventIds = it.getString(8) ?: "",
                        isConflict = it.getInt(9) == 1
                    )
                )
            }
        }
        return list
    }

    /**
     * Clear all messages for a conversation.
     */
    fun clearConversation(conversationId: String): Boolean {
        val db = getWritableEncryptedDatabase()
        val rows = db.delete(TABLE_CONVERSATIONS, "$COLUMN_CONV_ID = ?", arrayOf(conversationId))
        return rows >= 0
    }

    /**
     * Retrieve all customer names in alphabetical order.
     */
    fun getAllCustomerNames(): List<String> {
        val db = getReadableEncryptedDatabase()
        val query = "SELECT $COLUMN_NAME FROM $TABLE_CUSTOMERS ORDER BY $COLUMN_NAME COLLATE NOCASE ASC"
        val list = ArrayList<String>()
        val cursor = db.rawQuery(query, null)
        cursor.use {
            while (it.moveToNext()) {
                list.add(it.getString(0))
            }
        }
        return list
    }

    /**
     * Calculate total outstanding balance (in integer paise) across all customers.
     */
    fun getTotalOutstandingBalancePaise(): Long {
        val db = getReadableEncryptedDatabase()
        val query = """
            SELECT 
                SUM(CASE 
                    WHEN $COLUMN_TYPE = 'CREDIT' THEN $COLUMN_AMOUNT_PAISE 
                    WHEN $COLUMN_TYPE IN ('PAYMENT', 'DEBIT') THEN -$COLUMN_AMOUNT_PAISE 
                    WHEN $COLUMN_TYPE = 'REVERSAL' THEN -$COLUMN_AMOUNT_PAISE 
                    ELSE 0 
                END)
            FROM $TABLE_TRANSACTIONS
        """.trimIndent()
        val cursor = db.rawQuery(query, null)
        cursor.use {
            if (it.moveToFirst() && !it.isNull(0)) {
                return it.getLong(0)
            }
        }
        return 0L
    }

    /**
     * Retrieve transactions within a specific timestamp range.
     */
    fun getTransactionsByDateRange(startTimestamp: Long, endTimestamp: Long): List<TransactionEntity> {
        val db = getReadableEncryptedDatabase()
        val query = """
            SELECT 
                t.$COLUMN_ID, t.$COLUMN_EVENT_ID, c.$COLUMN_NAME, t.$COLUMN_AMOUNT_PAISE, 
                t.$COLUMN_TYPE, t.$COLUMN_TIMESTAMP, t.$COLUMN_NOTE, t.$COLUMN_REVERSAL_TARGET_ID,
                t.$COLUMN_EVENT_HASH, t.$COLUMN_PREVIOUS_EVENT_HASH
            FROM $TABLE_TRANSACTIONS t
            INNER JOIN $TABLE_CUSTOMERS c ON t.$COLUMN_CUSTOMER_ID = c.$COLUMN_ID
            WHERE t.$COLUMN_TIMESTAMP >= ? AND t.$COLUMN_TIMESTAMP <= ?
            ORDER BY t.$COLUMN_TIMESTAMP DESC, t.$COLUMN_ID DESC
        """.trimIndent()

        val list = ArrayList<TransactionEntity>()
        val cursor = db.rawQuery(query, arrayOf(startTimestamp.toString(), endTimestamp.toString()))
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    TransactionEntity(
                        id = it.getLong(0),
                        eventId = it.getString(1) ?: "",
                        customerName = it.getString(2) ?: "",
                        amountPaise = it.getLong(3),
                        type = it.getString(4) ?: "CREDIT",
                        timestamp = it.getLong(5),
                        note = it.getString(6) ?: "",
                        reversalTargetId = if (it.isNull(7)) null else it.getLong(7),
                        eventHash = it.getString(8) ?: "",
                        previousEventHash = it.getString(9) ?: ""
                    )
                )
            }
        }
        return list
    }

    /**
     * Retrieve transactions filtered by category/type.
     */
    fun getTransactionsByCategory(category: String, limit: Int = 100): List<TransactionEntity> {
        val db = getReadableEncryptedDatabase()
        // Map category filter to database column or note
        val list = ArrayList<TransactionEntity>()
        val query = if (category.equals("STUCK", ignoreCase = true) || category.equals("ILLEGAL", ignoreCase = true)) {
            """
                SELECT 
                    t.$COLUMN_ID, t.$COLUMN_EVENT_ID, c.$COLUMN_NAME, t.$COLUMN_AMOUNT_PAISE, 
                    t.$COLUMN_TYPE, t.$COLUMN_TIMESTAMP, t.$COLUMN_NOTE, t.$COLUMN_REVERSAL_TARGET_ID,
                    t.$COLUMN_EVENT_HASH, t.$COLUMN_PREVIOUS_EVENT_HASH
                FROM $TABLE_TRANSACTIONS t
                INNER JOIN $TABLE_CUSTOMERS c ON t.$COLUMN_CUSTOMER_ID = c.$COLUMN_ID
                WHERE t.$COLUMN_NOTE LIKE ? COLLATE NOCASE
                ORDER BY t.$COLUMN_TIMESTAMP DESC, t.$COLUMN_ID DESC
                LIMIT ?
            """.trimIndent()
        } else {
            val dbType = if (category.equals("DEBIT", ignoreCase = true)) "PAYMENT" else "CREDIT"
            """
                SELECT 
                    t.$COLUMN_ID, t.$COLUMN_EVENT_ID, c.$COLUMN_NAME, t.$COLUMN_AMOUNT_PAISE, 
                    t.$COLUMN_TYPE, t.$COLUMN_TIMESTAMP, t.$COLUMN_NOTE, t.$COLUMN_REVERSAL_TARGET_ID,
                    t.$COLUMN_EVENT_HASH, t.$COLUMN_PREVIOUS_EVENT_HASH
                FROM $TABLE_TRANSACTIONS t
                INNER JOIN $TABLE_CUSTOMERS c ON t.$COLUMN_CUSTOMER_ID = c.$COLUMN_ID
                WHERE t.$COLUMN_TYPE = '$dbType'
                ORDER BY t.$COLUMN_TIMESTAMP DESC, t.$COLUMN_ID DESC
                LIMIT ?
            """.trimIndent()
        }

        val param = if (category.equals("STUCK", ignoreCase = true) || category.equals("ILLEGAL", ignoreCase = true)) {
            arrayOf("%$category%", limit.toString())
        } else {
            arrayOf(limit.toString())
        }

        val cursor = db.rawQuery(query, param)
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    TransactionEntity(
                        id = it.getLong(0),
                        eventId = it.getString(1) ?: "",
                        customerName = it.getString(2) ?: "",
                        amountPaise = it.getLong(3),
                        type = it.getString(4) ?: "CREDIT",
                        timestamp = it.getLong(5),
                        note = it.getString(6) ?: "",
                        reversalTargetId = if (it.isNull(7)) null else it.getLong(7),
                        eventHash = it.getString(8) ?: "",
                        previousEventHash = it.getString(9) ?: ""
                    )
                )
            }
        }
        return list
    }

    /**
     * Retrieve the most recent payment transaction for a customer.
     */
    fun getLastPaymentForCustomer(customerName: String): TransactionEntity? {
        val db = getReadableEncryptedDatabase()
        val query = """
            SELECT 
                t.$COLUMN_ID, t.$COLUMN_EVENT_ID, c.$COLUMN_NAME, t.$COLUMN_AMOUNT_PAISE, 
                t.$COLUMN_TYPE, t.$COLUMN_TIMESTAMP, t.$COLUMN_NOTE, t.$COLUMN_REVERSAL_TARGET_ID,
                t.$COLUMN_EVENT_HASH, t.$COLUMN_PREVIOUS_EVENT_HASH
            FROM $TABLE_TRANSACTIONS t
            INNER JOIN $TABLE_CUSTOMERS c ON t.$COLUMN_CUSTOMER_ID = c.$COLUMN_ID
            WHERE c.$COLUMN_NAME = ? COLLATE NOCASE AND t.$COLUMN_TYPE = 'PAYMENT'
            ORDER BY t.$COLUMN_TIMESTAMP DESC, t.$COLUMN_ID DESC
            LIMIT 1
        """.trimIndent()

        val cursor = db.rawQuery(query, arrayOf(customerName.trim()))
        cursor.use {
            if (it.moveToFirst()) {
                return TransactionEntity(
                    id = it.getLong(0),
                    eventId = it.getString(1) ?: "",
                    customerName = it.getString(2) ?: "",
                    amountPaise = it.getLong(3),
                    type = it.getString(4) ?: "PAYMENT",
                    timestamp = it.getLong(5),
                    note = it.getString(6) ?: "",
                    reversalTargetId = if (it.isNull(7)) null else it.getLong(7),
                    eventHash = it.getString(8) ?: "",
                    previousEventHash = it.getString(9) ?: ""
                )
            }
        }
        return null
    }

    private fun getOrCreateCustomer(db: SQLiteDatabase, customerName: String): Long {
        val nameTrimmed = customerName.trim()
        val query = "SELECT $COLUMN_ID FROM $TABLE_CUSTOMERS WHERE $COLUMN_NAME = ? LIMIT 1"
        val cursor = db.rawQuery(query, arrayOf(nameTrimmed))

        cursor.use {
            if (it.moveToFirst()) {
                return it.getLong(0)
            }
        }

        val values = ContentValues().apply {
            put(COLUMN_NAME, nameTrimmed)
            put(COLUMN_CREATED_AT, System.currentTimeMillis())
        }
        return db.insert(TABLE_CUSTOMERS, null, values)
    }

    private fun getStartOfDayMillis(offsetDays: Int): Long {
        val now = System.currentTimeMillis()
        val dayIndex = (now / MILLIS_PER_DAY) + offsetDays
        return dayIndex * MILLIS_PER_DAY
    }
}
