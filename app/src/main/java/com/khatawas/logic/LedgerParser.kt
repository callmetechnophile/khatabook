/*
 * Copyright (c) 2026 KHATAWAS Project
 * Simplified Technical English (ASD STE-100) compliant.
 * Local deterministic natural language parser and Finite State Machine (FSM).
 */

package com.khatawas.logic

import java.util.Locale

/**
 * Data structure representing a parsed ledger transaction.
 * Property name: The customer name.
 * Property amount: The transaction monetary amount in integer rupees.
 * Property type: "CREDIT" (udhaar) or "DEBIT" (payment received/jama).
 * Property dateOffset: Days relative to current date (e.g. 0 = today, -1 = yesterday).
 */
data class Transaction(
    val name: String,
    val amount: Int,
    val type: String,
    val dateOffset: Int = 0
)

/**
 * Deterministic ledger parser.
 * This class parses spoken Hinglish and Hindi Kirana store utterances.
 * It uses regular expressions and a deterministic state machine.
 * It does not use external cloud language models.
 */
object LedgerParser {

    // Regular expression to match numeric digit sequences
    private val DIGIT_REGEX = Regex("\\d+")

    // Mapping of code-mixed terms to database transaction types
    private val TYPE_DICTIONARY = hashMapOf(
        "udhaar" to "CREDIT",
        "credit" to "CREDIT",
        "dena" to "CREDIT",
        "diya" to "DEBIT",
        "payment" to "DEBIT",
        "lena" to "DEBIT",
        "jama" to "DEBIT"
    )

    // Mapping of relative date terms to integer day offsets
    private val DATE_DICTIONARY = hashMapOf(
        "kal" to -1,
        "parso" to -2,
        "aaj" to 0
    )

    // Fallback dictionary for basic Hindi number words
    private val HINDI_NUMBERS = hashMapOf(
        "shunya" to 0, "zero" to 0,
        "ek" to 1, "do" to 2, "teen" to 3, "chaar" to 4, "paanch" to 5,
        "che" to 6, "saat" to 7, "aath" to 8, "nau" to 9, "das" to 10,
        "gyaarah" to 11, "baarah" to 12, "terah" to 13, "chaudah" to 14, "pandrah" to 15,
        "solah" to 16, "satrah" to 17, "athaarah" to 18, "unnees" to 19, "bees" to 20,
        "pachchees" to 25, "tees" to 30, "paintees" to 35, "chaalis" to 40,
        "paintalees" to 45, "pachaas" to 50, "saath" to 60, "sattar" to 70,
        "assi" to 80, "nabbe" to 90, "sau" to 100, "hazaar" to 1000
    )

    /**
     * Parse a natural speech utterance into a structured Transaction.
     * Parameter text: Raw speech recognition transcript.
     * Returns Transaction if amount and intent are resolved, null otherwise.
     */
    fun parseUtterance(text: String): Transaction? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return null
        }

        val tokens = trimmed.split(Regex("\\s+"))
        if (tokens.isEmpty()) {
            return null
        }

        // 1. Extract amount using Regex "\\d+". If not found, use Hindi number fallback.
        val amount = extractAmount(tokens) ?: return null

        // 2. Extract transaction type using HashMap. Default to "CREDIT".
        var transactionType = "CREDIT"
        for (token in tokens) {
            val lower = token.lowercase(Locale.ROOT)
            if (TYPE_DICTIONARY.containsKey(lower)) {
                transactionType = TYPE_DICTIONARY[lower]!!
                break
            }
        }

        // 3. Extract relative date offset using HashMap. Default to 0 (today).
        var dateOffset = 0
        for (token in tokens) {
            val lower = token.lowercase(Locale.ROOT)
            if (DATE_DICTIONARY.containsKey(lower)) {
                dateOffset = DATE_DICTIONARY[lower]!!
                break
            }
        }

        // 4. Extract customer name: first word of the utterance.
        val rawName = trimmed.split(" ")[0]
        val customerName = rawName.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
        }

        return Transaction(
            name = customerName,
            amount = amount,
            type = transactionType,
            dateOffset = dateOffset
        )
    }

    /**
     * Extract integer amount from tokens using regex first, then Hindi number words.
     */
    private fun extractAmount(tokens: List<String>): Int? {
        // Primary path: Regex for numeric digits
        for (token in tokens) {
            val match = DIGIT_REGEX.find(token)
            if (match != null) {
                return match.value.toIntOrNull()
            }
        }

        // Fallback path: Check tokens against Hindi number HashMap
        var totalAmount = 0
        var currentMultiplier = 0
        var foundAny = false

        for (token in tokens) {
            val lower = token.lowercase(Locale.ROOT)
            val value = HINDI_NUMBERS[lower] ?: continue

            foundAny = true
            when (value) {
                100 -> {
                    val base = if (currentMultiplier == 0) 1 else currentMultiplier
                    totalAmount += base * 100
                    currentMultiplier = 0
                }
                1000 -> {
                    val base = if (currentMultiplier == 0) 1 else currentMultiplier
                    totalAmount += base * 1000
                    currentMultiplier = 0
                }
                else -> {
                    currentMultiplier += value
                }
            }
        }

        totalAmount += currentMultiplier
        return if (foundAny && totalAmount > 0) totalAmount else null
    }
}
