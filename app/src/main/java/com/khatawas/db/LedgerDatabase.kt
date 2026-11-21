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
    val createdAt: Long
)

/**
 * Encrypted ledger database using SQLCipher.
 * Stores Kirana customer records, ledger transactions with AES-256 encryption,
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
        const val DATABASE_VERSION = 3

        const val TABLE_CUSTOMERS = "customers"
        const val TABLE_TRANSACTIONS = "transactions"
        const val TABLE_SYNC_QUEUE = "sync_queue"

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

        const val COLUMN_QUEUE_ID = "queue_id"
        const val COLUMN_PAYLOAD_JSON = "payload_json"
        const val COLUMN_STATUS = "status"
        const val COLUMN_RETRY_COUNT = "retry_count"
        const val COLUMN_LAST_ATTEMPT_AT = "last_attempt_at"
        const val COLUMN_ERROR_MESSAGE = "error_message"

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

        // Table 2: transactions (storing 64-bit integer paise and stable UUID event_id)
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
                FOREIGN KEY($COLUMN_EVENT_ID) REFERENCES $TABLE_TRANSACTIONS($COLUMN_EVENT_ID) ON DELETE CASCADE
            );
            """.trimIndent()
        )

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_customer_name ON $TABLE_CUSTOMERS($COLUMN_NAME);")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tx_event_id ON $TABLE_TRANSACTIONS($COLUMN_EVENT_ID);")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_transaction_customer ON $TABLE_TRANSACTIONS($COLUMN_CUSTOMER_ID);")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sync_queue_status ON $TABLE_SYNC_QUEUE($COLUMN_STATUS);")
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
    }

    /**
     * Insert a transaction into the encrypted database atomically and enqueue for sync.
     */
    fun insertTransaction(transaction: Transaction): Long {
        val db = getWritableEncryptedDatabase()
        db.beginTransaction()
        try {
            val customerId = getOrCreateCustomer(db, transaction.name)
            if (customerId < 0) return -1L

            val timestamp = System.currentTimeMillis() + (transaction.dateOffset * MILLIS_PER_DAY)
            val eventId = if (transaction.eventId.isNotBlank()) transaction.eventId else UUID.randomUUID().toString()

            val values = ContentValues().apply {
                put(COLUMN_EVENT_ID, eventId)
                put(COLUMN_CUSTOMER_ID, customerId)
                put(COLUMN_AMOUNT_PAISE, transaction.amountPaise)
                put(COLUMN_TYPE, transaction.type)
                put(COLUMN_TIMESTAMP, timestamp)
                put(COLUMN_NOTE, transaction.note)
            }

            val rowId = db.insert(TABLE_TRANSACTIONS, null, values)
            if (rowId == -1L) return -1L

            // Enqueue into sync_queue
            val syncPayload = JSONObject().apply {
                put("event_id", eventId)
                put("customer_name", transaction.name)
                put("amount_paise", transaction.amountPaise)
                put("type", transaction.type)
                put("timestamp", timestamp)
                put("note", transaction.note)
            }

            val queueValues = ContentValues().apply {
                put(COLUMN_EVENT_ID, eventId)
                put(COLUMN_PAYLOAD_JSON, syncPayload.toString())
                put(COLUMN_STATUS, "PENDING")
                put(COLUMN_RETRY_COUNT, 0)
                put(COLUMN_CREATED_AT, System.currentTimeMillis())
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
     */
    fun insertTransactionsAtomic(transactions: List<Transaction>): Boolean {
        if (transactions.isEmpty()) return true
        val db = getWritableEncryptedDatabase()
        db.beginTransaction()
        try {
            for (t in transactions) {
                val customerId = getOrCreateCustomer(db, t.name)
                if (customerId < 0) return false

                val timestamp = System.currentTimeMillis() + (t.dateOffset * MILLIS_PER_DAY)
                val eventId = if (t.eventId.isNotBlank()) t.eventId else UUID.randomUUID().toString()

                val values = ContentValues().apply {
                    put(COLUMN_EVENT_ID, eventId)
                    put(COLUMN_CUSTOMER_ID, customerId)
                    put(COLUMN_AMOUNT_PAISE, t.amountPaise)
                    put(COLUMN_TYPE, t.type)
                    put(COLUMN_TIMESTAMP, timestamp)
                    put(COLUMN_NOTE, t.note)
                }

                val rowId = db.insert(TABLE_TRANSACTIONS, null, values)
                if (rowId == -1L) return false

                val syncPayload = JSONObject().apply {
                    put("event_id", eventId)
                    put("customer_name", t.name)
                    put("amount_paise", t.amountPaise)
                    put("type", t.type)
                    put("timestamp", timestamp)
                    put("note", t.note)
                }

                val queueValues = ContentValues().apply {
                    put(COLUMN_EVENT_ID, eventId)
                    put(COLUMN_PAYLOAD_JSON, syncPayload.toString())
                    put(COLUMN_STATUS, "PENDING")
                    put(COLUMN_RETRY_COUNT, 0)
                    put(COLUMN_CREATED_AT, System.currentTimeMillis())
                }
                db.insert(TABLE_SYNC_QUEUE, null, queueValues)
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
                    WHEN t.$COLUMN_TYPE = 'PAYMENT' THEN -t.$COLUMN_AMOUNT_PAISE 
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
     * Reverse a transaction for a customer (e.g. "Ramesh ka cancel karo").
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

            val values = ContentValues().apply {
                put(COLUMN_EVENT_ID, eventId)
                put(COLUMN_CUSTOMER_ID, customerId)
                put(COLUMN_AMOUNT_PAISE, targetAmount)
                put(COLUMN_TYPE, "REVERSAL")
                put(COLUMN_TIMESTAMP, timestamp)
                put(COLUMN_REVERSAL_TARGET_ID, targetId)
                put(COLUMN_NOTE, "Reversal of transaction #$targetId")
            }
            val rowId = db.insert(TABLE_TRANSACTIONS, null, values)
            if (rowId == -1L) return false

            val syncPayload = JSONObject().apply {
                put("event_id", eventId)
                put("customer_name", customerName)
                put("amount_paise", targetAmount)
                put("type", "REVERSAL")
                put("timestamp", timestamp)
                put("reversal_target_id", targetId)
            }
            val queueValues = ContentValues().apply {
                put(COLUMN_EVENT_ID, eventId)
                put(COLUMN_PAYLOAD_JSON, syncPayload.toString())
                put(COLUMN_STATUS, "PENDING")
                put(COLUMN_RETRY_COUNT, 0)
                put(COLUMN_CREATED_AT, timestamp)
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
                    WHEN t.$COLUMN_TYPE = 'PAYMENT' THEN -t.$COLUMN_AMOUNT_PAISE 
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
     * Correct the most recent transaction amount (for "Nahi, 430" corrections).
     */
    fun updateLastTransactionAmount(newAmountPaise: Long): Boolean {
        val db = getWritableEncryptedDatabase()
        db.beginTransaction()
        try {
            val cursor = db.rawQuery(
                "SELECT $COLUMN_ID, $COLUMN_EVENT_ID FROM $TABLE_TRANSACTIONS ORDER BY $COLUMN_ID DESC LIMIT 1",
                null
            )
            var lastId = -1L
            var eventId = ""
            cursor.use {
                if (it.moveToFirst()) {
                    lastId = it.getLong(0)
                    eventId = it.getString(1) ?: ""
                }
            }

            if (lastId == -1L) return false

            val values = ContentValues().apply {
                put(COLUMN_AMOUNT_PAISE, newAmountPaise)
            }
            val rows = db.update(TABLE_TRANSACTIONS, values, "$COLUMN_ID = ?", arrayOf(lastId.toString()))
            if (rows > 0) {
                // Also update pending sync queue item if still present
                db.execSQL(
                    "UPDATE $TABLE_SYNC_QUEUE SET $COLUMN_STATUS = 'PENDING' WHERE $COLUMN_EVENT_ID = ?",
                    arrayOf(eventId)
                )
                db.setTransactionSuccessful()
                return true
            }
            return false
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
            SELECT $COLUMN_QUEUE_ID, $COLUMN_EVENT_ID, $COLUMN_PAYLOAD_JSON, $COLUMN_STATUS, $COLUMN_RETRY_COUNT, $COLUMN_CREATED_AT
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
                        createdAt = it.getLong(5)
                    )
                )
            }
        }
        return items
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
     * Purge all customer and transaction data from the local database.
     * Complies with Privacy & Testing Data Purge requirements (R14).
     */
    fun purgeAllData(): Boolean {
        val db = getWritableEncryptedDatabase()
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM $TABLE_SYNC_QUEUE;")
            db.execSQL("DELETE FROM $TABLE_TRANSACTIONS;")
            db.execSQL("DELETE FROM $TABLE_CUSTOMERS;")
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
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
