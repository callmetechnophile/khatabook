/*
 * Copyright (c) 2026 KHATAWAS Project
 * Simplified Technical English (ASD STE-100) compliant.
 * Unit tests for LedgerParser logic.
 */

package com.khatawas.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Unit test suite for LedgerParser.
 * Verifies deterministic intent parsing for Kirana store voice ledger inputs.
 */
class LedgerParserTest {

    @Test
    fun testCreditTransactionWithDigits() {
        val input = "Ramesh ka 340 udhaar"
        val transaction = LedgerParser.parseUtterance(input)

        assertNotNull("Transaction must not be null", transaction)
        assertEquals("Customer name must be Ramesh", "Ramesh", transaction!!.name)
        assertEquals("Amount must be 340", 340, transaction.amount)
        assertEquals("Type must be CREDIT", "CREDIT", transaction.type)
        assertEquals("Date offset must be 0", 0, transaction.dateOffset)
    }

    @Test
    fun testDebitTransactionWithDigits() {
        val input = "Suresh ko 200 diya"
        val transaction = LedgerParser.parseUtterance(input)

        assertNotNull("Transaction must not be null", transaction)
        assertEquals("Customer name must be Suresh", "Suresh", transaction!!.name)
        assertEquals("Amount must be 200", 200, transaction.amount)
        assertEquals("Type must be DEBIT", "DEBIT", transaction.type)
        assertEquals("Date offset must be 0", 0, transaction.dateOffset)
    }

    @Test
    fun testYesterdayTransactionWithDateOffset() {
        val input = "Kal wala 500 jama"
        val transaction = LedgerParser.parseUtterance(input)

        assertNotNull("Transaction must not be null", transaction)
        assertEquals("Customer name must be Kal", "Kal", transaction!!.name)
        assertEquals("Amount must be 500", 500, transaction.amount)
        assertEquals("Type must be DEBIT", "DEBIT", transaction.type)
        assertEquals("Date offset must be -1", -1, transaction.dateOffset)
    }
}
