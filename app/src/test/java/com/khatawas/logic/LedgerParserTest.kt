/*
 * Copyright (c) 2026 KHATAWAS Project
 * Simplified Technical English (ASD STE-100) compliant.
 * Regression unit test suite for LedgerParser logic (Latin + Devanagari).
 */

package com.khatawas.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerParserTest {

    @Test
    fun testCreditTransactionWithDigits() {
        val input = "Ramesh ka 340 udhaar"
        val result = LedgerParser.parseStructured(input)

        assertTrue(result is ParseResult.Success)
        val transactions = (result as ParseResult.Success).transactions
        assertEquals(1, transactions.size)

        val t = transactions[0]
        assertEquals("Ramesh", t.name)
        assertEquals(34000L, t.amountPaise)
        assertEquals(340, t.amount)
        assertEquals("CREDIT", t.type)
        assertEquals(0, t.dateOffset)
    }

    @Test
    fun testDevanagariCreditTransaction() {
        val input = "रमेश का 340 उधार"
        val result = LedgerParser.parseStructured(input)

        assertTrue(result is ParseResult.Success)
        val t = (result as ParseResult.Success).transactions[0]
        assertEquals("रमेश", t.name)
        assertEquals(34000L, t.amountPaise)
        assertEquals(340, t.amount)
        assertEquals("CREDIT", t.type)
        assertEquals(0, t.dateOffset)
    }

    @Test
    fun testPronounIskaRejectedAsCustomerName() {
        // "इसका 340 उधार" must NEVER be saved as a customer named "इसका"
        val inputDevanagari = "इसका 340 उधार"
        val result1 = LedgerParser.parseStructured(inputDevanagari)
        assertTrue("Devanagari pronoun 'इसका' must trigger ambiguity prompt", result1 is ParseResult.Ambiguous)
        val amb1 = result1 as ParseResult.Ambiguous
        assertTrue(amb1.clarificationQuestion.contains("customer ka naam", ignoreCase = true))

        val inputLatin = "Iska 340 udhaar"
        val result2 = LedgerParser.parseStructured(inputLatin)
        assertTrue("Latin pronoun 'Iska' must trigger ambiguity prompt", result2 is ParseResult.Ambiguous)
    }

    @Test
    fun testPronounUskaRejectedAsCustomerName() {
        val input = "Uska 200 jama"
        val result = LedgerParser.parseStructured(input)
        assertTrue("Pronoun 'Uska' must trigger ambiguity prompt", result is ParseResult.Ambiguous)
    }

    @Test
    fun testDevanagariPaymentTransaction() {
        val input = "सुरेश ने 200 दिया"
        val result = LedgerParser.parseStructured(input)

        assertTrue(result is ParseResult.Success)
        val t = (result as ParseResult.Success).transactions[0]
        assertEquals("सुरेश", t.name)
        assertEquals(20000L, t.amountPaise)
        assertEquals("PAYMENT", t.type)
        assertEquals(0, t.dateOffset)
    }

    @Test
    fun testDevanagariDigitsNormalization() {
        val input = "रमेश का ३४० उधार"
        val result = LedgerParser.parseStructured(input)

        assertTrue(result is ParseResult.Success)
        val t = (result as ParseResult.Success).transactions[0]
        assertEquals("रमेश", t.name)
        assertEquals(34000L, t.amountPaise)
        assertEquals("CREDIT", t.type)
    }

    @Test
    fun testYesterdayRelativeDateNotMistakenForCustomerName() {
        val input = "Kal Suresh ne 500 jama kiye"
        val result = LedgerParser.parseStructured(input)

        assertTrue(result is ParseResult.Success)
        val t = (result as ParseResult.Success).transactions[0]
        assertEquals("Suresh", t.name)
        assertEquals(50000L, t.amountPaise)
        assertEquals("PAYMENT", t.type)
        assertEquals(-1, t.dateOffset)
    }

    @Test
    fun testCompoundUtteranceMultipleTransactions() {
        val input = "Ramesh ka 340 udhaar, kal 200 diya tha"
        val result = LedgerParser.parseStructured(input)

        assertTrue(result is ParseResult.Success)
        val transactions = (result as ParseResult.Success).transactions
        assertEquals(2, transactions.size)

        val t1 = transactions[0]
        assertEquals("Ramesh", t1.name)
        assertEquals(34000L, t1.amountPaise)
        assertEquals("CREDIT", t1.type)
        assertEquals(0, t1.dateOffset)

        val t2 = transactions[1]
        assertEquals("Ramesh", t2.name)
        assertEquals(20000L, t2.amountPaise)
        assertEquals("PAYMENT", t2.type)
        assertEquals(-1, t2.dateOffset)
    }

    @Test
    fun testHindiNumberWordsComposition() {
        val input1 = "Ramesh ka teen sau chaalis udhaar"
        val r1 = LedgerParser.parseStructured(input1)
        assertTrue(r1 is ParseResult.Success)
        assertEquals(34000L, (r1 as ParseResult.Success).transactions[0].amountPaise)

        val input2 = "Suresh ne do sau pachaas jama kiye"
        val r2 = LedgerParser.parseStructured(input2)
        assertTrue(r2 is ParseResult.Success)
        assertEquals(25000L, (r2 as ParseResult.Success).transactions[0].amountPaise)

        val input3 = "Amit ka ek hazaar do sau udhaar"
        val r3 = LedgerParser.parseStructured(input3)
        assertTrue(r3 is ParseResult.Success)
        assertEquals(120000L, (r3 as ParseResult.Success).transactions[0].amountPaise)
    }

    @Test
    fun testCorrectionDetection() {
        val input = "Nahi, 430"
        val result = LedgerParser.parseStructured(input)

        assertTrue("Should detect correction", result is ParseResult.Correction)
        assertEquals(43000L, (result as ParseResult.Correction).newAmountPaise)
    }

    @Test
    fun testQueryDetection() {
        val input = "Ramesh ka hisaab batao"
        val result = LedgerParser.parseStructured(input)

        assertTrue("Should detect query", result is ParseResult.Query)
        val q = result as ParseResult.Query
        assertEquals("Ramesh", q.customerName)
        assertEquals("BALANCE", q.queryType)
    }

    @Test
    fun testAmbiguousDirectionNeverDefaultsToCredit() {
        val input = "Ramesh 500 rupaye"
        val result = LedgerParser.parseStructured(input)

        assertTrue("Should detect ambiguity when direction is missing", result is ParseResult.Ambiguous)
    }

    @Test
    fun testParsoAndPichhleHafteDateOffsets() {
        val inputParso = "Parso Suresh ne 200 diya"
        val rParso = LedgerParser.parseStructured(inputParso)
        assertTrue(rParso is ParseResult.Success)
        assertEquals(-2, (rParso as ParseResult.Success).transactions[0].dateOffset)

        val inputWeek = "Pichhle hafte Ramesh ne 400 diya"
        val rWeek = LedgerParser.parseStructured(inputWeek)
        assertTrue(rWeek is ParseResult.Success)
        assertEquals(-7, (rWeek as ParseResult.Success).transactions[0].dateOffset)
    }

    @Test
    fun testReversalDetection() {
        val input = "Ramesh ka hisaab cancel karo"
        val result = LedgerParser.parseStructured(input)

        assertTrue("Should detect reversal", result is ParseResult.Reversal)
        val rev = result as ParseResult.Reversal
        assertEquals("Ramesh", rev.customerName)
    }

    @Test
    fun testTopDebtorsQueryDetection() {
        val input = "Sabse zyada udhaar kiske paas hai?"
        val result = LedgerParser.parseStructured(input)

        assertTrue("Should detect top debtors query", result is ParseResult.TopDebtorsQuery)
    }

    @Test
    fun testDailyCreditAndPaymentQueryDetection() {
        val inputCredit = "Aaj kitna udhaar gaya?"
        val resCredit = LedgerParser.parseStructured(inputCredit)
        assertTrue("Should detect daily credit query", resCredit is ParseResult.DailySummaryQuery)
        val qCredit = resCredit as ParseResult.DailySummaryQuery
        assertEquals(0, qCredit.dateOffset)
        assertEquals("CREDIT", qCredit.queryType)

        val inputPayment = "Kal ka collection kitna tha?"
        val resPayment = LedgerParser.parseStructured(inputPayment)
        assertTrue("Should detect daily payment collection query", resPayment is ParseResult.DailySummaryQuery)
        val qPayment = resPayment as ParseResult.DailySummaryQuery
        assertEquals(-1, qPayment.dateOffset)
        assertEquals("PAYMENT", qPayment.queryType)
    }
}
