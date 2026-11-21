/*
 * Copyright (c) 2026 KHATAWAS Project
 * Simplified Technical English (ASD STE-100) compliant.
 * Unit tests for PrivacyManager financial data masking, PIN format validation, and accessibility descriptions.
 */

package com.khatawas.auth

import com.khatawas.ui.TransactionCategory
import com.khatawas.ui.TransactionUiModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PrivacyManagerTest {

    @Before
    fun setUp() {
        // Enforce default locked state before each test
        PrivacyManager.lockSession()
    }

    @Test
    fun testDefaultStateIsLocked() {
        assertFalse("PrivacyManager must start in a locked session state", PrivacyManager.isUnlocked)
    }

    @Test
    fun testUnlockAndLockSession() {
        PrivacyManager.lockSession()
        assertFalse(PrivacyManager.isUnlocked)

        PrivacyManager.unlockSession()
        assertTrue(PrivacyManager.isUnlocked)

        PrivacyManager.lockSession()
        assertFalse(PrivacyManager.isUnlocked)
    }

    @Test
    fun testMaskAmountWhenLocked() {
        PrivacyManager.lockSession()
        val originalAmount = "₹5,000"
        val masked = PrivacyManager.maskAmount(originalAmount)
        assertEquals("###XXX", masked)
    }

    @Test
    fun testExposeAmountWhenUnlocked() {
        PrivacyManager.unlockSession()
        val originalAmount = "₹5,000"
        val display = PrivacyManager.maskAmount(originalAmount)
        assertEquals("₹5,000", display)
    }

    @Test
    fun testMaskPaymentReferenceWhenLocked() {
        PrivacyManager.lockSession()
        val ref = "UPI-REF-9876543210"
        val masked = PrivacyManager.maskPaymentReference(ref)
        assertEquals("###XXX", masked)
    }

    @Test
    fun testExposePaymentReferenceWhenUnlocked() {
        PrivacyManager.unlockSession()
        val ref = "UPI-REF-9876543210"
        val display = PrivacyManager.maskPaymentReference(ref)
        assertEquals("UPI-REF-9876543210", display)
    }

    @Test
    fun testAccessibleDescriptionsWhenLocked() {
        PrivacyManager.lockSession()
        assertEquals(PrivacyManager.ACCESSIBLE_AMOUNT_HIDDEN, PrivacyManager.getAmountContentDescription("₹1,200"))
        assertEquals(PrivacyManager.ACCESSIBLE_BALANCE_HIDDEN, PrivacyManager.getBalanceContentDescription("₹10,500"))
        assertEquals("Amount hidden", PrivacyManager.ACCESSIBLE_AMOUNT_HIDDEN)
        assertEquals("Balance hidden", PrivacyManager.ACCESSIBLE_BALANCE_HIDDEN)
        assertEquals("Customer name hidden", PrivacyManager.ACCESSIBLE_NAME_HIDDEN)
    }

    @Test
    fun testAccessibleDescriptionsWhenUnlocked() {
        PrivacyManager.unlockSession()
        assertEquals("₹1,200", PrivacyManager.getAmountContentDescription("₹1,200"))
        assertEquals("₹10,500", PrivacyManager.getBalanceContentDescription("₹10,500"))
    }

    @Test
    fun testPinFormatValidation() {
        // Valid 4 to 8 digit PINs
        assertTrue(PrivacyManager.isValidPinFormat("1234"))
        assertTrue(PrivacyManager.isValidPinFormat("0000"))
        assertTrue(PrivacyManager.isValidPinFormat("123456"))
        assertTrue(PrivacyManager.isValidPinFormat("12345678"))

        // Invalid: too short (< 4 digits)
        assertFalse(PrivacyManager.isValidPinFormat(""))
        assertFalse(PrivacyManager.isValidPinFormat("1"))
        assertFalse(PrivacyManager.isValidPinFormat("12"))
        assertFalse(PrivacyManager.isValidPinFormat("123"))

        // Invalid: too long (> 8 digits)
        assertFalse(PrivacyManager.isValidPinFormat("123456789"))

        // Invalid: non-numeric characters
        assertFalse(PrivacyManager.isValidPinFormat("123a"))
        assertFalse(PrivacyManager.isValidPinFormat("abcd"))
        assertFalse(PrivacyManager.isValidPinFormat("12 34"))
        assertFalse(PrivacyManager.isValidPinFormat("12-34"))
    }

    @Test
    fun testTransactionUiModelMasking() {
        val model = TransactionUiModel(
            id = 1L,
            eventId = "EVT-1001",
            customerName = "Ramesh Kumar",
            amountPaise = 250000L,
            category = TransactionCategory.CREDIT,
            timestamp = 1732185600000L,
            note = "Invoice #101"
        )

        // When locked and customer names masked:
        val displayAmountLocked = model.getDisplayAmount(isUnlocked = false)
        val displayCustomerLocked = model.getDisplayCustomerName(isUnlocked = false, maskCustomerNames = true)
        val accessibleLocked = model.getAccessibleDescription(isUnlocked = false, maskCustomerNames = true)

        assertEquals("###XXX", displayAmountLocked)
        assertEquals("###XXX", displayCustomerLocked)
        assertTrue(accessibleLocked.contains("Amount hidden"))
        assertTrue(accessibleLocked.contains("Customer name hidden"))

        // When locked but customer names NOT masked:
        val displayCustomerUnmaskedName = model.getDisplayCustomerName(isUnlocked = false, maskCustomerNames = false)
        assertEquals("Ramesh Kumar", displayCustomerUnmaskedName)

        // When unlocked:
        val displayAmountUnlocked = model.getDisplayAmount(isUnlocked = true)
        val displayCustomerUnlocked = model.getDisplayCustomerName(isUnlocked = true, maskCustomerNames = true)
        val accessibleUnlocked = model.getAccessibleDescription(isUnlocked = true, maskCustomerNames = true)

        assertEquals("₹2,500", displayAmountUnlocked)
        assertEquals("Ramesh Kumar", displayCustomerUnlocked)
        assertTrue(accessibleUnlocked.contains("₹2,500"))
        assertTrue(accessibleUnlocked.contains("Ramesh Kumar"))
    }
}
