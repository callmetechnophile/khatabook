/*
 * Copyright (c) 2026 KHATAWAS Project
 * Simplified Technical English (ASD STE-100) compliant.
 * Authorized Database Query Planner and Grounded Response Generator.
 * Enforces strictly read-only execution, customer disambiguation, and integer paise precision.
 */

package com.khatawas.ai

import android.content.Context
import android.util.Log
import com.khatawas.db.ChatMessageEntity
import com.khatawas.db.LedgerDatabase
import com.khatawas.db.TransactionEntity
import com.khatawas.ui.IndianCurrencyFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class StructuredQueryIntent(
    val intent: String,
    val customerName: String? = null,
    val relation: String = "NONE",
    val dateFilter: String = "NONE",
    val categoryFilter: String = "NONE",
    val requiresClarification: Boolean = false,
    val clarificationPrompt: String = ""
)

data class GroundedChatAnswer(
    val answerText: String,
    val sourceIndicator: String,
    val retrievedRecordCount: Int,
    val retrievedEventIds: List<String>,
    val requiresClarification: Boolean = false,
    val isConflict: Boolean = false
)

interface LedgerDatabaseDataSource {
    fun getAllCustomerNames(): List<String>
    fun getTopDebtors(limit: Int): List<Pair<String, Long>>
    fun getTotalOutstandingBalancePaise(): Long
}

class LedgerDatabaseSourceAdapter(private val db: LedgerDatabase) : LedgerDatabaseDataSource {
    override fun getAllCustomerNames(): List<String> = db.getAllCustomerNames()
    override fun getTopDebtors(limit: Int): List<Pair<String, Long>> = db.getTopDebtors(limit)
    override fun getTotalOutstandingBalancePaise(): Long = db.getTotalOutstandingBalancePaise()
}

interface UnifiedQueryDataSource {
    fun getCustomerLedgerUnified(customerName: String, limit: Int = 50): UnifiedQueryResult
    fun getRecentTransactionsUnified(limit: Int = 50): UnifiedQueryResult
    fun getTransactionsByCategoryUnified(category: String, limit: Int = 50): UnifiedQueryResult
    fun getTransactionsByDateRangeUnified(startTimestamp: Long, endTimestamp: Long, dateDesc: String): UnifiedQueryResult
    fun getTotalSummaryUnified(): UnifiedQueryResult
}

/**
 * Deterministic query planner and grounded answer synthesizer.
 * Intercepts model-extracted structured query intents and maps them to safe,
 * read-only database operations.
 */
class QueryPlanner(
    private val ledgerDataSource: LedgerDatabaseDataSource,
    private val unifiedQueryService: UnifiedQueryDataSource
) {
    // Secondary constructor for Android Activity and Orchestrator integration
    constructor(
        context: Context?,
        ledgerDatabase: LedgerDatabase,
        unifiedQueryService: UnifiedLedgerQueryService
    ) : this(
        ledgerDataSource = LedgerDatabaseSourceAdapter(ledgerDatabase),
        unifiedQueryService = unifiedQueryService
    )

    companion object {
        private const val TAG = "KHATAWAS_QUERY_PLANNER"
        private val DATE_FORMAT = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
    }

    /**
     * Plan and execute a read-only query based on the structured intent and conversation context.
     */
    fun planAndExecute(
        intent: StructuredQueryIntent,
        recentConversationContext: List<ChatMessageEntity>
    ): GroundedChatAnswer {
        // 1. Check for immediate clarification requirement from intent
        if (intent.requiresClarification) {
            val prompt = if (intent.clarificationPrompt.isNotEmpty()) {
                intent.clarificationPrompt
            } else {
                "Could you please specify which customer or transaction you are referring to?"
            }
            return GroundedChatAnswer(
                answerText = prompt,
                sourceIndicator = "Clarification required.",
                retrievedRecordCount = 0,
                retrievedEventIds = emptyList(),
                requiresClarification = true
            )
        }

        // 2. Handle Out-Of-Domain queries with graceful abstention
        if (intent.intent == "UNKNOWN") {
            return GroundedChatAnswer(
                answerText = "I can only answer questions about your KHATAWAS financial ledger records (customers, balances, transactions, and summaries).",
                sourceIndicator = "Out of domain query.",
                retrievedRecordCount = 0,
                retrievedEventIds = emptyList(),
                requiresClarification = false
            )
        }

        // 3. Resolve customer name if this is a follow-up query with pronoun
        var targetCustomerName = intent.customerName
        if (intent.intent == "FOLLOW_UP_QUERY" || isPronounReference(targetCustomerName)) {
            val resolvedName = resolveCustomerFromContext(recentConversationContext)
            if (resolvedName != null) {
                targetCustomerName = resolvedName
            } else if (isPronounReference(targetCustomerName)) {
                return GroundedChatAnswer(
                    answerText = "Which customer are you referring to?",
                    sourceIndicator = "Ambiguous customer reference.",
                    retrievedRecordCount = 0,
                    retrievedEventIds = emptyList(),
                    requiresClarification = true
                )
            }
        }

        // 4. Resolve customer ambiguity against authorized database records
        if (!targetCustomerName.isNullOrEmpty()) {
            val customerDisambiguation = disambiguateCustomer(targetCustomerName, intent.relation)
            if (customerDisambiguation.isAmbiguous) {
                return GroundedChatAnswer(
                    answerText = "Which ${targetCustomerName} are you referring to? (Found: ${customerDisambiguation.matches.joinToString(", ")})",
                    sourceIndicator = "Multiple customer matches found.",
                    retrievedRecordCount = 0,
                    retrievedEventIds = emptyList(),
                    requiresClarification = true
                )
            }
            if (customerDisambiguation.resolvedName != null) {
                targetCustomerName = customerDisambiguation.resolvedName
            }
        }

        // 5. Dispatch to deterministic query handler based on validated intent
        return when (intent.intent) {
            "GET_CUSTOMER_BALANCE" -> executeCustomerBalanceQuery(targetCustomerName)
            "GET_CUSTOMER_HISTORY" -> executeCustomerHistoryQuery(targetCustomerName)
            "GET_CUSTOMER_LAST_PAYMENT" -> executeCustomerLastPaymentQuery(targetCustomerName)
            "LIST_OUTSTANDING_CUSTOMERS" -> executeListOutstandingCustomersQuery()
            "GET_TOTAL_OUTSTANDING" -> executeTotalOutstandingQuery()
            "GET_TOP_DEBTOR" -> executeTopDebtorQuery()
            "GET_PERIOD_SUMMARY" -> executePeriodSummaryQuery(intent.dateFilter, intent.categoryFilter)
            "LIST_CATEGORY_TRANSACTIONS" -> executeCategoryTransactionsQuery(intent.categoryFilter)
            "GET_TRANSACTIONS_BY_DATE" -> executeTransactionsByDateQuery(intent.dateFilter)
            "FOLLOW_UP_QUERY" -> executeFollowUpQuery(targetCustomerName, intent.dateFilter, intent.categoryFilter)
            else -> GroundedChatAnswer(
                answerText = "I couldn't find a matching transaction in your ledger.",
                sourceIndicator = "Query not understood.",
                retrievedRecordCount = 0,
                retrievedEventIds = emptyList(),
                requiresClarification = false
            )
        }
    }

    // --- Query Executors ---

    private fun executeCustomerBalanceQuery(customerName: String?): GroundedChatAnswer {
        if (customerName.isNullOrEmpty()) {
            return GroundedChatAnswer(
                answerText = "Please specify a customer name to check the balance.",
                sourceIndicator = "Missing customer parameter.",
                retrievedRecordCount = 0,
                retrievedEventIds = emptyList(),
                requiresClarification = true
            )
        }

        val result = unifiedQueryService.getCustomerLedgerUnified(customerName)
        if (result.transactions.isEmpty()) {
            return GroundedChatAnswer(
                answerText = "I couldn't find customer '$customerName' or any transactions for them in your ledger.",
                sourceIndicator = result.sourceIndicator,
                retrievedRecordCount = 0,
                retrievedEventIds = emptyList()
            )
        }

        val balanceRupees = IndianCurrencyFormatter.formatPaise(result.netBalancePaise)
        val answer = when {
            result.netBalancePaise > 0 -> "$customerName currently owes $balanceRupees."
            result.netBalancePaise == 0L -> "$customerName is fully paid up with a balance of ₹0."
            else -> "$customerName has an advance credit of ${IndianCurrencyFormatter.formatPaise(-result.netBalancePaise)}."
        }

        val eventIds = result.transactions.map { it.eventId }
        val sourceInfo = "${result.sourceIndicator} (Based on ${result.transactions.size} records)"

        return GroundedChatAnswer(
            answerText = answer,
            sourceIndicator = sourceInfo,
            retrievedRecordCount = result.transactions.size,
            retrievedEventIds = eventIds,
            isConflict = result.freshness == FreshnessDisclosure.HAS_CONFLICTS
        )
    }

    private fun executeCustomerHistoryQuery(customerName: String?): GroundedChatAnswer {
        if (customerName.isNullOrEmpty()) {
            return GroundedChatAnswer(
                answerText = "Please specify a customer name to view their transaction history.",
                sourceIndicator = "Missing customer parameter.",
                retrievedRecordCount = 0,
                retrievedEventIds = emptyList(),
                requiresClarification = true
            )
        }

        val result = unifiedQueryService.getCustomerLedgerUnified(customerName, limit = 5)
        if (result.transactions.isEmpty()) {
            return GroundedChatAnswer(
                answerText = "I couldn't find any recorded transactions for '$customerName' in your ledger.",
                sourceIndicator = result.sourceIndicator,
                retrievedRecordCount = 0,
                retrievedEventIds = emptyList()
            )
        }

        val sb = StringBuilder("Transaction history for $customerName:\n")
        for ((idx, tx) in result.transactions.take(5).withIndex()) {
            val dateStr = DATE_FORMAT.format(Date(tx.timestamp))
            val amtStr = IndianCurrencyFormatter.formatPaise(tx.amountPaise)
            sb.append("${idx + 1}. $dateStr — ${tx.type}: $amtStr")
            if (tx.note.isNotEmpty()) sb.append(" (${tx.note})")
            sb.append("\n")
        }
        val balStr = IndianCurrencyFormatter.formatPaise(result.netBalancePaise)
        sb.append("Current Net Balance: $balStr")

        return GroundedChatAnswer(
            answerText = sb.toString().trim(),
            sourceIndicator = "${result.sourceIndicator} (Showing recent ${result.transactions.size} transactions)",
            retrievedRecordCount = result.transactions.size,
            retrievedEventIds = result.transactions.map { it.eventId },
            isConflict = result.freshness == FreshnessDisclosure.HAS_CONFLICTS
        )
    }

    private fun executeCustomerLastPaymentQuery(customerName: String?): GroundedChatAnswer {
        if (customerName.isNullOrEmpty()) {
            return GroundedChatAnswer(
                answerText = "Please specify a customer name to check their last payment.",
                sourceIndicator = "Missing customer parameter.",
                retrievedRecordCount = 0,
                retrievedEventIds = emptyList(),
                requiresClarification = true
            )
        }

        val result = unifiedQueryService.getCustomerLedgerUnified(customerName)
        val lastPayment = result.transactions.firstOrNull { 
            it.type.equals("PAYMENT", ignoreCase = true) || it.type.equals("DEBIT", ignoreCase = true) 
        }

        if (lastPayment == null) {
            return GroundedChatAnswer(
                answerText = "No payment records found for $customerName in your ledger.",
                sourceIndicator = result.sourceIndicator,
                retrievedRecordCount = 0,
                retrievedEventIds = emptyList()
            )
        }

        val dateStr = DATE_FORMAT.format(Date(lastPayment.timestamp))
        val amtStr = IndianCurrencyFormatter.formatPaise(lastPayment.amountPaise)
        val answer = "$customerName's last payment was $amtStr on $dateStr."

        return GroundedChatAnswer(
            answerText = answer,
            sourceIndicator = result.sourceIndicator,
            retrievedRecordCount = 1,
            retrievedEventIds = listOf(lastPayment.eventId)
        )
    }

    private fun executeListOutstandingCustomersQuery(): GroundedChatAnswer {
        val topDebtors = ledgerDataSource.getTopDebtors(10).filter { it.second > 0 }
        if (topDebtors.isEmpty()) {
            return GroundedChatAnswer(
                answerText = "All customers are fully paid up. There are no outstanding balances.",
                sourceIndicator = "Based on local and cloud ledger data.",
                retrievedRecordCount = 0,
                retrievedEventIds = emptyList()
            )
        }

        val sb = StringBuilder("Customers with outstanding balances:\n")
        for ((name, balancePaise) in topDebtors) {
            val amtStr = IndianCurrencyFormatter.formatPaise(balancePaise)
            sb.append("• $name: $amtStr\n")
        }

        return GroundedChatAnswer(
            answerText = sb.toString().trim(),
            sourceIndicator = "Based on authorized customer ledger records (${topDebtors.size} customers).",
            retrievedRecordCount = topDebtors.size,
            retrievedEventIds = emptyList()
        )
    }

    private fun executeTotalOutstandingQuery(): GroundedChatAnswer {
        val totalPaise = ledgerDataSource.getTotalOutstandingBalancePaise()
        val formattedRupees = IndianCurrencyFormatter.formatPaise(totalPaise)
        val answer = "Your total outstanding balance across all customers is $formattedRupees."

        return GroundedChatAnswer(
            answerText = answer,
            sourceIndicator = "Calculated deterministically from deduplicated customer records.",
            retrievedRecordCount = 1,
            retrievedEventIds = emptyList()
        )
    }

    private fun executeTopDebtorQuery(): GroundedChatAnswer {
        val top = ledgerDataSource.getTopDebtors(1).firstOrNull()
        if (top == null || top.second <= 0) {
            return GroundedChatAnswer(
                answerText = "There are no customers with outstanding balances right now.",
                sourceIndicator = "Based on ledger records.",
                retrievedRecordCount = 0,
                retrievedEventIds = emptyList()
            )
        }

        val amtStr = IndianCurrencyFormatter.formatPaise(top.second)
        val answer = "${top.first} has the highest outstanding balance at $amtStr."

        return GroundedChatAnswer(
            answerText = answer,
            sourceIndicator = "Based on customer balance aggregation.",
            retrievedRecordCount = 1,
            retrievedEventIds = emptyList()
        )
    }

    private fun executePeriodSummaryQuery(dateFilter: String, categoryFilter: String): GroundedChatAnswer {
        val (startTs, endTs, periodDesc) = computeDateRange(dateFilter)
        val result = unifiedQueryService.getTransactionsByDateRangeUnified(startTs, endTs, periodDesc)

        if (result.transactions.isEmpty()) {
            return GroundedChatAnswer(
                answerText = "No transactions were recorded $periodDesc.",
                sourceIndicator = result.sourceIndicator,
                retrievedRecordCount = 0,
                retrievedEventIds = emptyList()
            )
        }

        val creditRupees = IndianCurrencyFormatter.formatPaise(result.totalCreditPaise)
        val debitRupees = IndianCurrencyFormatter.formatPaise(result.totalDebitPaise)

        val answer = when (categoryFilter) {
            "CREDIT" -> "Credit issued $periodDesc: $creditRupees across ${result.transactions.count { it.type == "CREDIT" }} transaction(s)."
            "DEBIT" -> "Money received $periodDesc: $debitRupees across ${result.transactions.count { it.type == "PAYMENT" || it.type == "DEBIT" }} payment(s)."
            else -> "Summary for $periodDesc: Received $debitRupees, Credit Issued $creditRupees across ${result.transactions.size} total transaction(s)."
        }

        return GroundedChatAnswer(
            answerText = answer,
            sourceIndicator = "${result.sourceIndicator} (Based on ${result.transactions.size} transactions $periodDesc)",
            retrievedRecordCount = result.transactions.size,
            retrievedEventIds = result.transactions.map { it.eventId },
            isConflict = result.freshness == FreshnessDisclosure.HAS_CONFLICTS
        )
    }

    private fun executeCategoryTransactionsQuery(category: String): GroundedChatAnswer {
        val result = unifiedQueryService.getTransactionsByCategoryUnified(category)
        if (result.transactions.isEmpty()) {
            return GroundedChatAnswer(
                answerText = "I couldn't find any $category transactions in your ledger.",
                sourceIndicator = result.sourceIndicator,
                retrievedRecordCount = 0,
                retrievedEventIds = emptyList()
            )
        }

        val sb = StringBuilder("Found ${result.transactions.size} $category transaction(s):\n")
        for ((idx, tx) in result.transactions.take(5).withIndex()) {
            val dateStr = DATE_FORMAT.format(Date(tx.timestamp))
            val amtStr = IndianCurrencyFormatter.formatPaise(tx.amountPaise)
            sb.append("${idx + 1}. ${tx.customerName}: $amtStr on $dateStr")
            if (tx.note.isNotEmpty()) sb.append(" [${tx.note}]")
            sb.append("\n")
        }

        return GroundedChatAnswer(
            answerText = sb.toString().trim(),
            sourceIndicator = "${result.sourceIndicator} (Listing $category category)",
            retrievedRecordCount = result.transactions.size,
            retrievedEventIds = result.transactions.map { it.eventId },
            isConflict = result.freshness == FreshnessDisclosure.HAS_CONFLICTS
        )
    }

    private fun executeTransactionsByDateQuery(dateFilter: String): GroundedChatAnswer {
        val (startTs, endTs, periodDesc) = computeDateRange(dateFilter)
        val result = unifiedQueryService.getTransactionsByDateRangeUnified(startTs, endTs, periodDesc)

        if (result.transactions.isEmpty()) {
            return GroundedChatAnswer(
                answerText = "No transactions were recorded $periodDesc.",
                sourceIndicator = result.sourceIndicator,
                retrievedRecordCount = 0,
                retrievedEventIds = emptyList()
            )
        }

        val sb = StringBuilder("Transactions recorded $periodDesc (${result.transactions.size} total):\n")
        for ((idx, tx) in result.transactions.take(6).withIndex()) {
            val amtStr = IndianCurrencyFormatter.formatPaise(tx.amountPaise)
            sb.append("${idx + 1}. ${tx.customerName} — ${tx.type}: $amtStr\n")
        }

        return GroundedChatAnswer(
            answerText = sb.toString().trim(),
            sourceIndicator = "${result.sourceIndicator} (Based on ${result.transactions.size} records $periodDesc)",
            retrievedRecordCount = result.transactions.size,
            retrievedEventIds = result.transactions.map { it.eventId },
            isConflict = result.freshness == FreshnessDisclosure.HAS_CONFLICTS
        )
    }

    private fun executeFollowUpQuery(customerName: String?, dateFilter: String, categoryFilter: String): GroundedChatAnswer {
        if (!customerName.isNullOrEmpty()) {
            return executeCustomerLedgerFollowUp(customerName, dateFilter)
        }
        if (dateFilter != "NONE") {
            return executePeriodSummaryQuery(dateFilter, categoryFilter)
        }
        return GroundedChatAnswer(
            answerText = "Could you please specify which customer or period you would like to follow up on?",
            sourceIndicator = "Follow-up needs clarification.",
            retrievedRecordCount = 0,
            retrievedEventIds = emptyList(),
            requiresClarification = true
        )
    }

    private fun executeCustomerLedgerFollowUp(customerName: String, dateFilter: String): GroundedChatAnswer {
        val result = unifiedQueryService.getCustomerLedgerUnified(customerName)
        val filtered = if (dateFilter != "NONE") {
            val (startTs, endTs, _) = computeDateRange(dateFilter)
            result.transactions.filter { it.timestamp in startTs..endTs }
        } else {
            result.transactions
        }

        if (filtered.isEmpty()) {
            return GroundedChatAnswer(
                answerText = "No transactions found for $customerName in the specified period.",
                sourceIndicator = result.sourceIndicator,
                retrievedRecordCount = 0,
                retrievedEventIds = emptyList()
            )
        }

        val sb = StringBuilder("Previous transactions for $customerName:\n")
        for ((idx, tx) in filtered.take(5).withIndex()) {
            val dateStr = DATE_FORMAT.format(Date(tx.timestamp))
            val amtStr = IndianCurrencyFormatter.formatPaise(tx.amountPaise)
            sb.append("${idx + 1}. $dateStr — ${tx.type}: $amtStr\n")
        }

        return GroundedChatAnswer(
            answerText = sb.toString().trim(),
            sourceIndicator = "${result.sourceIndicator} (Follow-up context)",
            retrievedRecordCount = filtered.size,
            retrievedEventIds = filtered.map { it.eventId }
        )
    }

    // --- Helper Methods ---

    private fun isPronounReference(name: String?): Boolean {
        if (name == null) return false
        val lower = name.trim().lowercase(Locale.ROOT)
        return lower in listOf("he", "him", "his", "her", "she", "uske", "uska", "unki", "unka", "avara", "avanu", "avalu")
    }

    private fun resolveCustomerFromContext(context: List<ChatMessageEntity>): String? {
        // Search backwards in conversation history for mentioned customer names
        val allCustomers = ledgerDataSource.getAllCustomerNames()
        for (msg in context.reversed()) {
            for (cust in allCustomers) {
                if (msg.content.contains(cust, ignoreCase = true)) {
                    return cust
                }
            }
        }
        return null
    }

    data class DisambiguationResult(
        val resolvedName: String?,
        val isAmbiguous: Boolean,
        val matches: List<String>
    )

    private fun disambiguateCustomer(queryName: String, relation: String): DisambiguationResult {
        val allCustomers = ledgerDataSource.getAllCustomerNames()
        val trimmed = queryName.trim()

        // Exact match
        for (c in allCustomers) {
            if (c.equals(trimmed, ignoreCase = true)) {
                return DisambiguationResult(resolvedName = c, isAmbiguous = false, matches = listOf(c))
            }
        }

        // Relationship-aware exact match (e.g. "Ravi's father" or "Ravi ke papa")
        if (relation != "NONE") {
            for (c in allCustomers) {
                if (c.contains(trimmed, ignoreCase = true) && 
                    (c.contains("papa", ignoreCase = true) || c.contains("father", ignoreCase = true) || c.contains("thande", ignoreCase = true))) {
                    return DisambiguationResult(resolvedName = c, isAmbiguous = false, matches = listOf(c))
                }
            }
        }

        // Substring / prefix matches
        val prefixMatches = allCustomers.filter { it.startsWith(trimmed, ignoreCase = true) }
        if (prefixMatches.size == 1) {
            return DisambiguationResult(resolvedName = prefixMatches[0], isAmbiguous = false, matches = prefixMatches)
        } else if (prefixMatches.size > 1) {
            return DisambiguationResult(resolvedName = null, isAmbiguous = true, matches = prefixMatches)
        }

        val containsMatches = allCustomers.filter { it.contains(trimmed, ignoreCase = true) }
        if (containsMatches.size == 1) {
            return DisambiguationResult(resolvedName = containsMatches[0], isAmbiguous = false, matches = containsMatches)
        } else if (containsMatches.size > 1) {
            return DisambiguationResult(resolvedName = null, isAmbiguous = true, matches = containsMatches)
        }

        // Fallback: return as-is
        return DisambiguationResult(resolvedName = trimmed, isAmbiguous = false, matches = emptyList())
    }

    private fun computeDateRange(filter: String): Triple<Long, Long, String> {
        val now = System.currentTimeMillis()
        val millisPerDay = 86_400_000L
        val currentDayStart = (now / millisPerDay) * millisPerDay

        return when (filter.uppercase(Locale.ROOT)) {
            "TODAY" -> Triple(currentDayStart, now, "today")
            "YESTERDAY" -> Triple(currentDayStart - millisPerDay, currentDayStart - 1, "yesterday")
            "THIS_WEEK" -> Triple(now - 7 * millisPerDay, now, "this week")
            "THIS_MONTH" -> Triple(now - 30 * millisPerDay, now, "this month")
            "LAST_MONTH" -> Triple(now - 60 * millisPerDay, now - 30 * millisPerDay, "last month")
            else -> Triple(0L, now, "in recorded history")
        }
    }
}
