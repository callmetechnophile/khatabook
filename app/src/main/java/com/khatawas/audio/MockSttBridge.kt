/*
 * Copyright (c) 2026 KHATAWAS Project
 * Simplified Technical English (ASD STE-100) compliant.
 * Mock Speech-To-Text (STT) bridge for end-to-end pipeline validation.
 */

package com.khatawas.audio

import android.util.Log
import com.khatawas.core.AppOrchestrator

/**
 * Mock STT bridge.
 * Simulates streaming speech transcripts from the Gnani Prisma speech engine.
 * Dispatches test utterances to AppOrchestrator to verify parser, database, and TTS.
 * Does not make external network requests.
 */
class MockSttBridge(private val orchestrator: AppOrchestrator) {

    /**
     * Run the complete pipeline test using three hardcoded Kirana store utterances.
     * 1. "Ramesh ka 340 udhaar" (Credit transaction)
     * 2. "Suresh ko 200 diya" (Debit transaction)
     * 3. "Kal wala 500 jama" (Debit transaction with -1 day offset)
     */
    fun runPipelineTest() {
        Log.i(TAG, "Starting Mock STT pipeline test with 3 test utterances...")

        // Utterance 1: Credit transaction with explicit digits
        Log.d(TAG, "Dispatching test utterance 1: \"Ramesh ka 340 udhaar\"")
        orchestrator.processMockTransaction("Ramesh ka 340 udhaar")

        // Utterance 2: Debit transaction with explicit digits
        Log.d(TAG, "Dispatching test utterance 2: \"Suresh ko 200 diya\"")
        orchestrator.processMockTransaction("Suresh ko 200 diya")

        // Utterance 3: Debit transaction with relative date offset
        Log.d(TAG, "Dispatching test utterance 3: \"Kal wala 500 jama\"")
        orchestrator.processMockTransaction("Kal wala 500 jama")

        Log.i(TAG, "All test utterances dispatched to AppOrchestrator.")
    }

    companion object {
        private const val TAG = "MockSttBridge"

        @Volatile
        private var defaultOrchestrator: AppOrchestrator? = null

        /**
         * Initialize the default orchestrator instance for static invocation.
         */
        fun init(orchestrator: AppOrchestrator) {
            defaultOrchestrator = orchestrator
        }

        /**
         * Static runner for pipeline test using the initialized default orchestrator.
         */
        fun runPipelineTest() {
            val orchestrator = defaultOrchestrator
                ?: throw IllegalStateException("MockSttBridge default orchestrator is not initialized.")
            orchestrator.processMockTransaction("Ramesh ka 340 udhaar")
            orchestrator.processMockTransaction("Suresh ko 200 diya")
            orchestrator.processMockTransaction("Kal wala 500 jama")
        }

        /**
         * Static runner accepting an explicit AppOrchestrator instance.
         */
        fun runPipelineTest(orchestrator: AppOrchestrator) {
            orchestrator.processMockTransaction("Ramesh ka 340 udhaar")
            orchestrator.processMockTransaction("Suresh ko 200 diya")
            orchestrator.processMockTransaction("Kal wala 500 jama")
        }
    }
}
