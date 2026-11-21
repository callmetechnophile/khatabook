/*
 * Copyright (c) 2026 KHATAWAS Project
 * Simplified Technical English (ASD STE-100) compliant.
 * Mock Speech-To-Text (STT) bridge for offline pipeline integration.
 */

package com.khatawas.audio

import com.khatawas.db.LedgerDatabase
import com.khatawas.logic.LedgerParser
import com.khatawas.logic.Transaction

/**
 * Mock STT bridge.
 * Mocks the Gnani Prisma streaming speech recognition API.
 * Converts speech text to a Transaction and writes the record to the database.
 * Does not make external network requests.
 */
class MockSttBridge(private val database: LedgerDatabase) {

    /**
     * Process recognized speech text.
     * Parses the utterance using deterministic Regex.
     * Stores the transaction in the encrypted SQLite database.
     *
     * Parameter text: Speech recognition transcript.
     * Returns Transaction if parsed and written to database, null otherwise.
     */
    fun simulateSttInput(text: String): Transaction? {
        return processAndInsert(text, database)
    }

    companion object {
        @Volatile
        private var defaultDatabase: LedgerDatabase? = null

        /**
         * Initialize the default database instance for static calls.
         */
        fun init(database: LedgerDatabase) {
            defaultDatabase = database
        }

        /**
         * Static bridge method to process speech input.
         * Parameter text: Speech recognition transcript.
         * Returns Transaction if parsed and written to database, null otherwise.
         */
        fun simulateSttInput(text: String): Transaction? {
            val db = defaultDatabase
                ?: throw IllegalStateException("MockSttBridge default database is not initialized.")
            return processAndInsert(text, db)
        }

        /**
         * Internal helper to parse utterance and insert record into database.
         */
        private fun processAndInsert(text: String, db: LedgerDatabase): Transaction? {
            val transaction = LedgerParser.parseUtterance(text) ?: return null
            val rowId = db.insertTransaction(transaction)
            return if (rowId > 0L) {
                transaction
            } else {
                null
            }
        }
    }
}
