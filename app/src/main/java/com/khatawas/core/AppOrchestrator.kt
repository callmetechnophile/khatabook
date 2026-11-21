/*
 * Copyright (c) 2026 KHATAWAS Project
 * Simplified Technical English (ASD STE-100) compliant.
 * Thread-safe application orchestrator for deterministic parsing, encrypted DB, and TTS feedback.
 */

package com.khatawas.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.khatawas.audio.RnnoiseProcessor
import com.khatawas.audio.VoiceFeedback
import com.khatawas.db.LedgerDatabase
import com.khatawas.logic.LedgerParser
import com.khatawas.logic.ParseResult
import com.khatawas.logic.Transaction
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Main application orchestrator for KHATAWAS.
 * Coordinates audio processing, deterministic intent parsing,
 * encrypted SQLite transactions, and localized voice confirmation.
 * Uses a single-threaded executor for SQLite and a main-thread handler for TTS.
 */
class AppOrchestrator(context: Context) {

    companion object {
        private const val TAG = "KHATAWAS_AUDIO"
    }

    // Components
    val rnnoiseProcessor: RnnoiseProcessor = RnnoiseProcessor()
    val ledgerDatabase: LedgerDatabase = LedgerDatabase(context)
    val voiceFeedback: VoiceFeedback = VoiceFeedback(context)

    // Callback invoked on the main thread when a transaction is saved
    var onTransactionSaved: ((Transaction) -> Unit)? = null
    var onBatchTransactionsSaved: ((List<Transaction>, Long) -> Unit)? = null
    var onTransactionRejected: ((String) -> Unit)? = null
    var onQueryResult: ((String, Long) -> Unit)? = null

    // Dedicated single-thread executor for all encrypted SQLite operations
    private val dbExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // Main thread handler for all UI and TTS operations
    private val mainHandler: Handler = Handler(Looper.getMainLooper())

    // Pre-allocated frame buffer for RNNoise audio (480 samples, 10 ms at 48 kHz)
    private val audioOutputBuffer = FloatArray(RnnoiseProcessor.FRAME_SIZE)

    /**
     * Process real transaction text from live speech recognition.
     */
    fun processRealTransaction(text: String) {
        processTransactionInternal(text)
    }

    /**
     * Process mock transaction text for test verification.
     */
    fun processMockTransaction(text: String) {
        processTransactionInternal(text)
    }

    /**
     * Internal processor for utterance strings.
     * Evaluates structured parse results: success, ambiguity, queries, or corrections.
     */
    private fun processTransactionInternal(text: String) {
        val parseResult = LedgerParser.parseStructured(text)
        Log.i(TAG, "Parser structured result: $parseResult")

        when (parseResult) {
            is ParseResult.Success -> {
                val transactions = parseResult.transactions
                dbExecutor.execute {
                    try {
                        val success = ledgerDatabase.insertTransactionsAtomic(transactions)
                        if (success) {
                            val customer = transactions.first().name
                            val netBalance = ledgerDatabase.getCustomerBalancePaise(customer)

                            mainHandler.post {
                                if (transactions.size == 1) {
                                    val t = transactions.first()
                                    voiceFeedback.speakConfirmation(t)
                                    onTransactionSaved?.invoke(t)
                                } else {
                                    val ttsMsg = "${customer} ke ${transactions.size} hisaab likh liye. Kul baaki balance ₹${netBalance / 100} hai"
                                    voiceFeedback.speak(ttsMsg)
                                    onBatchTransactionsSaved?.invoke(transactions, netBalance)
                                    onTransactionSaved?.invoke(transactions.first())
                                }
                            }
                        } else {
                            mainHandler.post {
                                onTransactionRejected?.invoke("Database insert failed. Please try again.")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "DB exception: ${e.message}", e)
                        mainHandler.post {
                            onTransactionRejected?.invoke("Database error. The entry was not saved.")
                        }
                    }
                }
            }

            is ParseResult.Ambiguous -> {
                Log.w(TAG, "Ambiguous utterance: ${parseResult.reason}")
                mainHandler.post {
                    voiceFeedback.speak(parseResult.clarificationQuestion)
                    onTransactionRejected?.invoke(parseResult.clarificationQuestion)
                }
            }

            is ParseResult.Correction -> {
                val newAmountPaise = parseResult.newAmountPaise
                dbExecutor.execute {
                    try {
                        val updated = ledgerDatabase.updateLastTransactionAmount(newAmountPaise)
                        mainHandler.post {
                            if (updated) {
                                val msg = "Amount badal kar ₹${newAmountPaise / 100} kar diya gaya hai"
                                voiceFeedback.speak(msg)
                                onTransactionSaved?.invoke(Transaction("Pichhla hisaab", newAmountPaise, "UPDATED", 0))
                            } else {
                                onTransactionRejected?.invoke("Koi pichhla transaction update karne ke liye nahi mila.")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error updating transaction: ${e.message}", e)
                    }
                }
            }

            is ParseResult.Query -> {
                val customer = parseResult.customerName
                dbExecutor.execute {
                    try {
                        val balancePaise = ledgerDatabase.getCustomerBalancePaise(customer)
                        mainHandler.post {
                            val msg = "${customer} ka kul baaki hisaab ₹${balancePaise / 100} hai"
                            voiceFeedback.speak(msg)
                            onQueryResult?.invoke(customer, balancePaise)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error fetching balance: ${e.message}", e)
                    }
                }
            }

            is ParseResult.DailySummaryQuery -> {
                val offset = parseResult.dateOffset
                val type = parseResult.queryType
                val dayLabel = if (offset == 0) "Aaj" else if (offset == -1) "Kal" else "Pichhle din"
                dbExecutor.execute {
                    try {
                        val creditPaise = ledgerDatabase.getDailyCreditPaise(offset)
                        val paymentPaise = ledgerDatabase.getDailyPaymentPaise(offset)
                        mainHandler.post {
                            val msg = when (type) {
                                "CREDIT" -> "$dayLabel ka kul udhaar ₹${creditPaise / 100} hai"
                                "PAYMENT" -> "$dayLabel ka kul collection ₹${paymentPaise / 100} hai"
                                else -> "$dayLabel ka udhaar ₹${creditPaise / 100} aur collection ₹${paymentPaise / 100} hai"
                            }
                            voiceFeedback.speak(msg)
                            onQueryResult?.invoke(dayLabel, if (type == "PAYMENT") paymentPaise else creditPaise)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error fetching daily summary: ${e.message}", e)
                    }
                }
            }

            is ParseResult.TopDebtorsQuery -> {
                dbExecutor.execute {
                    try {
                        val top = ledgerDatabase.getTopDebtors(parseResult.limit)
                        mainHandler.post {
                            if (top.isEmpty()) {
                                val msg = "Abhi kisi ka koi udhaar baaki nahi hai"
                                voiceFeedback.speak(msg)
                                onQueryResult?.invoke("Sabhi", 0L)
                            } else {
                                val topPerson = top.first()
                                val msg = "Sabse zyada udhaar ${topPerson.first} ke paas ₹${topPerson.second / 100} hai"
                                voiceFeedback.speak(msg)
                                onQueryResult?.invoke(topPerson.first, topPerson.second)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error fetching top debtors: ${e.message}", e)
                    }
                }
            }

            is ParseResult.Reversal -> {
                val customer = parseResult.customerName
                val amount = parseResult.amountPaise
                dbExecutor.execute {
                    try {
                        val reversed = ledgerDatabase.reverseTransaction(customer, amount)
                        mainHandler.post {
                            if (reversed) {
                                val msg = "${customer} ka hisaab cancel kar diya gaya hai"
                                voiceFeedback.speak(msg)
                                val netBalance = ledgerDatabase.getCustomerBalancePaise(customer)
                                onTransactionSaved?.invoke(Transaction(customer, 0, "REVERSAL", 0))
                            } else {
                                val msg = "Cancel karne ke liye koi hisaab nahi mila"
                                voiceFeedback.speak(msg)
                                onTransactionRejected?.invoke(msg)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error reversing transaction: ${e.message}", e)
                    }
                }
            }

            is ParseResult.Failure -> {
                mainHandler.post {
                    onTransactionRejected?.invoke(parseResult.errorMessage)
                }
            }
        }
    }

    /**
     * Process real-time 480-sample audio chunk through RNNoise noise suppression.
     */
    fun processAudioChunk(audioData: FloatArray) {
        rnnoiseProcessor.processFrame(audioData, audioOutputBuffer)
    }

    /**
     * Release all background executors, database handles, and native resources.
     */
    fun destroy() {
        Log.d(TAG, "Shutting down AppOrchestrator resources...")
        dbExecutor.shutdown()
        rnnoiseProcessor.destroy()
        ledgerDatabase.close()
        voiceFeedback.shutdown()
    }
}
