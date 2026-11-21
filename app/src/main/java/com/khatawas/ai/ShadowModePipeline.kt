/*
 * Copyright (c) 2026 KHATAWAS Project
 * Shadow Mode Execution and Deterministic Financial Boundary.
 *
 * CRITICAL ARCHITECTURAL GUARANTEE:
 * 1. The distilled student model runs purely as a parallel observer/advisor (shadow mode).
 * 2. All actual financial mutations MUST be authorized exclusively by the deterministic
 *    validation layer (LedgerParser & LedgerDatabase).
 * 3. The student model has NO database handle and CANNOT commit records to SQLite or SurrealDB.
 * 4. Logs are strictly sanitized: no customer PII or transaction balances are emitted to logcat.
 */

package com.khatawas.ai

import android.content.Context
import android.util.Log
import com.khatawas.logic.LedgerParser
import com.khatawas.logic.ParseResult

data class ShadowEvaluation(
    val deterministicResult: ParseResult,
    val studentPrediction: StudentPrediction?,
    val agreesOnCategory: Boolean,
    val agreesOnClarification: Boolean,
    val executionLatencyMs: Long
)

class ShadowModePipeline(
    private val studentInference: StudentModelInference?
) {
    companion object {
        private const val TAG = "KhatawasShadowAI"
        private var totalInferences = 0L
        private var categoryAgreements = 0L
        private var clarificationAgreements = 0L

        fun create(context: Context): ShadowModePipeline {
            val inference = try {
                StudentModelInference.create(context)
            } catch (e: Exception) {
                Log.w(TAG, "Student model could not be initialized; running deterministic-only pipeline: ${e.message}")
                null
            }
            return ShadowModePipeline(inference)
        }
    }

    /**
     * Executes transaction understanding in shadow mode.
     * Always returns the deterministic ParseResult as the authoritative result.
     */
    fun process(utterance: String, existingCustomers: Set<String> = emptySet()): ShadowEvaluation {
        val startTime = System.currentTimeMillis()

        // 1. Authoritative deterministic parse
        val authoritativeResult = LedgerParser.parseStructured(utterance)

        // 2. Parallel shadow model execution
        var studentPred: StudentPrediction? = null
        var categoryAgrees = false
        var clarAgrees = false

        if (studentInference != null) {
            try {
                val pred = studentInference.infer(utterance)
                studentPred = pred
                totalInferences++

                // Compare category
                val authCategory = when (authoritativeResult) {
                    is ParseResult.Success -> {
                        val first = authoritativeResult.transactions.firstOrNull()
                        when (first?.type) {
                            "CREDIT" -> "CREDIT"
                            "PAYMENT" -> "DEBIT"
                            else -> "UNKNOWN"
                        }
                    }
                    is ParseResult.Ambiguous -> "AMBIGUOUS"
                    else -> "OTHER"
                }

                categoryAgrees = (authCategory == pred.category) ||
                        (authCategory == "DEBIT" && pred.category == "DEBIT") ||
                        (authCategory == "CREDIT" && pred.category == "CREDIT")
                if (categoryAgrees) categoryAgreements++

                val authNeedsClar = authoritativeResult is ParseResult.Ambiguous || authoritativeResult is ParseResult.Failure
                clarAgrees = (authNeedsClar == pred.requiresClarification)
                if (clarAgrees) clarificationAgreements++

                val elapsed = System.currentTimeMillis() - startTime
                // Sanitized telemetry logging (NO customer names, NO amounts)
                Log.d(
                    TAG,
                    "Shadow inference executed in ${pred.latencyMs}ms. " +
                            "Category Agreement: $categoryAgrees, Clarification Agreement: $clarAgrees. " +
                            "Lifetime Agreement Rate: ${if (totalInferences > 0) (categoryAgreements * 100 / totalInferences) else 0}%"
                )
            } catch (e: Exception) {
                Log.w(TAG, "Shadow inference failed gracefully: ${e.message}")
            }
        }

        val totalTime = System.currentTimeMillis() - startTime
        return ShadowEvaluation(
            deterministicResult = authoritativeResult,
            studentPrediction = studentPred,
            agreesOnCategory = categoryAgrees,
            agreesOnClarification = clarAgrees,
            executionLatencyMs = totalTime
        )
    }
}
