/*
 * Copyright (c) 2026 KHATAWAS Project
 * Simplified Technical English (ASD STE-100) compliant.
 * Presentation models and category taxonomy for customer ledger and transaction history.
 */

package com.khatawas.ui

import com.khatawas.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exactly four presentation categories:
 * - CREDIT: Money added to the customer's outstanding balance (Green)
 * - DEBIT: Money deducted from the customer's outstanding balance (Red)
 * - STUCK: Transaction explicitly classified as stuck (Yellow)
 * - ILLEGAL: Transaction explicitly classified as an illegal bank payment (Grey)
 */
enum class TransactionCategory(
    val label: String,
    val meaning: String,
    val bgDrawableRes: Int,
    val textColorRes: Int,
    val lightHexColor: String,
    val darkHexColor: String
) {
    CREDIT(
        label = "CREDIT",
        meaning = "Money added to customer's outstanding balance",
        bgDrawableRes = R.drawable.bg_badge_credit,
        textColorRes = R.color.badge_credit_text,
        lightHexColor = "#1B5E20",
        darkHexColor = "#81C784"
    ),
    DEBIT(
        label = "DEBIT",
        meaning = "Money deducted from customer's outstanding balance",
        bgDrawableRes = R.drawable.bg_badge_debit,
        textColorRes = R.color.badge_debit_text,
        lightHexColor = "#B71C1C",
        darkHexColor = "#EF9A9A"
    ),
    STUCK(
        label = "STUCK",
        meaning = "Transaction explicitly classified as stuck",
        bgDrawableRes = R.drawable.bg_badge_stuck,
        textColorRes = R.color.badge_stuck_text,
        lightHexColor = "#8D5B00",
        darkHexColor = "#FFE082"
    ),
    ILLEGAL(
        label = "ILLEGAL",
        meaning = "Transaction explicitly classified as an illegal bank payment",
        bgDrawableRes = R.drawable.bg_badge_illegal,
        textColorRes = R.color.badge_illegal_text,
        lightHexColor = "#263238",
        darkHexColor = "#CFD8DC"
    );

    companion object {
        /**
         * Resolves the presentation category from database type and note metadata.
         * Ensures backwards compatibility with existing database rows while strictly
         * respecting explicit STUCK and ILLEGAL classifications.
         */
        fun fromRecord(type: String, note: String = ""): TransactionCategory {
            val upperType = type.trim().uppercase(Locale.ROOT)
            val upperNote = note.trim().uppercase(Locale.ROOT)

            if (upperType == "STUCK" || upperNote.contains("STUCK") || upperNote.contains("CLASSIFICATION:STUCK")) {
                return STUCK
            }
            if (upperType == "ILLEGAL" || upperNote.contains("ILLEGAL") || upperNote.contains("CLASSIFICATION:ILLEGAL")) {
                return ILLEGAL
            }

            return when (upperType) {
                "CREDIT" -> CREDIT
                "PAYMENT", "DEBIT" -> DEBIT
                "REVERSAL" -> DEBIT
                else -> CREDIT
            }
        }
    }
}

/**
 * UI representation of a ledger transaction entry formatted for display.
 */
data class TransactionUiModel(
    val id: Long,
    val eventId: String,
    val customerName: String,
    val amountPaise: Long,
    val category: TransactionCategory,
    val timestamp: Long,
    val note: String,
    val paymentMethod: String = "Cash / Kirana Khata",
    val reviewStatus: String = "Recorded"
) {
    val formattedAmount: String get() = IndianCurrencyFormatter.formatPaise(amountPaise)

    val formattedDateTime: String
        get() {
            if (timestamp <= 0L) return "Today"
            val sdf = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }

    val accessibleDescription: String
        get() = "$customerName, ${category.label} of $formattedAmount on $formattedDateTime"
}
