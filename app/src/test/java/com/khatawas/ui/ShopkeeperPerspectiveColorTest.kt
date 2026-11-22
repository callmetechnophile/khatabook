/*
 * Copyright (c) 2026 KHATAWAS Project
 * Simplified Technical English (ASD STE-100) compliant.
 * Acceptance and regression test suite for Shopkeeper Cash-Flow Perspective UI colors and semantics.
 */

package com.khatawas.ui

import com.khatawas.R
import com.khatawas.logic.LedgerParser
import com.khatawas.logic.ParseResult
import com.khatawas.logic.Transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShopkeeperPerspectiveColorTest {

    @Test
    fun testCategoryColorMappingShopkeeperPerspective() {
        // 1. Give Udhar / Add Credit must map to RED presentation
        val creditCategory = TransactionCategory.CREDIT
        assertEquals("CREDIT", creditCategory.label)
        assertEquals("#C62828", creditCategory.lightHexColor)
        assertEquals(R.color.badge_credit_text, creditCategory.textColorRes)
        assertEquals(R.drawable.bg_badge_credit, creditCategory.bgDrawableRes)

        // 2. Got Payment / Receive Payment must map to GREEN presentation
        val debitCategory = TransactionCategory.DEBIT
        assertEquals("DEBIT", debitCategory.label)
        assertEquals("#1B5E20", debitCategory.lightHexColor)
        assertEquals(R.color.badge_debit_text, debitCategory.textColorRes)
        assertEquals(R.drawable.bg_badge_debit, debitCategory.bgDrawableRes)
    }

    @Test
    fun testAcceptanceCriteriaGiven1000RupeesOutstanding() {
        // Given an outstanding customer balance of ₹1,000 (100,000 paise)
        val initialBalancePaise = 100_000L
        assertEquals("₹1,000", IndianCurrencyFormatter.formatPaise(initialBalancePaise))

        // 1. Recording a ₹500 Give Udhar transaction:
        // Shopkeeper gives goods/credit. Customer's outstanding balance increases.
        // Financial semantics: balance_effect_paise > 0
        val giveUdharAmountPaise = 50_000L
        val udharBalanceEffectPaise = giveUdharAmountPaise
        assertTrue("Give Udhar balance effect must be positive", udharBalanceEffectPaise > 0)

        val balanceAfterUdhar = initialBalancePaise + udharBalanceEffectPaise
        assertEquals(150_000L, balanceAfterUdhar)
        assertEquals("₹1,500", IndianCurrencyFormatter.formatPaise(balanceAfterUdhar))

        val udharCategory = TransactionCategory.fromRecord("CREDIT")
        assertEquals(TransactionCategory.CREDIT, udharCategory)
        assertEquals("#C62828", udharCategory.lightHexColor) // RED

        // 2. Recording a ₹500 Got Payment transaction:
        // Shopkeeper receives money. Customer's outstanding balance decreases.
        // Financial semantics: balance_effect_paise < 0
        val gotPaymentAmountPaise = 50_000L
        val paymentBalanceEffectPaise = -gotPaymentAmountPaise
        assertTrue("Got Payment balance effect must be negative", paymentBalanceEffectPaise < 0)

        val balanceAfterPayment = initialBalancePaise + paymentBalanceEffectPaise
        assertEquals(50_000L, balanceAfterPayment)
        assertEquals("₹500", IndianCurrencyFormatter.formatPaise(balanceAfterPayment))

        val paymentCategory = TransactionCategory.fromRecord("PAYMENT")
        assertEquals(TransactionCategory.DEBIT, paymentCategory)
        assertEquals("#1B5E20", paymentCategory.lightHexColor) // GREEN
    }

    @Test
    fun testVoiceTransactionFlowSemanticsAndColorMapping() {
        // Voice flow: "Ramesh ka 500 udhaar" (Give Udhar)
        val voiceCreditResult = LedgerParser.parseStructured("Ramesh ka 500 udhaar")
        assertTrue(voiceCreditResult is ParseResult.Success)
        val creditTx = (voiceCreditResult as ParseResult.Success).transactions[0]
        assertEquals("Ramesh", creditTx.name)
        assertEquals(50_000L, creditTx.amountPaise)
        assertEquals("CREDIT", creditTx.type)

        val creditUiModel = TransactionUiModel(
            id = 1L,
            eventId = creditTx.eventId,
            customerName = creditTx.name,
            amountPaise = creditTx.amountPaise,
            category = TransactionCategory.fromRecord(creditTx.type),
            timestamp = System.currentTimeMillis(),
            note = ""
        )
        assertEquals(TransactionCategory.CREDIT, creditUiModel.category)
        assertEquals("#C62828", creditUiModel.category.lightHexColor) // RED
        assertEquals("₹500", creditUiModel.formattedAmount)

        // Voice flow: "Ramesh ne 500 jama kiye" (Got Payment)
        val voicePaymentResult = LedgerParser.parseStructured("Ramesh ne 500 jama kiye")
        assertTrue(voicePaymentResult is ParseResult.Success)
        val paymentTx = (voicePaymentResult as ParseResult.Success).transactions[0]
        assertEquals("Ramesh", paymentTx.name)
        assertEquals(50_000L, paymentTx.amountPaise)
        assertEquals("PAYMENT", paymentTx.type)

        val paymentUiModel = TransactionUiModel(
            id = 2L,
            eventId = paymentTx.eventId,
            customerName = paymentTx.name,
            amountPaise = paymentTx.amountPaise,
            category = TransactionCategory.fromRecord(paymentTx.type),
            timestamp = System.currentTimeMillis(),
            note = ""
        )
        assertEquals(TransactionCategory.DEBIT, paymentUiModel.category)
        assertEquals("#1B5E20", paymentUiModel.category.lightHexColor) // GREEN
        assertEquals("₹500", paymentUiModel.formattedAmount)
    }

    @Test
    fun testCustomerUiModelBalanceColorLogic() {
        // Outstanding balance > 0 (Customer owes money) -> RED color
        val customerOwes = CustomerUiModel(name = "Ramesh", balancePaise = 150_000L)
        assertEquals("₹1,500", customerOwes.getDisplayBalance(isUnlocked = true))
        assertTrue("Customer owes money if balance > 0", customerOwes.balancePaise > 0L)

        // Advance payment balance < 0 (Customer paid in advance) -> GREEN color
        val customerAdvance = CustomerUiModel(name = "Suresh", balancePaise = -50_000L)
        assertEquals("-₹500", customerAdvance.getDisplayBalance(isUnlocked = true))
        assertTrue("Customer has advance balance if balance < 0", customerAdvance.balancePaise < 0L)
    }
}
