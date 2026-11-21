/*
 * Copyright (c) 2026 KHATAWAS Project
 * Simplified Technical English (ASD STE-100) compliant.
 * Unit tests verifying Indian Currency (INR / ₹) formatting rules across all value magnitudes.
 */

package com.khatawas.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class IndianCurrencyFormatterTest {

    @Test
    fun testZeroAmount() {
        assertEquals("₹0", IndianCurrencyFormatter.formatPaise(0L))
    }

    @Test
    fun testStandardAmountsFromPrompt() {
        // Acceptance Criteria: ₹500, ₹1,250, ₹25,000, ₹1,00,000
        assertEquals("₹500", IndianCurrencyFormatter.formatPaise(50000L))
        assertEquals("₹1,250", IndianCurrencyFormatter.formatPaise(125000L))
        assertEquals("₹25,000", IndianCurrencyFormatter.formatPaise(2500000L))
        assertEquals("₹1,00,000", IndianCurrencyFormatter.formatPaise(10000000L))
    }

    @Test
    fun testLargeCroreValues() {
        // 1 Crore = 1,00,00,000 Rupees = 1,00,00,00,000 Paise
        assertEquals("₹1,00,00,000", IndianCurrencyFormatter.formatPaise(1000000000L))
        // 10 Crore = 10,00,00,000 Rupees
        assertEquals("₹10,00,00,000", IndianCurrencyFormatter.formatPaise(10000000000L))
    }

    @Test
    fun testFractionalPaise() {
        // 1250.50 Rupees = 125050 Paise
        assertEquals("₹1,250.50", IndianCurrencyFormatter.formatPaise(125050L))
        // 50.05 Rupees = 5005 Paise
        assertEquals("₹50.05", IndianCurrencyFormatter.formatPaise(5005L))
        // 0.75 Rupees = 75 Paise
        assertEquals("₹0.75", IndianCurrencyFormatter.formatPaise(75L))
    }

    @Test
    fun testNegativeAmounts() {
        assertEquals("-₹500", IndianCurrencyFormatter.formatPaise(-50000L))
        assertEquals("-₹1,250", IndianCurrencyFormatter.formatPaise(-125000L))
    }

    @Test
    fun testWithoutSymbol() {
        assertEquals("1,00,000", IndianCurrencyFormatter.formatPaise(10000000L, includeSymbol = false))
        assertEquals("1,250", IndianCurrencyFormatter.formatPaise(125000L, includeSymbol = false))
    }
}
