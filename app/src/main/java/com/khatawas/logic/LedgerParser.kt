/*
 * Copyright (c) 2026 KHATAWAS Project
 * Simplified Technical English (ASD STE-100) compliant.
 * Deterministic natural language financial parser supporting Devanagari Hindi, Latin Hinglish, and Kannada terms.
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
 * Supports both Devanagari script (e.g. "रमेश का 340 उधार") and Latin transliteration ("Ramesh ka 340 udhaar").
 * Strictly rejects pronouns ("iska", "uska", "इसका", "उसका") as customer names.
 */
object LedgerParser {

    private val DIGIT_REGEX = Regex("\\b[\\d\\u0966-\\u096F]+(?:\\.[\\d\\u0966-\\u096F]{1,2})?\\b")

    // Keywords indicating CREDIT (money owed to shopkeeper) in Latin and Devanagari
    private val CREDIT_KEYWORDS = setOf(
        // Latin
        "udhaar", "udhar", "baaki", "baki", "credit", "likh", "likho",
        "samaan", "samaano", "dena", "saala", "kadam", "bakiye", "khata",
        // Devanagari
        "उधार", "उध़ार", "बाकी", "बाक़ी", "बकाया", "क्रेडिट", "लिख", "लिखो",
        "सामान", "देना", "खाता", "खाते", "साल"
    )

    // Keywords indicating PAYMENT (money paid to shopkeeper) in Latin and Devanagari
    private val PAYMENT_KEYWORDS = setOf(
        // Latin
        "jama", "diya", "diye", "payment", "paid", "mila", "mile", "aaye",
        "vasool", "kottidhaare", "pavadhi", "received", "chuka", "chukaye", "bheja", "bheje",
        // Devanagari
        "जमा", "दिया", "दिए", "पेमेंट", "पेड", "मिला", "मिले", "आए", "आये",
        "वसूल", "चुकाया", "चुकाए", "भेजा", "भेजे", "प्राप्त"
    )

    // Relative date mapping in Latin and Devanagari
    private val DATE_MAP = mapOf(
        "aaj" to 0, "today" to 0, "आज" to 0,
        "kal" to -1, "yesterday" to -1, "कल" to -1,
        "parso" to -2, "परसों" to -2, "परसो" to -2,
        "pichhle hafte" to -7, "last week" to -7, "पिछले हफ्ते" to -7, "पिछले सप्ताह" to -7
    )

    // Pronouns and demonstratives that MUST NEVER be accepted as customer names
    private val PRONOUNS = setOf(
        // Latin
        "iska", "isko", "isse", "iski", "iske",
        "uska", "usko", "usse", "uski", "uske",
        "inka", "inko", "inse", "unki", "unke",
        "unka", "unko", "unse",
        "mera", "meri", "mere", "mujhko", "mujhe",
        "tera", "teri", "tere", "tujhko", "tujhe",
        "tumhara", "tumhari", "tumhare",
        "hamara", "hamari", "hamare",
        "apna", "apni", "apne",
        "kiska", "kisko", "kisse",
        "is", "us", "in", "un", "ye", "wo", "yeh", "voh",
        // Devanagari
        "इसका", "इसको", "इससे", "इसकी", "इसके",
        "उसका", "उसको", "उससे", "उसकी", "उसके",
        "इनका", "इनको", "इनसे", "इनकी", "इनके",
        "उनका", "उनको", "उनसे", "उनकी", "उनके",
        "मेरा", "मेरी", "मेरे", "मुझको", "मुझे",
        "तेरा", "तेरी", "तेरे", "तुझको", "तुझे",
        "तुम्हारा", "तुम्हारी", "तुम्hare",
        "हमारा", "हमारी", "हमारे",
        "अपना", "अपनी", "अपने",
        "किसका", "किसको", "किसे",
        "इस", "उस", "इन", "उन", "यह", "वह", "ये", "वो"
    )

    // Reserved words, particles, and stopwords
    private val RESERVED_WORDS = setOf(
        // Latin
        "aaj", "kal", "parso", "today", "yesterday", "rupaye", "rupiya", "rupee", "rupees", "rs",
        "ka", "ke", "ki", "ko", "ne", "se", "me", "mein", "par", "wala", "wali", "tha", "thi", "the", "hai", "hain",
        "udhaar", "udhar", "jama", "diya", "diye", "baaki", "payment", "paid", "bhi", "aur", "and",
        "nahi", "no", "not", "samaan", "hisaab", "hisab", "kitna", "batao", "saala",
        // Devanagari
        "आज", "कल", "परसों", "परसो", "रुपये", "रुपए", "रुपया",
        "का", "के", "की", "को", "ने", "से", "में", "पर", "वाला", "वाली", "था", "थी", "थे", "है", "हैं",
        "उधार", "जमा", "दिया", "दिए", "बाकी", "पेमेंट", "भी", "और",
        "नहीं", "ना", "सामान", "हिसाब", "कितना", "बताओ", "कुल"
    )

    // Hindi & English number words in Latin and Devanagari
    private val NUMBER_WORDS = mapOf(
        // Latin
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
        "lakh" to 100000L, "lac" to 100000L,

        // Devanagari
        "शून्य" to 0L,
        "एक" to 1L,
        "दो" to 2L,
        "तीन" to 3L,
        "चार" to 4L,
        "पांच" to 5L, "पाँच" to 5L,
        "छह" to 6L, "छः" to 6L,
        "सात" to 7L,
        "आठ" to 8L,
        "नौ" to 9L,
        "दस" to 10L,
        "ग्यारह" to 11L,
        "बारह" to 12L,
        "तेरह" to 13L,
        "चौदह" to 14L,
        "पंद्रह" to 15L,
        "सोलह" to 16L,
        "सत्रह" to 17L,
        "अट्ठारह" to 18L,
        "उन्नीस" to 19L,
        "बीस" to 20L,
        "पच्चीस" to 25L,
        "तीस" to 30L,
        "पैंतीस" to 35L,
        "चालीस" to 40L,
        "पैंतालीस" to 45L,
        "पचास" to 50L,
        "साठ" to 60L,
        "सत्तर" to 70L,
        "अस्सी" to 80L,
        "नब्बे" to 90L,
        "सौ" to 100L,
        "हजार" to 1000L, "हज़ार" to 1000L,
        "लाख" to 100000L
    )

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

        // 1. Correction detection: "Nahi, 430" / "नहीं, 430"
        if (lower.startsWith("nahi") || lower.startsWith("no ") || lower.startsWith("galat") ||
            trimmed.startsWith("नहीं") || trimmed.startsWith("गलत")
        ) {
            val amount = extractAmountPaise(trimmed)
            if (amount != null && amount > 0L) {
                return ParseResult.Correction(amount)
            }
            return ParseResult.Ambiguous("Correction without valid amount", "Kitna amount likhna hai?")
        }

        // 2. Query detection: "Ramesh ka hisaab batao" / "रमेश का हिसाब बताओ"
        if (lower.contains("kitna") || lower.contains("hisaab") || lower.contains("balance") || lower.contains("batao") ||
            trimmed.contains("कितना") || trimmed.contains("हिसाब") || trimmed.contains("बताओ")
        ) {
            val customer = extractCustomerName(trimmed)
            if (customer != null) {
                return ParseResult.Query(customer, "BALANCE")
            }
        }

        // 3. Multi-clause split: handles compound sentences like:
        // "Ramesh ka 340 udhaar, kal 200 diya tha" or "रमेश का 340 उधार, कल 200 दिया था"
        val clauses = splitClauses(trimmed)
        val defaultCustomer = extractCustomerName(trimmed)

        if (defaultCustomer == null) {
            // Check if utterance used a pronoun like "iska" / "uska" / "इसका" / "उसका"
            val tokens = trimmed.split(Regex("[\\s,;]+"))
            val usedPronoun = tokens.any { PRONOUNS.contains(it.lowercase(Locale.ROOT)) }
            val question = if (usedPronoun) {
                "Kiska hisaab hai? Kripya customer ka naam bataiye."
            } else {
                "Kiska hisaab likhna hai?"
            }
            return ParseResult.Ambiguous("Missing or ambiguous customer name", question)
        }

        val parsedTransactions = ArrayList<Transaction>()

        for (clause in clauses) {
            val clauseLower = clause.lowercase(Locale.ROOT)
            val amountPaise = extractAmountPaise(clause) ?: continue

            val isCredit = CREDIT_KEYWORDS.any { clauseLower.contains(it) || clause.contains(it) }
            val isPayment = PAYMENT_KEYWORDS.any { clauseLower.contains(it) || clause.contains(it) }

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
                // Both words present: prioritize whichever appears first
                val creditIdx = minOf(
                    if (clauseLower.indexOf("udhaar") >= 0) clauseLower.indexOf("udhaar") else Int.MAX_VALUE,
                    if (clause.indexOf("उधार") >= 0) clause.indexOf("उधार") else Int.MAX_VALUE
                )
                val paymentIdx = minOf(
                    if (clauseLower.indexOf("diya") >= 0) clauseLower.indexOf("diya") else Int.MAX_VALUE,
                    if (clause.indexOf("दिया") >= 0) clause.indexOf("दिया") else Int.MAX_VALUE
                )
                if (creditIdx <= paymentIdx) TransactionDirection.CREDIT else TransactionDirection.PAYMENT
            }

            val dateOffset = extractDateOffset(clause)

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

    private fun splitClauses(text: String): List<String> {
        val delimiters = Regex("[,;]|\\b(?:aur|and|lekin|magar|और|लेकिन)\\b", RegexOption.IGNORE_CASE)
        val parts = text.split(delimiters).map { it.trim() }.filter { it.isNotEmpty() }
        return if (parts.isNotEmpty()) parts else listOf(text)
    }

    /**
     * Extract customer name.
     * Strictly rejects pronouns (iska, uska, इसका, उसका) and reserved stopwords.
     * Prioritizes words preceding postpositions (ka, ke, ki, ko, ne, se, का, के, की, को, ने, से).
     */
    fun extractCustomerName(text: String): String? {
        val tokens = text.trim().split(Regex("[\\s,;]+"))
        if (tokens.isEmpty()) return null

        val particles = setOf("ka", "ke", "ki", "ko", "ne", "se", "का", "के", "की", "को", "ने", "से")

        // Strategy 1: Look for a token immediately followed by a particle
        for (i in 0 until tokens.size - 1) {
            val candidate = tokens[i]
            val next = tokens[i + 1].lowercase(Locale.ROOT)
            if (particles.contains(next)) {
                val candidateLower = candidate.lowercase(Locale.ROOT)
                if (isValidCustomerCandidate(candidateLower, candidate)) {
                    return formatCustomerName(candidate)
                }
            }
        }

        // Strategy 2: First valid non-reserved, non-pronoun, non-numeric token
        for (token in tokens) {
            val tokenLower = token.lowercase(Locale.ROOT)
            if (isValidCustomerCandidate(tokenLower, token)) {
                return formatCustomerName(token)
            }
        }

        return null
    }

    private fun isValidCustomerCandidate(lower: String, raw: String): Boolean {
        if (PRONOUNS.contains(lower) || PRONOUNS.contains(raw)) return false
        if (RESERVED_WORDS.contains(lower) || RESERVED_WORDS.contains(raw)) return false
        if (NUMBER_WORDS.containsKey(lower) || NUMBER_WORDS.containsKey(raw)) return false
        if (DIGIT_REGEX.matches(raw) || DIGIT_REGEX.matches(lower)) return false
        if (raw.length < 2) return false
        return true
    }

    private fun formatCustomerName(name: String): String {
        return name.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
        }
    }

    private fun extractDateOffset(clause: String): Int {
        val lower = clause.lowercase(Locale.ROOT)
        for ((phrase, offset) in DATE_MAP) {
            if (lower.contains(phrase) || clause.contains(phrase)) {
                return offset
            }
        }
        return 0
    }

    /**
     * Extract monetary amount in integer paise (Long) from Arabic or Devanagari digits/words.
     */
    fun extractAmountPaise(clause: String): Long? {
        val lower = clause.lowercase(Locale.ROOT)

        // 1. Check numeric digits (converting Devanagari digits to standard digits)
        val normalizedClause = normalizeDigits(lower)
        val digitMatch = Regex("\\b\\d+(?:\\.\\d{1,2})?\\b").find(normalizedClause)
        if (digitMatch != null) {
            val floatVal = digitMatch.value.toDoubleOrNull()
            if (floatVal != null && floatVal > 0.0) {
                return Math.round(floatVal * 100.0)
            }
        }

        // 2. Parse multi-word Hindi & English number expressions
        val tokens = clause.split(Regex("[\\s,;]+"))
        var totalRupees = 0L
        var currentChunk = 0L
        var foundNumber = false

        for (token in tokens) {
            val unit = NUMBER_WORDS[token.lowercase(Locale.ROOT)] ?: NUMBER_WORDS[token] ?: continue
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

    private fun normalizeDigits(input: String): String {
        val devanagariDigits = "०१२३४५६७८९"
        val sb = StringBuilder(input.length)
        for (ch in input) {
            val idx = devanagariDigits.indexOf(ch)
            if (idx >= 0) {
                sb.append(('0' + idx))
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }
}
