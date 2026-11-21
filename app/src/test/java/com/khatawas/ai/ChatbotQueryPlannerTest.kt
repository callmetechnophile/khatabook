/*
 * Copyright (c) 2026 KHATAWAS Project
 * Simplified Technical English (ASD STE-100) compliant.
 * Unit tests verifying database-grounded query planning, exact integer paise balance calculation,
 * customer disambiguation, pronoun resolution, out-of-domain abstention, and privacy masking.
 */

package com.khatawas.ai

import com.khatawas.auth.PrivacyManager
import com.khatawas.db.ChatMessageEntity
import com.khatawas.db.TransactionEntity
import com.khatawas.ui.TransactionCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

class ChatbotQueryPlannerTest {

    private val customerList = mutableListOf<String>()
    private val customerTransactions = mutableMapOf<String, List<TransactionEntity>>()

    private val fakeLedgerDataSource = object : LedgerDatabaseDataSource {
        override fun getAllCustomerNames(): List<String> = customerList
        override fun getTopDebtors(limit: Int): List<Pair<String, Long>> = emptyList()
        override fun getTotalOutstandingBalancePaise(): Long = 0L
    }

    private val fakeUnifiedQueryDataSource = object : UnifiedQueryDataSource {
        override fun getCustomerLedgerUnified(customerName: String, limit: Int): UnifiedQueryResult {
            val txs = customerTransactions[customerName] ?: emptyList()
            var credit = 0L
            var debit = 0L
            for (t in txs) {
                if (t.type.equals("CREDIT", ignoreCase = true)) credit += t.amountPaise
                if (t.type.equals("PAYMENT", ignoreCase = true) || t.type.equals("DEBIT", ignoreCase = true)) debit += t.amountPaise
            }
            val unifiedTxs = txs.map {
                UnifiedTransaction(
                    eventId = it.eventId,
                    customerName = it.customerName,
                    amountPaise = it.amountPaise,
                    type = it.type,
                    timestamp = it.timestamp,
                    note = it.note,
                    source = EventSource.LOCAL
                )
            }
            return UnifiedQueryResult(
                transactions = unifiedTxs,
                totalCreditPaise = credit,
                totalDebitPaise = debit,
                netBalancePaise = credit - debit,
                customerName = customerName,
                freshness = FreshnessDisclosure.LOCAL_ONLY,
                sourceIndicator = "Based on local ledger data (pending cloud sync)."
            )
        }

        override fun getRecentTransactionsUnified(limit: Int): UnifiedQueryResult {
            return UnifiedQueryResult(
                transactions = emptyList(),
                totalCreditPaise = 0L,
                totalDebitPaise = 0L,
                netBalancePaise = 0L,
                freshness = FreshnessDisclosure.LOCAL_ONLY,
                sourceIndicator = "Based on local ledger data."
            )
        }

        override fun getTransactionsByCategoryUnified(category: String, limit: Int): UnifiedQueryResult {
            return UnifiedQueryResult(
                transactions = emptyList(),
                totalCreditPaise = 0L,
                totalDebitPaise = 0L,
                netBalancePaise = 0L,
                freshness = FreshnessDisclosure.LOCAL_ONLY,
                sourceIndicator = "Based on local ledger data."
            )
        }

        override fun getTransactionsByDateRangeUnified(startTimestamp: Long, endTimestamp: Long, dateDesc: String): UnifiedQueryResult {
            return UnifiedQueryResult(
                transactions = emptyList(),
                totalCreditPaise = 0L,
                totalDebitPaise = 0L,
                netBalancePaise = 0L,
                freshness = FreshnessDisclosure.LOCAL_ONLY,
                sourceIndicator = "Based on local ledger data."
            )
        }

        override fun getTotalSummaryUnified(): UnifiedQueryResult {
            return UnifiedQueryResult(
                transactions = emptyList(),
                totalCreditPaise = 0L,
                totalDebitPaise = 0L,
                netBalancePaise = 0L,
                freshness = FreshnessDisclosure.LOCAL_ONLY,
                sourceIndicator = "Based on local ledger data."
            )
        }
    }

    private lateinit var queryPlanner: QueryPlanner

    @Before
    fun setUp() {
        customerList.clear()
        customerTransactions.clear()
        queryPlanner = QueryPlanner(fakeLedgerDataSource, fakeUnifiedQueryDataSource)
        PrivacyManager.lockSession()
    }

    @Test
    fun testCustomerBalanceExactPaiseCheck() {
        // Setup Ravi with 50,000 paise (₹500) credit
        val customer = "Ravi"
        customerList.add(customer)
        val eventId = UUID.randomUUID().toString()
        val tx = TransactionEntity(
            id = 1L,
            eventId = eventId,
            customerName = customer,
            amountPaise = 50000L, // ₹500
            type = "CREDIT",
            timestamp = System.currentTimeMillis(),
            note = "Groceries"
        )
        customerTransactions[customer] = listOf(tx)

        val intent = StructuredQueryIntent(
            intent = "GET_CUSTOMER_BALANCE",
            customerName = customer
        )

        val answer = queryPlanner.planAndExecute(intent, emptyList())

        // Must say exactly ₹500 without rounding errors or LLM arithmetic
        assertTrue("Answer should contain ₹500", answer.answerText.contains("₹500"))
        assertTrue("Answer should state customer owes balance", answer.answerText.contains("Ravi currently owes ₹500"))
        assertEquals(1, answer.retrievedRecordCount)
        assertEquals(listOf(eventId), answer.retrievedEventIds)
        assertFalse(answer.requiresClarification)
    }

    @Test
    fun testUnknownCustomerReturnsGroundedNotFound() {
        customerList.add("Ravi")

        val intent = StructuredQueryIntent(
            intent = "GET_CUSTOMER_BALANCE",
            customerName = "Vikram"
        )

        val answer = queryPlanner.planAndExecute(intent, emptyList())

        assertTrue(
            "Answer should explicitly say customer wasn't found",
            answer.answerText.contains("I couldn't find customer 'Vikram'")
        )
        assertEquals(0, answer.retrievedRecordCount)
        assertTrue(answer.retrievedEventIds.isEmpty())
    }

    @Test
    fun testAmbiguousCustomerTriggersClarification() {
        // Two customers matching "Ravi" prefix
        customerList.add("Ravi Kumar")
        customerList.add("Ravi Sharma")

        val intent = StructuredQueryIntent(
            intent = "GET_CUSTOMER_BALANCE",
            customerName = "Ravi"
        )

        val answer = queryPlanner.planAndExecute(intent, emptyList())

        assertTrue("Should require clarification for ambiguous name", answer.requiresClarification)
        assertTrue(answer.answerText.contains("Which Ravi are you referring to?"))
        assertTrue(answer.answerText.contains("Ravi Kumar"))
        assertTrue(answer.answerText.contains("Ravi Sharma"))
    }

    @Test
    fun testOutOfDomainQueriesTriggerAbstention() {
        val intent = StructuredQueryIntent(
            intent = "UNKNOWN"
        )

        val answer = queryPlanner.planAndExecute(intent, emptyList())

        assertFalse(answer.requiresClarification)
        assertTrue(
            "Assistant must abstain from out-of-domain world knowledge",
            answer.answerText.contains("I can only answer questions about your KHATAWAS financial ledger records")
        )
        assertEquals(0, answer.retrievedRecordCount)
    }

    @Test
    fun testPronounFollowUpResolvesFromConversationContext() {
        val customer = "Suresh"
        customerList.add(customer)
        val eventId = UUID.randomUUID().toString()
        customerTransactions[customer] = listOf(
            TransactionEntity(
                id = 2L,
                eventId = eventId,
                customerName = customer,
                amountPaise = 120000L, // ₹1,200
                type = "CREDIT",
                timestamp = System.currentTimeMillis()
            )
        )

        // Previous context where Suresh was mentioned
        val recentContext = listOf(
            ChatMessageEntity(
                conversationId = "conv_1",
                messageId = "msg_1",
                role = "assistant",
                content = "Suresh currently owes ₹1,200.",
                modality = "TEXT",
                timestamp = System.currentTimeMillis() - 5000L
            )
        )

        // Follow up query using pronoun "he"
        val intent = StructuredQueryIntent(
            intent = "GET_CUSTOMER_BALANCE",
            customerName = "he"
        )

        val answer = queryPlanner.planAndExecute(intent, recentContext)

        assertTrue(
            "Pronoun 'he' should resolve to Suresh from context",
            answer.answerText.contains("Suresh currently owes ₹1,200")
        )
        assertEquals(listOf(eventId), answer.retrievedEventIds)
    }

    @Test
    fun testExactFourCategoriesRetainExactMeanings() {
        assertEquals("CREDIT", TransactionCategory.CREDIT.name)
        assertEquals("DEBIT", TransactionCategory.DEBIT.name)
        assertEquals("STUCK", TransactionCategory.STUCK.name)
        assertEquals("ILLEGAL", TransactionCategory.ILLEGAL.name)

        // Semantic checks
        assertTrue(TransactionCategory.CREDIT.meaning.contains("added"))
        assertTrue(TransactionCategory.DEBIT.meaning.contains("deducted"))
        assertTrue(TransactionCategory.STUCK.meaning.contains("stuck"))
        assertTrue(TransactionCategory.ILLEGAL.meaning.contains("illegal"))
    }

    @Test
    fun testSensitiveAmountsAreMaskedWhenLocked() {
        PrivacyManager.lockSession()
        assertFalse("Session must be locked by default", PrivacyManager.isUnlocked)

        val rawAmount = "₹5,000"
        val masked = PrivacyManager.maskAmount(rawAmount)
        assertEquals("###XXX", masked)

        PrivacyManager.unlockSession()
        assertTrue(PrivacyManager.isUnlocked)
        val unmasked = PrivacyManager.maskAmount(rawAmount)
        assertEquals("₹5,000", unmasked)
    }
}
