/*
 * Copyright (c) 2026 KHATAWAS Project
 * Simplified Technical English (ASD STE-100) compliant.
 * Local encrypted SQLite database using SQLCipher (AES-256).
 * Enforces foreign-key constraints, integer paise precision, atomic transactions, and rollback safety.
 */

package com.khatawas.db

import android.content.ContentValues
import android.content.Context
import android.util.Log
import com.khatawas.logic.Transaction
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SQLiteOpenHelper

/**
 * Encrypted ledger database using SQLCipher.
 * Stores Kirana customer records and ledger transactions with AES-256 encryption.
 * Does not transmit data to external cloud services.
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
        const val DATABASE_VERSION = 2

        const val TABLE_CUSTOMERS = "customers"
        const val TABLE_TRANSACTIONS = "transactions"

        const val COLUMN_ID = "id"
        const val COLUMN_NAME = "name"

        const val COLUMN_CUSTOMER_ID = "customer_id"
        const val COLUMN_AMOUNT_PAISE = "amount_paise"
        const val COLUMN_TYPE = "type"
        const val COLUMN_TIMESTAMP = "timestamp"

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
                $COLUMN_NAME TEXT UNIQUE NOT NULL COLLATE NOCASE
            );
            """.trimIndent()
        )

        // Table 2: transactions (storing 64-bit integer paise)
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_TRANSACTIONS (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_CUSTOMER_ID INTEGER NOT NULL,
                $COLUMN_AMOUNT_PAISE INTEGER NOT NULL,
                $COLUMN_TYPE TEXT NOT NULL,
                $COLUMN_TIMESTAMP INTEGER NOT NULL,
                FOREIGN KEY($COLUMN_CUSTOMER_ID) REFERENCES $TABLE_CUSTOMERS($COLUMN_ID) ON DELETE RESTRICT
            );
            """.trimIndent()
        )

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_customer_name ON $TABLE_CUSTOMERS($COLUMN_NAME);")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_transaction_customer ON $TABLE_TRANSACTIONS($COLUMN_CUSTOMER_ID);")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Log.i(TAG, "Upgrading database from version $oldVersion to $newVersion")
        if (oldVersion < 2) {
            // Version 1 had 'amount' in rupees; migrate to 'amount_paise' (amount * 100)
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
    }

    /**
     * Insert a transaction into the encrypted database atomically.
     * Checks if the customer exists. If not, inserts the customer.
     * Computes the timestamp from current time plus the date offset.
     * Returns the database row ID of the inserted transaction, or -1 on error.
     */
    fun insertTransaction(transaction: Transaction): Long {
        val db = getWritableEncryptedDatabase()
        db.beginTransaction()
        try {
            val customerId = getOrCreateCustomer(db, transaction.name)
            if (customerId < 0) {
                return -1L
            }

            val timestamp = System.currentTimeMillis() + (transaction.dateOffset * MILLIS_PER_DAY)

            val values = ContentValues().apply {
                put(COLUMN_CUSTOMER_ID, customerId)
                put(COLUMN_AMOUNT_PAISE, transaction.amountPaise)
                put(COLUMN_TYPE, transaction.type)
                put(COLUMN_TIMESTAMP, timestamp)
            }

            val rowId = db.insert(TABLE_TRANSACTIONS, null, values)
            if (rowId != -1L) {
                db.setTransactionSuccessful()
            }
            return rowId
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Insert multiple transactions atomically (e.g. for compound sentences).
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
                val values = ContentValues().apply {
                    put(COLUMN_CUSTOMER_ID, customerId)
                    put(COLUMN_AMOUNT_PAISE, t.amountPaise)
                    put(COLUMN_TYPE, t.type)
                    put(COLUMN_TIMESTAMP, timestamp)
                }

                val rowId = db.insert(TABLE_TRANSACTIONS, null, values)
                if (rowId == -1L) return false
            }
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Calculate outstanding customer balance in integer paise.
     * Net balance = Total Credit (udhaar) - Total Payment (jama).
     */
    fun getCustomerBalancePaise(customerName: String): Long {
        val db = getReadableEncryptedDatabase()
        val query = """
            SELECT 
                SUM(CASE WHEN t.$COLUMN_TYPE = 'CREDIT' THEN t.$COLUMN_AMOUNT_PAISE ELSE -t.$COLUMN_AMOUNT_PAISE END)
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
     * Correct the most recent transaction amount (for "Nahi, 430" corrections).
     */
    fun updateLastTransactionAmount(newAmountPaise: Long): Boolean {
        val db = getWritableEncryptedDatabase()
        db.beginTransaction()
        try {
            val cursor = db.rawQuery(
                "SELECT $COLUMN_ID FROM $TABLE_TRANSACTIONS ORDER BY $COLUMN_ID DESC LIMIT 1",
                null
            )
            val lastId = cursor.use {
                if (it.moveToFirst()) it.getLong(0) else -1L
            }

            if (lastId == -1L) return false

            val values = ContentValues().apply {
                put(COLUMN_AMOUNT_PAISE, newAmountPaise)
            }
            val rows = db.update(TABLE_TRANSACTIONS, values, "$COLUMN_ID = ?", arrayOf(lastId.toString()))
            if (rows > 0) {
                db.setTransactionSuccessful()
                return true
            }
            return false
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Check if customer exists in customers table. Insert if not present.
     */
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
        }
        return db.insert(TABLE_CUSTOMERS, null, values)
    }
}
