/*
 * Copyright (c) 2026 KHATAWAS Project
 * Simplified Technical English (ASD STE-100) compliant.
 * Deterministic natural language financial parser and entity extractor for Kirana voice ledger.
 * Supports multilingual Hindi, Hinglish, English, and Kannada terms.
 * All monetary amounts are represented as 64-bit integer paise (1 Rupee = 100 Paise).
 */

package com.khatawas.logic

import java.util.Locale

/**
 * Direction of a ledger entry.
 */
enum class TransactionDirection {
    CREDIT,  // Goods given on credit (udhaar / baaki)
    PAYMENT  // Money received from customer (jama / diya)
}

/**
 * Data structure representing a parsed ledger transaction.
 * All monetary amounts are stored in integer paise (Long).
 */
data class Transaction(
    val name: String,
    val amountPaise: Long,
    val type: String, // "CREDIT" or "PAYMENT"
    val dateOffset: Int = 0
) {
    // Backward-compatible rupee integer
    val amount: Int get() = (amountPaise / 100).toInt()
    val amountRupees: Double get() = amountPaise / 100.0

    constructor(name: String, amountRupees: Int, type: String, dateOffset: Int = 0) :
            this(name, amountRupees.toLong() * 100L, type, dateOffset)
}

/**
 * Structured parsing result with explicit ambiguity and intent reporting.
 */
sealed class ParseResult {
    data class Success(val transactions: List<Transaction>) : ParseResult()
    data class Correction(val newAmountPaise: Long) : ParseResult()
    data class Query(val customerName: String, val queryType: String) : ParseResult()
    data class Ambiguous(val reason: String, val clarificationQuestion: String) : ParseResult()
    data class Failure(val errorMessage: String) : ParseResult()
}

/**
 * Deterministic Kirana ledger parser.
 * Distinguishes credit vs payment without guessing, supports multi-clause compound sentences,
 * multi-word Hindi/English numbers, relative dates, and corrections.
 */
object LedgerParser {

    private val DIGIT_REGEX = Regex("\\b\\d+(?:\\.\\d{1,2})?\\b")

    // Keywords indicating CREDIT (money owed to shopkeeper)
    private val CREDIT_KEYWORDS = setOf(
        "udhaar", "udhar", "baaki", "baki", "credit", "likh", "likho",
        "samaan", "samaano", "dena", "saala", "kadam", "bakiye"
    )

    // Keywords indicating PAYMENT (money paid to shopkeeper)
    private val PAYMENT_KEYWORDS = setOf(
        "jama", "diya", "diye", "payment", "paid", "mila", "mile", "aaye",
        "vasool", "kottidhaare", "pavadhi", "received", "chuka", "chukaye"
    )

    // Relative date mapping
    private val DATE_MAP = mapOf(
        "aaj" to 0,
        "today" to 0,
        "kal" to -1,
        "yesterday" to -1,
        "parso" to -2,
        "pichhle hafte" to -7,
        "last week" to -7
    )

    // Stopwords and particles that must never be mistaken for customer names
    private val RESERVED_WORDS = setOf(
        "aaj", "kal", "parso", "today", "yesterday", "rupaye", "rupiya", "rupee", "rupees", "rs",
        "ka", "ke", "ki", "ko", "ne", "se", "wala", "wali", "tha", "thi", "the", "hai", "hain",
        "udhaar", "udhar", "jama", "diya", "diye", "baaki", "payment", "paid", "bhi", "aur", "and",
        "nahi", "no", "not", "samaan", "hisaab", "hisab", "kitna", "batao", "saala"
    )

    // Hindi & English number words to base units
    private val NUMBER_WORDS = mapOf(
        "zero" to 0L, "shunya" to 0L,
        "ek" to 1L, "one" to 1L,
        "do" to 2L, "two" to 2L,
        "teen" to 3L, "three" to 3L,
        "chaar" to 4L, "four" to 4L,
        "paanch" to 5L, "five" to 5L,
        "che" to 6L, "chheh" to 6L, "six" to 6L,
        "saat" to 7L, "seven" to 7L,
        "aath" to 8L, "eight" to 8L,
        "nau" to 9L, "nine" to 9L,
        "das" to 10L, "ten" to 10L,
        "gyaarah" to 11L, "eleven" to 11L,
        "baarah" to 12L, "twelve" to 12L,
        "terah" to 13L, "thirteen" to 13L,
        "chaudah" to 14L, "fourteen" to 14L,
        "pandrah" to 15L, "fifteen" to 15L,
        "solah" to 16L, "sixteen" to 16L,
        "satrah" to 17L, "seventeen" to 17L,
        "athaarah" to 18L, "eighteen" to 18L,
        "unnees" to 19L, "nineteen" to 19L,
        "bees" to 20L, "twenty" to 20L,
        "pachchees" to 25L,
        "tees" to 30L, "thirty" to 30L,
        "paintees" to 35L,
        "chaalis" to 40L, "chalis" to 40L, "forty" to 40L,
        "paintalees" to 45L,
        "pachaas" to 50L, "fifty" to 50L,
        "saath" to 60L, "sixty" to 60L,
        "sattar" to 70L, "seventy" to 70L,
        "assi" to 80L, "eighty" to 80L,
        "nabbe" to 90L, "ninety" to 90L,
        "sau" to 100L, "hundred" to 100L,
        "hazaar" to 1000L, "hazar" to 1000L, "thousand" to 1000L,
        "lakh" to 100000L, "lac" to 100000L
    )

    /**
     * Backward-compatible parse function returning first Transaction or null.
     */
    fun parseUtterance(text: String): Transaction? {
        val structured = parseStructured(text)
        return when (structured) {
            is ParseResult.Success -> structured.transactions.firstOrNull()
            else -> null
        }
    }

    /**
     * Parse full utterance into a structured ParseResult.
     */
    fun parseStructured(text: String): ParseResult {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return ParseResult.Failure("Empty utterance")
        }

        val lower = trimmed.lowercase(Locale.ROOT)

        // 1. Correction detection: "Nahi, 430" / "Nahi chaar sau tees"
        if (lower.startsWith("nahi") || lower.startsWith("no ") || lower.startsWith("galat")) {
            val amount = extractAmountPaise(lower)
            if (amount != null && amount > 0L) {
                return ParseResult.Correction(amount)
            }
            return ParseResult.Ambiguous("Correction without valid amount", "Kitna amount likhna hai?")
        }

        // 2. Query detection: "Ramesh ka hisaab batao" / "kitna baaki hai"
        if (lower.contains("kitna") || lower.contains("hisaab") || lower.contains("balance") || lower.contains("batao")) {
            val customer = extractCustomerName(trimmed)
            if (customer != null) {
                return ParseResult.Query(customer, "BALANCE")
            }
        }

        // 3. Multi-clause split: handles compound sentences like:
        // "Ramesh ka 340 udhaar, kal 200 diya tha"
        val clauses = splitClauses(trimmed)
        val defaultCustomer = extractCustomerName(trimmed)

        if (defaultCustomer == null) {
            return ParseResult.Ambiguous("Missing customer name", "Kiska hisaab likhna hai?")
        }

        val parsedTransactions = ArrayList<Transaction>()

        for (clause in clauses) {
            val clauseLower = clause.lowercase(Locale.ROOT)
            val amountPaise = extractAmountPaise(clauseLower) ?: continue

            // Determine direction explicitly — never guess or default to CREDIT
            val isCredit = CREDIT_KEYWORDS.any { clauseLower.contains(it) }
            val isPayment = PAYMENT_KEYWORDS.any { clauseLower.contains(it) }

            if (!isCredit && !isPayment) {
                return ParseResult.Ambiguous(
                    "Direction ambiguous for amount ₹${amountPaise / 100}",
                    "Kya ₹${amountPaise / 100} udhaar hai ya jama mila?"
                )
            }

            val direction = if (isCredit && !isPayment) {
                TransactionDirection.CREDIT
            } else if (isPayment && !isCredit) {
                TransactionDirection.PAYMENT
            } else {
                // Both words present in single clause: evaluate phrasing priority
                if (clauseLower.indexOf("udhaar") < clauseLower.indexOf("diya") ||
                    clauseLower.indexOf("baaki") < clauseLower.indexOf("jama")) {
                    TransactionDirection.CREDIT
                } else {
                    TransactionDirection.PAYMENT
                }
            }

            // Extract clause-specific date offset or inherit 0
            val dateOffset = extractDateOffset(clauseLower)

            parsedTransactions.add(
                Transaction(
                    name = defaultCustomer,
                    amountPaise = amountPaise,
                    type = direction.name,
                    dateOffset = dateOffset
                )
            )
        }

        if (parsedTransactions.isEmpty()) {
            return ParseResult.Ambiguous("Missing amount", "Kitne rupaye ka transaction hai?")
        }

        return ParseResult.Success(parsedTransactions)
    }

    /**
     * Splits compound sentences on commas, conjunctions ("aur", "and"), or clause boundaries.
     */
    private fun splitClauses(text: String): List<String> {
        val delimiters = Regex("[,;]|\\b(?:aur|and|lekin|magar)\\b", RegexOption.IGNORE_CASE)
        val parts = text.split(delimiters).map { it.trim() }.filter { it.isNotEmpty() }
        return if (parts.isNotEmpty()) parts else listOf(text)
    }

    /**
     * Extract customer name avoiding date words, directions, numbers, and stopwords.
     */
    fun extractCustomerName(text: String): String? {
        val tokens = text.trim().split(Regex("[\\s,;]+"))
        for (i in tokens.indices) {
            val token = tokens[i]
            val lower = token.lowercase(Locale.ROOT)
            if (RESERVED_WORDS.contains(lower) || NUMBER_WORDS.containsKey(lower) || DIGIT_REGEX.matches(token)) {
                continue
            }
            if (token.length < 2) continue

            // Found valid non-reserved token
            return token.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
            }
        }
        return null
    }

    /**
     * Extract date offset from clause tokens.
     */
    private fun extractDateOffset(clauseLower: String): Int {
        for ((phrase, offset) in DATE_MAP) {
            if (clauseLower.contains(phrase)) {
                return offset
            }
        }
        return 0
    }

    /**
     * Extract monetary amount in integer paise (Long).
     * 1 Rupee = 100 Paise.
     */
    fun extractAmountPaise(clauseLower: String): Long? {
        // 1. Check numeric digits
        val digitMatch = DIGIT_REGEX.find(clauseLower)
        if (digitMatch != null) {
            val floatVal = digitMatch.value.toDoubleOrNull()
            if (floatVal != null && floatVal > 0.0) {
                return Math.round(floatVal * 100.0)
            }
        }

        // 2. Parse multi-word Hindi & English number expressions
        val tokens = clauseLower.split(Regex("[\\s,;]+"))
        var totalRupees = 0L
        var currentChunk = 0L
        var foundNumber = false

        for (token in tokens) {
            val unit = NUMBER_WORDS[token] ?: continue
            foundNumber = true

            when (unit) {
                100L -> {
                    val multiplier = if (currentChunk == 0L) 1L else currentChunk
                    totalRupees += multiplier * 100L
                    currentChunk = 0L
                }
                1000L -> {
                    val multiplier = if (currentChunk == 0L) 1L else currentChunk
                    totalRupees += multiplier * 1000L
                    currentChunk = 0L
                }
                100000L -> {
                    val multiplier = if (currentChunk == 0L) 1L else currentChunk
                    totalRupees += multiplier * 100000L
                    currentChunk = 0L
                }
                else -> {
                    currentChunk += unit
                }
            }
        }

        totalRupees += currentChunk
        return if (foundNumber && totalRupees > 0L) totalRupees * 100L else null
    }
}
