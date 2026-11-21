/*
 * Copyright (c) 2026 KHATAWAS Project
 * Simplified Technical English (ASD STE-100) compliant.
 * Unit tests verifying exact four transaction categories and mapping logic.
 */

package com.khatawas.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionCategoryTest {

    @Test
    fun testExactFourCategoriesExist() {
        val categories = TransactionCategory.values()
        assertEquals(4, categories.size)
        assertEquals("CREDIT", TransactionCategory.CREDIT.name)
        assertEquals("DEBIT", TransactionCategory.DEBIT.name)
        assertEquals("STUCK", TransactionCategory.STUCK.name)
        assertEquals("ILLEGAL", TransactionCategory.ILLEGAL.name)
    }

    @Test
    fun testCategorySemanticsAndLabels() {
        assertEquals("CREDIT", TransactionCategory.CREDIT.label)
        assertEquals("DEBIT", TransactionCategory.DEBIT.label)
        assertEquals("STUCK", TransactionCategory.STUCK.label)
        assertEquals("ILLEGAL", TransactionCategory.ILLEGAL.label)

        assertTrue(TransactionCategory.CREDIT.meaning.contains("added"))
        assertTrue(TransactionCategory.DEBIT.meaning.contains("deducted"))
        assertTrue(TransactionCategory.STUCK.meaning.contains("stuck"))
        assertTrue(TransactionCategory.ILLEGAL.meaning.contains("illegal"))
    }

    @Test
    fun testDistinctColorsAcrossCategories() {
        val lightColors = TransactionCategory.values().map { it.lightHexColor }.toSet()
        val darkColors = TransactionCategory.values().map { it.darkHexColor }.toSet()

        // All 4 categories must have unique light and dark colors
        assertEquals(4, lightColors.size)
        assertEquals(4, darkColors.size)
    }

    @Test
    fun testMappingFromDatabaseRecords() {
        // CREDIT -> CREDIT
        assertEquals(TransactionCategory.CREDIT, TransactionCategory.fromRecord("CREDIT"))

        // PAYMENT -> DEBIT (customer paid, reducing debt balance)
        assertEquals(TransactionCategory.DEBIT, TransactionCategory.fromRecord("PAYMENT"))

        // REVERSAL -> DEBIT
        assertEquals(TransactionCategory.DEBIT, TransactionCategory.fromRecord("REVERSAL"))

        // Explicit STUCK classification in type or note
        assertEquals(TransactionCategory.STUCK, TransactionCategory.fromRecord("STUCK"))
        assertEquals(TransactionCategory.STUCK, TransactionCategory.fromRecord("PAYMENT", "CLASSIFICATION:STUCK bank gateway"))
        assertEquals(TransactionCategory.STUCK, TransactionCategory.fromRecord("CREDIT", "Transaction marked STUCK"))

        // Explicit ILLEGAL classification in type or note
        assertEquals(TransactionCategory.ILLEGAL, TransactionCategory.fromRecord("ILLEGAL"))
        assertEquals(TransactionCategory.ILLEGAL, TransactionCategory.fromRecord("PAYMENT", "CLASSIFICATION:ILLEGAL payment"))
        assertEquals(TransactionCategory.ILLEGAL, TransactionCategory.fromRecord("CREDIT", "Disallowed illegal transfer"))
    }

    @Test
    fun testUiModelAccessibleDescription() {
        val model = TransactionUiModel(
            id = 1L,
            eventId = "evt-123",
            customerName = "Ramesh Kumar",
            amountPaise = 50000L,
            category = TransactionCategory.CREDIT,
            timestamp = 1732200000000L,
            note = "Udhaar groceries"
        )

        assertEquals("₹500", model.formattedAmount)
        assertTrue(model.accessibleDescription.contains("Ramesh Kumar"))
        assertTrue(model.accessibleDescription.contains("CREDIT"))
        assertTrue(model.accessibleDescription.contains("₹500"))
    }
}
