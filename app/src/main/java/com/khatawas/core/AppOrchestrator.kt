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
        private const val TAG = "AppOrchestrator"
    }

    // Components
    val rnnoiseProcessor: RnnoiseProcessor = RnnoiseProcessor()
    val ledgerDatabase: LedgerDatabase = LedgerDatabase(context)
    val voiceFeedback: VoiceFeedback = VoiceFeedback(context)

    // Dedicated single-thread executor for all encrypted SQLite operations
    private val dbExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // Main thread handler for all UI and TTS operations
    private val mainHandler: Handler = Handler(Looper.getMainLooper())

    // Pre-allocated frame buffer for RNNoise audio (480 samples, 10 ms at 48 kHz)
    private val audioOutputBuffer = FloatArray(RnnoiseProcessor.FRAME_SIZE)

    /**
     * Process an incoming speech utterance string.
     * Step 1: Parse the utterance using Kotlin Regex and FSM dictionary.
     * Step 2: Submit the transaction insert to the single-thread DB executor.
     * Step 3: On insert success, post runnable to main thread handler to trigger TTS confirmation.
     *
     * Parameter text: Speech recognition transcript to process.
     */
    fun processMockTransaction(text: String) {
        // [STEP: PARSING]
        Log.d(TAG, "[PARSER] Starting deterministic parse for utterance: \"$text\"")
        val transaction = LedgerParser.parseUtterance(text)

        if (transaction == null) {
            Log.w(TAG, "[PARSER] Failed to resolve transaction from utterance: \"$text\"")
            return
        }

        Log.i(
            TAG,
            "[PARSER] Successfully parsed transaction -> Name: ${transaction.name}, " +
                "Amount: ${transaction.amount}, Type: ${transaction.type}, DateOffset: ${transaction.dateOffset}"
        )

        // [STEP: DB INSERT]
        dbExecutor.execute {
            Log.d(TAG, "[DATABASE] Submitting transaction insert on background DB thread...")
            try {
                val rowId = ledgerDatabase.insertTransaction(transaction)

                if (rowId > 0L) {
                    Log.i(TAG, "[DATABASE] Transaction successfully inserted. Row ID: $rowId")

                    // [STEP: TTS TRIGGER]
                    mainHandler.post {
                        Log.d(TAG, "[TTS] Posting confirmation to main thread for customer: ${transaction.name}")
                        voiceFeedback.speakConfirmation(transaction)
                    }
                } else {
                    Log.e(TAG, "[DATABASE] Insert failed. Database returned row ID: $rowId")
                }
            } catch (e: Exception) {
                Log.e(TAG, "[DATABASE] Database insert error: ${e.message}", e)
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
