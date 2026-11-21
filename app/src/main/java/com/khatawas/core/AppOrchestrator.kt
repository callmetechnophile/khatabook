/*
 * Copyright (c) 2026 KHATAWAS Project
 * Simplified Technical English (ASD STE-100) compliant.
 * Thread-safe application orchestrator for deterministic parsing, DB, and TTS.
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
    var onTransactionRejected: ((String) -> Unit)? = null

    // Dedicated single-thread executor for all encrypted SQLite operations
    private val dbExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // Main thread handler for all UI and TTS operations
    private val mainHandler: Handler = Handler(Looper.getMainLooper())

    // Pre-allocated frame buffer for RNNoise audio (480 samples, 10 ms at 48 kHz)
    private val audioOutputBuffer = FloatArray(RnnoiseProcessor.FRAME_SIZE)

    /**
     * Process real transaction text from live speech recognition.
     * Parameter text: Speech recognition transcript to process.
     */
    fun processRealTransaction(text: String) {
        processTransactionInternal(text)
    }

    /**
     * Process mock transaction text for test verification.
     * Parameter text: Speech recognition transcript to process.
     */
    fun processMockTransaction(text: String) {
        processTransactionInternal(text)
    }

    /**
     * Internal processor for utterance strings.
     * Step 1: Parse the utterance using Kotlin Regex and FSM dictionary.
     * Step 2: Submit the transaction insert to the single-thread DB executor.
     * Step 3: On insert success, post runnable to main thread handler to trigger TTS confirmation and UI update.
     */
    private fun processTransactionInternal(text: String) {
        // [STEP: PARSING]
        val transaction = LedgerParser.parseUtterance(text)
        Log.i(TAG, "Parser result: $transaction")

        if (transaction == null) {
            Log.w(TAG, "Failed to resolve transaction from utterance")
            mainHandler.post {
                onTransactionRejected?.invoke("Could not identify a safe transaction. Check the amount, customer, and credit/payment wording.")
            }
            return
        }

        // [STEP: DB INSERT]
        dbExecutor.execute {
            try {
                val rowId = ledgerDatabase.insertTransaction(transaction)

                if (rowId > 0L) {
                    Log.i(TAG, "DB insert OK, id = $rowId")

                    // [STEP: TTS TRIGGER & UI UPDATE]
                    mainHandler.post {
                        Log.i(TAG, "TTS triggered")
                        voiceFeedback.speakConfirmation(transaction)
                        onTransactionSaved?.invoke(transaction)
                    }
                } else {
                    Log.e(TAG, "DB insert failed. Returned row ID: $rowId")
                    mainHandler.post {
                        onTransactionRejected?.invoke("The ledger entry could not be saved. Please try again.")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "DB insert exception: ${e.message}", e)
                mainHandler.post {
                    onTransactionRejected?.invoke("Database error. The entry was not confirmed as saved.")
                }
            }
        }
    }

    /**
     * Process real-time 480-sample audio chunk through RNNoise noise suppression.
     * Parameter audioData: Array of 480 float samples.
     */
    fun processAudioChunk(audioData: FloatArray) {
        rnnoiseProcessor.nativeProcessFrame(audioData, audioOutputBuffer)
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
