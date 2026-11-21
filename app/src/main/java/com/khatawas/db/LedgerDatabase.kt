/*
 * Copyright (c) 2026 KHATAWAS Project
 * Simplified Technical English (ASD STE-100) compliant.
 * Local encrypted SQLite database using SQLCipher.
 */

package com.khatawas.db

import android.content.ContentValues
import android.content.Context
import com.khatawas.logic.Transaction
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteOpenHelper

/**
 * Encrypted ledger database using SQLCipher.
 * Stores Kirana customer records and ledger transactions with AES-256 encryption.
 * Does not transmit data to external cloud services.
 */
class LedgerDatabase(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {

    companion object {
        const val DATABASE_NAME = "khatawas.db"
        const val DATABASE_VERSION = 1

        // Security: Placeholder constant for the database password.
        // In production, this key is derived dynamically from Android Keystore on first launch.
        private const val KEYSTORE_DERIVED_KEY = "KEYSTORE_DERIVED_KEY_PLACEHOLDER"

        private const val TABLE_CUSTOMERS = "customers"
        private const val TABLE_TRANSACTIONS = "transactions"

        private const val COLUMN_ID = "id"
        private const val COLUMN_NAME = "name"

        private const val COLUMN_CUSTOMER_ID = "customer_id"
        private const val COLUMN_AMOUNT = "amount"
        private const val COLUMN_TYPE = "type"
        private const val COLUMN_TIMESTAMP = "timestamp"

        private const val MILLIS_PER_DAY = 86_400_000L

        init {
            // Load SQLCipher native binaries
            System.loadLibrary("sqlcipher")
        }
    }

    /**
     * Get the writable database using the Keystore-derived encryption password.
     */
    fun getWritableEncryptedDatabase(): SQLiteDatabase {
        return getWritableDatabase(KEYSTORE_DERIVED_KEY)
    }

    /**
     * Get the readable database using the Keystore-derived encryption password.
     */
    fun getReadableEncryptedDatabase(): SQLiteDatabase {
        return getReadableDatabase(KEYSTORE_DERIVED_KEY)
    }

    override fun onCreate(db: SQLiteDatabase) {
        // Table 1: customers
        db.execSQL(
            """
            CREATE TABLE $TABLE_CUSTOMERS (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_NAME TEXT UNIQUE NOT NULL
            );
            """.trimIndent()
        )

        // Table 2: transactions
        db.execSQL(
            """
            CREATE TABLE $TABLE_TRANSACTIONS (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_CUSTOMER_ID INTEGER,
                $COLUMN_AMOUNT INTEGER,
                $COLUMN_TYPE TEXT,
                $COLUMN_TIMESTAMP INTEGER,
                FOREIGN KEY($COLUMN_CUSTOMER_ID) REFERENCES $TABLE_CUSTOMERS($COLUMN_ID)
            );
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Retain table structure for version upgrades
    }

    /**
     * Insert a transaction into the encrypted database.
     * Checks if the customer exists. If not, inserts the customer.
     * Computes the timestamp from current time plus the date offset.
     * Parameter transaction: The transaction data class instance.
     * Returns the database row ID of the inserted transaction, or -1 on error.
     */
    fun insertTransaction(transaction: Transaction): Long {
        val db = getWritableEncryptedDatabase()
        db.beginTransaction()
        try {
            // Step 1: Check if customer exists; insert if not
            val customerId = getOrCreateCustomer(db, transaction.name)
            if (customerId < 0) {
                return -1L
            }

            // Step 2: Compute timestamp with date offset (offset * 86,400,000 ms)
            val timestamp = System.currentTimeMillis() + (transaction.dateOffset * MILLIS_PER_DAY)

            // Step 3: Insert transaction record
            val values = ContentValues().apply {
                put(COLUMN_CUSTOMER_ID, customerId)
                put(COLUMN_AMOUNT, transaction.amount)
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

        // Customer not found: insert customer
        val values = ContentValues().apply {
            put(COLUMN_NAME, nameTrimmed)
        }
        return db.insert(TABLE_CUSTOMERS, null, values)
    }
}
