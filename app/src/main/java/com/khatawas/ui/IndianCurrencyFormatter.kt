/*
 * Copyright (c) 2026 KHATAWAS Project
 * Simplified Technical English (ASD STE-100) compliant.
 * Indian Currency (INR / ₹) Formatter supporting standard Indian numbering grouping:
 * 3 digits on the right, followed by 2-digit groups (e.g. ₹500, ₹1,250, ₹25,000, ₹1,00,000, ₹1,00,00,000).
 * Enforces integer paise precision (1 Rupee = 100 Paise) and prevents raw paise from rendering as rupees.
 */

package com.khatawas.ui

import java.util.Locale

object IndianCurrencyFormatter {

    /**
     * Formats an amount stored in integer paise into an Indian Rupee string (e.g. ₹1,250).
     *
     * @param amountPaise The amount in paise (100 paise = 1 Rupee).
     * @param includeSymbol When true, prepends the Rupee symbol (₹).
     * @return Formatted string with Indian comma grouping.
     */
    fun formatPaise(amountPaise: Long, includeSymbol: Boolean = true): String {
        val isNegative = amountPaise < 0
        val absPaise = Math.abs(amountPaise)
        val rupees = absPaise / 100L
        val paiseRemainder = absPaise % 100L

        val formattedRupees = formatRupees(rupees)
        val fullFormatted = if (paiseRemainder > 0L) {
            String.format(Locale.ROOT, "%s.%02d", formattedRupees, paiseRemainder)
        } else {
            formattedRupees
        }

        val prefix = if (isNegative) "-" else ""
        val symbol = if (includeSymbol) "₹" else ""
        return "$prefix$symbol$fullFormatted"
    }

    /**
     * Formats a whole rupee value with Indian numbering system grouping.
     * Examples:
     *        0 -> "0"
     *      500 -> "500"
     *     1250 -> "1,250"
     *    25000 -> "25,000"
     *   100000 -> "1,00,000"
     * 10000000 -> "1,00,00,000"
     */
    fun formatRupees(rupees: Long): String {
        val s = rupees.toString()
        if (s.length <= 3) return s

        val last3 = s.substring(s.length - 3)
        val rest = s.substring(0, s.length - 3)

        val sb = StringBuilder()
        var count = 0
        for (i in rest.length - 1 downTo 0) {
            sb.append(rest[i])
            count++
            if (count % 2 == 0 && i > 0) {
                sb.append(',')
            }
        }
        val restFormatted = sb.reverse().toString()
        return "$restFormatted,$last3"
    }
}
