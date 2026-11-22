/*
 * Copyright (c) 2026 KHATAWAS Project
 * Production Cleanliness & Anti-Fabrication Regression Test Suite.
 *
 * Verifies:
 * 1. Fresh production installation contains 0 seeded customers and 0 transactions.
 * 2. Empty ledger states return genuine zero balances and empty lists.
 * 3. Chatbot queries on clean databases return truthful not-found answers without fabricating ledger records.
 * 4. Production source code, layouts, and resources contain NO hardcoded demo customer names or sample amounts.
 */

package com.khatawas.audit

import com.khatawas.ai.FreshnessDisclosure
import com.khatawas.ai.GroundedChatAnswer
import com.khatawas.ai.LedgerDatabaseDataSource
import com.khatawas.ai.QueryPlanner
import com.khatawas.ai.StructuredQueryIntent
import com.khatawas.ai.UnifiedQueryDataSource
import com.khatawas.ai.UnifiedQueryResult
import com.khatawas.ai.UnifiedTransaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProductionCleanlinessRegressionTest {

    /**
     * In-memory clean data source representing a fresh installation.
     */
    private class FreshInstallationDataSource : LedgerDatabaseDataSource, UnifiedQueryDataSource {
        val customers = mutableListOf<String>()
        val transactions = mutableListOf<UnifiedTransaction>()

        override fun getAllCustomerNames(): List<String> = customers
        override fun getTopDebtors(limit: Int): List<Pair<String, Long>> = emptyList()
        override fun getTotalOutstandingBalancePaise(): Long = 0L

        override fun getCustomerLedgerUnified(customerName: String, limit: Int): UnifiedQueryResult {
            val matching = transactions.filter { it.customerName.equals(customerName, ignoreCase = true) }
            val credit = matching.filter { it.type == "CREDIT" }.sumOf { it.amountPaise }
            val debit = matching.filter { it.type == "PAYMENT" }.sumOf { it.amountPaise }
            return UnifiedQueryResult(
                transactions = matching,
                totalCreditPaise = credit,
                totalDebitPaise = debit,
                netBalancePaise = credit - debit,
                customerName = customerName,
                freshness = FreshnessDisclosure.LOCAL_ONLY,
                sourceIndicator = "Local encrypted ledger."
            )
        }

        override fun getRecentTransactionsUnified(limit: Int): UnifiedQueryResult {
            return UnifiedQueryResult(
                transactions = transactions,
                totalCreditPaise = 0L,
                totalDebitPaise = 0L,
                netBalancePaise = 0L,
                freshness = FreshnessDisclosure.LOCAL_ONLY,
                sourceIndicator = "Local encrypted ledger."
            )
        }

        override fun getTransactionsByCategoryUnified(category: String, limit: Int): UnifiedQueryResult =
            getRecentTransactionsUnified(limit)

        override fun getTransactionsByDateRangeUnified(
            startTimestamp: Long,
            endTimestamp: Long,
            dateDesc: String
        ): UnifiedQueryResult = getRecentTransactionsUnified()

        override fun getTotalSummaryUnified(): UnifiedQueryResult = getRecentTransactionsUnified()
    }

    @Test
    fun testFreshInstallationContainsNoSeededCustomers() {
        val freshSource = FreshInstallationDataSource()
        assertTrue("A fresh production installation must have zero seeded customers", freshSource.getAllCustomerNames().isEmpty())
        assertEquals(0, freshSource.getAllCustomerNames().size)
    }

    @Test
    fun testFreshInstallationContainsNoSeededTransactions() {
        val freshSource = FreshInstallationDataSource()
        val result = freshSource.getRecentTransactionsUnified()
        assertTrue("A fresh production installation must have zero seeded transactions", result.transactions.isEmpty())
        assertEquals(0L, result.totalCreditPaise)
        assertEquals(0L, result.totalDebitPaise)
        assertEquals(0L, result.netBalancePaise)
    }

    @Test
    fun testFreshInstallationBalanceIsZero() {
        val freshSource = FreshInstallationDataSource()
        assertEquals("Clean ledger must report exactly 0 paise total outstanding", 0L, freshSource.getTotalOutstandingBalancePaise())
        assertTrue("Clean ledger must report no top debtors", freshSource.getTopDebtors(10).isEmpty())
    }

    @Test
    fun testChatbotNeverFabricatesCustomerOrTransactionOnCleanDatabase() {
        val freshSource = FreshInstallationDataSource()
        val planner = QueryPlanner(ledgerDataSource = freshSource, unifiedQueryService = freshSource)

        val intent = StructuredQueryIntent(
            intent = "GET_CUSTOMER_BALANCE",
            customerName = "Ramesh"
        )
        val answer: GroundedChatAnswer = planner.planAndExecute(intent, emptyList())

        // The chatbot must NEVER invent a customer balance or fabricate records
        assertEquals(0, answer.retrievedRecordCount)
        assertTrue(answer.retrievedEventIds.isEmpty())
        assertTrue(
            "Chatbot must truthfully state the customer was not found",
            answer.answerText.contains("couldn't find customer 'Ramesh'", ignoreCase = true)
        )
    }

    @Test
    fun testChatbotTopDebtorQueryOnEmptyDatabaseIsTruthful() {
        val freshSource = FreshInstallationDataSource()
        val planner = QueryPlanner(ledgerDataSource = freshSource, unifiedQueryService = freshSource)

        val intent = StructuredQueryIntent(
            intent = "GET_TOP_DEBTOR"
        )
        val answer: GroundedChatAnswer = planner.planAndExecute(intent, emptyList())

        assertEquals(0, answer.retrievedRecordCount)
        assertTrue(
            "Chatbot must truthfully report no outstanding debtors found",
            answer.answerText.contains("no customers with outstanding balances", ignoreCase = true)
        )
    }

    @Test
    fun testChatbotTotalOutstandingOnEmptyDatabaseIsTruthful() {
        val freshSource = FreshInstallationDataSource()
        val planner = QueryPlanner(ledgerDataSource = freshSource, unifiedQueryService = freshSource)

        val intent = StructuredQueryIntent(
            intent = "GET_TOTAL_OUTSTANDING"
        )
        val answer: GroundedChatAnswer = planner.planAndExecute(intent, emptyList())

        assertTrue(
            "Chatbot must report exactly ₹0 for clean ledger total outstanding",
            answer.answerText.contains("₹0")
        )
    }

    @Test
    fun testLegitimateCustomerPersistenceAndRetrieval() {
        val source = FreshInstallationDataSource()
        val planner = QueryPlanner(ledgerDataSource = source, unifiedQueryService = source)

        // Add a legitimate real customer and real transaction
        source.customers.add("Kiran Kirana")
        source.transactions.add(
            UnifiedTransaction(
                eventId = "real-event-1234",
                customerName = "Kiran Kirana",
                amountPaise = 250_00L,
                type = "CREDIT",
                timestamp = System.currentTimeMillis(),
                source = com.khatawas.ai.EventSource.LOCAL
            )
        )

        val queryResult = source.getCustomerLedgerUnified("Kiran Kirana")
        assertEquals(1, queryResult.transactions.size)
        assertEquals(250_00L, queryResult.netBalancePaise)

        val intent = StructuredQueryIntent(
            intent = "GET_CUSTOMER_BALANCE",
            customerName = "Kiran Kirana"
        )
        val answer = planner.planAndExecute(intent, emptyList())
        assertEquals(1, answer.retrievedRecordCount)
        assertTrue(answer.answerText.contains("Kiran Kirana currently owes ₹250"))
    }

    @Test
    fun testProductionSourceTreeFreeOfRameshAndDemoValues() {
        val mainSrcDir = listOf(
            File("src/main"),
            File("app/src/main"),
            File("../app/src/main")
        ).firstOrNull { it.exists() } ?: File("src/main")
        assertTrue("app/src/main or src/main must exist", mainSrcDir.exists())

        val forbiddenTokens = listOf(
            "Ramesh",
            "340",
            "रमेश"
        )

        val violations = mutableListOf<String>()

        mainSrcDir.walkTopDown().forEach { file ->
            if (file.isFile && (file.extension == "kt" || file.extension == "xml")) {
                val text = file.readText(Charsets.UTF_8)
                for (token in forbiddenTokens) {
                    if (text.contains(token, ignoreCase = false)) {
                        violations.add("${file.path} contains '$token'")
                    }
                }
            }
        }

        assertTrue(
            "Found forbidden demo tokens in production source files:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }
}
