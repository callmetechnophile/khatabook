/*
 * Copyright (c) 2026 KHATAWAS Project
 * Audit test suite executing Phase E test cases against LedgerParser.
 */

package com.khatawas.logic

import org.junit.Test

class ParserAuditTest {

    @Test
    fun runPhaseEAudit() {
        val testCases = listOf(
            // Basic credit entries
            "Ramesh ka 340 udhaar",
            "Suresh ke 500 rupaye baaki",
            "Amit ko 250 ka samaan diya",

            // Payments
            "Ramesh ne 200 diya",
            "Suresh ne 500 rupaye jama kiye",
            "Amit ka payment mil gaya",

            // Multiple transactions
            "Ramesh ka 340 udhaar, kal 200 diya tha",

            // Number variations
            "Teen sau chaalis",
            "340",
            "Three hundred forty",
            "Three-forty",
            "Ek hazaar do sau",
            "Barah sau pachaas",

            // Relative dates
            "Kal Ramesh ka 100 udhaar",
            "Parso Suresh ne 200 diya",
            "Aaj Amit ka 300 jama",
            "Pichhle hafte Ramesh ne 400 diya",

            // Corrections
            "Nahi, 430",

            // Customer ambiguity / queries
            "Aaj kitna udhaar gaya?",
            "Ramesh ka total?",
            "Aaj kitna payment mila?",
            "Sabse zyada kis customer ka baaki hai?"
        )

        println("=== PHASE E PARSER AUDIT RESULTS ===")
        for (input in testCases) {
            val result = LedgerParser.parseUtterance(input)
            println("INPUT: \"$input\"")
            println("OUTPUT: $result")
            println("----------------------------------------")
        }
        println("=== END AUDIT RESULTS ===")
    }
}
