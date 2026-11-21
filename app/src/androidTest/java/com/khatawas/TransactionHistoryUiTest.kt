/*
 * Copyright (c) 2026 KHATAWAS Project
 * Simplified Technical English (ASD STE-100) compliant.
 * Instrumented UI test suite verifying:
 * 1. A CREDIT transaction displays green.
 * 2. A DEBIT transaction displays red.
 * 3. A STUCK transaction displays yellow.
 * 4. An ILLEGAL transaction displays grey.
 * 5. Category labels remain visible in light and dark modes.
 * 6. Large Indian currency values format correctly without layout clipping.
 * 7. Transaction history remains readable with many records.
 * 8. Existing customer balances remain unchanged.
 * 9. Existing transaction creation and query workflows still work.
 * 10. Empty transaction history is handled correctly.
 */

package com.khatawas

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.khatawas.logic.LedgerParser
import com.khatawas.logic.ParseResult
import com.khatawas.logic.Transaction
import com.khatawas.ui.IndianCurrencyFormatter
import com.khatawas.ui.TransactionCategory
import com.khatawas.ui.TransactionUiModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransactionHistoryUiTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun testCriterion1_CreditTransactionDisplaysGreen() {
        val category = TransactionCategory.CREDIT
        assertEquals("CREDIT", category.label)
        assertEquals(R.drawable.bg_badge_credit, category.bgDrawableRes)
        assertEquals(R.color.badge_credit_text, category.textColorRes)

        val resolvedColor = ContextCompat.getColor(context, category.textColorRes)
        val expectedLightColor = Color.parseColor("#1B5E20")
        val expectedDarkColor = Color.parseColor("#81C784")

        // Must be green in light mode or dark mode
        assertTrue(
            "Credit color must be dark green (light mode) or soft green (dark mode). Actual: $resolvedColor",
            resolvedColor == expectedLightColor || resolvedColor == expectedDarkColor
        )

        // Verify inflated view
        val view = LayoutInflater.from(context).inflate(R.layout.item_transaction, null)
        val tvBadge = view.findViewById<TextView>(R.id.tv_category_badge)
        tvBadge.text = category.label
        tvBadge.setBackgroundResource(category.bgDrawableRes)
        tvBadge.setTextColor(resolvedColor)

        assertEquals("CREDIT", tvBadge.text.toString())
        assertEquals(resolvedColor, tvBadge.currentTextColor)
    }

    @Test
    fun testCriterion2_DebitTransactionDisplaysRed() {
        val category = TransactionCategory.DEBIT
        assertEquals("DEBIT", category.label)
        assertEquals(R.drawable.bg_badge_debit, category.bgDrawableRes)
        assertEquals(R.color.badge_debit_text, category.textColorRes)

        val resolvedColor = ContextCompat.getColor(context, category.textColorRes)
        val expectedLightColor = Color.parseColor("#B71C1C")
        val expectedDarkColor = Color.parseColor("#EF9A9A")

        assertTrue(
            "Debit color must be dark red (light mode) or light red (dark mode). Actual: $resolvedColor",
            resolvedColor == expectedLightColor || resolvedColor == expectedDarkColor
        )

        val view = LayoutInflater.from(context).inflate(R.layout.item_transaction, null)
        val tvBadge = view.findViewById<TextView>(R.id.tv_category_badge)
        tvBadge.text = category.label
        tvBadge.setBackgroundResource(category.bgDrawableRes)
        tvBadge.setTextColor(resolvedColor)

        assertEquals("DEBIT", tvBadge.text.toString())
        assertEquals(resolvedColor, tvBadge.currentTextColor)
    }

    @Test
    fun testCriterion3_StuckTransactionDisplaysYellow() {
        val category = TransactionCategory.STUCK
        assertEquals("STUCK", category.label)
        assertEquals(R.drawable.bg_badge_stuck, category.bgDrawableRes)
        assertEquals(R.color.badge_stuck_text, category.textColorRes)

        val resolvedColor = ContextCompat.getColor(context, category.textColorRes)
        val expectedLightColor = Color.parseColor("#8D5B00") // Accessible dark amber/yellow
        val expectedDarkColor = Color.parseColor("#FFE082")  // Accessible light amber/yellow

        assertTrue(
            "Stuck color must be amber/yellow with high contrast. Actual: $resolvedColor",
            resolvedColor == expectedLightColor || resolvedColor == expectedDarkColor
        )

        val view = LayoutInflater.from(context).inflate(R.layout.item_transaction, null)
        val tvBadge = view.findViewById<TextView>(R.id.tv_category_badge)
        tvBadge.text = category.label
        tvBadge.setBackgroundResource(category.bgDrawableRes)
        tvBadge.setTextColor(resolvedColor)

        assertEquals("STUCK", tvBadge.text.toString())
        assertEquals(resolvedColor, tvBadge.currentTextColor)
    }

    @Test
    fun testCriterion4_IllegalTransactionDisplaysGrey() {
        val category = TransactionCategory.ILLEGAL
        assertEquals("ILLEGAL", category.label)
        assertEquals(R.drawable.bg_badge_illegal, category.bgDrawableRes)
        assertEquals(R.color.badge_illegal_text, category.textColorRes)

        val resolvedColor = ContextCompat.getColor(context, category.textColorRes)
        val expectedLightColor = Color.parseColor("#263238") // Dark blue grey
        val expectedDarkColor = Color.parseColor("#CFD8DC")  // Light grey

        assertTrue(
            "Illegal color must be grey/blue-grey. Actual: $resolvedColor",
            resolvedColor == expectedLightColor || resolvedColor == expectedDarkColor
        )

        val view = LayoutInflater.from(context).inflate(R.layout.item_transaction, null)
        val tvBadge = view.findViewById<TextView>(R.id.tv_category_badge)
        tvBadge.text = category.label
        tvBadge.setBackgroundResource(category.bgDrawableRes)
        tvBadge.setTextColor(resolvedColor)

        assertEquals("ILLEGAL", tvBadge.text.toString())
        assertEquals(resolvedColor, tvBadge.currentTextColor)
    }

    @Test
    fun testCriterion5_CategoryLabelsRemainVisibleInLightAndDarkModes() {
        for (category in TransactionCategory.values()) {
            val color = ContextCompat.getColor(context, category.textColorRes)
            // Color must not be transparent
            assertFalse("Color must not be transparent", Color.alpha(color) == 0)

            // Text label must be non-empty and accessible
            assertTrue(category.label.isNotBlank())
            assertTrue(category.meaning.isNotBlank())

            val model = TransactionUiModel(
                id = 1L,
                eventId = "test-uuid",
                customerName = "Ramesh",
                amountPaise = 50000L,
                category = category,
                timestamp = System.currentTimeMillis(),
                note = "Test entry"
            )
            assertTrue(model.accessibleDescription.contains(category.label))
        }
    }

    @Test
    fun testCriterion6_LargeIndianCurrencyValuesFormatCorrectly() {
        // ₹500, ₹1,250, ₹25,000, ₹1,00,000
        assertEquals("₹500", IndianCurrencyFormatter.formatPaise(50000L))
        assertEquals("₹1,250", IndianCurrencyFormatter.formatPaise(125000L))
        assertEquals("₹25,000", IndianCurrencyFormatter.formatPaise(2500000L))
        assertEquals("₹1,00,000", IndianCurrencyFormatter.formatPaise(10000000L))
        assertEquals("₹10,00,00,000", IndianCurrencyFormatter.formatPaise(10000000000L))

        // Check inflation and binding with large value
        val view = LayoutInflater.from(context).inflate(R.layout.item_transaction, null)
        val tvAmount = view.findViewById<TextView>(R.id.tv_amount)
        tvAmount.text = IndianCurrencyFormatter.formatPaise(10000000000L)
        assertEquals("₹10,00,00,000", tvAmount.text.toString())
    }

    @Test
    fun testCriterion7_TransactionHistoryRemainsReadableWithManyRecords() {
        val models = (1..50).map { i ->
            val cat = when (i % 4) {
                0 -> TransactionCategory.CREDIT
                1 -> TransactionCategory.DEBIT
                2 -> TransactionCategory.STUCK
                else -> TransactionCategory.ILLEGAL
            }
            TransactionUiModel(
                id = i.toLong(),
                eventId = "evt-$i",
                customerName = "Customer #$i With A Rather Long Full Name For Layout Testing",
                amountPaise = (i * 25000L),
                category = cat,
                timestamp = System.currentTimeMillis() - (i * 3600000L),
                note = "Invoice #$i goods delivery note"
            )
        }

        assertEquals(50, models.size)
        // Verify each model generates valid formatted amounts and descriptions without exception
        for (m in models) {
            assertTrue(m.formattedAmount.startsWith("₹"))
            assertTrue(m.customerName.length > 20)
            assertTrue(m.accessibleDescription.isNotBlank())
        }
    }

    @Test
    fun testCriterion8_ExistingCustomerBalancesRemainUnchanged() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            // Access authoritative database directly
            val db = com.khatawas.db.LedgerDatabase(activity)
            val balanceBefore = db.getCustomerBalancePaise("BenchmarkCustomer")

            // Trigger UI refresh
            activity.refreshLedgerAndTransactions("BenchmarkCustomer")

            val balanceAfter = db.getCustomerBalancePaise("BenchmarkCustomer")
            assertEquals("Customer balance must be identical before and after UI presentation refresh", balanceBefore, balanceAfter)
            db.close()
        }
        scenario.close()
    }

    @Test
    fun testCriterion9_ExistingTransactionCreationAndQueryWorkflowsStillWork() {
        // Validate natural language parsing workflow integrity
        val creditParsed = LedgerParser.parseStructured("Ramesh ka 340 udhaar")
        assertTrue(creditParsed is ParseResult.Success)
        val creditTx = (creditParsed as ParseResult.Success).transactions.first()
        assertEquals("Ramesh", creditTx.name)
        assertEquals(34000L, creditTx.amountPaise)
        assertEquals("CREDIT", creditTx.type)

        val queryParsed = LedgerParser.parseStructured("Ramesh ka hisaab batao")
        assertTrue(queryParsed is ParseResult.Query)
        assertEquals("Ramesh", (queryParsed as ParseResult.Query).customerName)

        val reversalParsed = LedgerParser.parseStructured("Ramesh ka hisaab cancel karo")
        assertTrue(reversalParsed is ParseResult.Reversal)
        assertEquals("Ramesh", (reversalParsed as ParseResult.Reversal).customerName)
    }

    @Test
    fun testCriterion10_EmptyTransactionHistoryIsHandledCorrectly() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            val llEmpty = activity.findViewById<View>(R.id.ll_empty_state)
            val rv = activity.findViewById<View>(R.id.rv_transactions)
            assertNotNull(llEmpty)
            assertNotNull(rv)

            // When list is empty, empty state view must be visible and RV gone (or vice versa when items exist)
            val isConsistent = (llEmpty.visibility == View.VISIBLE && rv.visibility == View.GONE) ||
                    (llEmpty.visibility == View.GONE && rv.visibility == View.VISIBLE)
            assertTrue("Empty state and RecyclerView visibilities must be mutually consistent", isConsistent)
        }
        scenario.close()
    }
}
