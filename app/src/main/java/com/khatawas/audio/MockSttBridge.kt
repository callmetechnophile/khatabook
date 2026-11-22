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
 * Simulates streaming speech transcripts for testing the audio and speech dispatch pipeline.
 * Dispatches parameterized utterances to AppOrchestrator to verify parser, database, and TTS.
 * Does not make external network requests and contains no hardcoded customer demo data.
 */
class MockSttBridge(private val orchestrator: AppOrchestrator) {

    /**
     * Dispatch an utterance to the orchestrator pipeline.
     */
    fun dispatchUtterance(utterance: String) {
        Log.d(TAG, "Dispatching utterance: \"$utterance\"")
        orchestrator.processMockTransaction(utterance)
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
         * Static runner for dispatching an utterance using the initialized default orchestrator.
         */
        fun dispatch(utterance: String) {
            val orchestrator = defaultOrchestrator
                ?: throw IllegalStateException("MockSttBridge default orchestrator is not initialized.")
            orchestrator.processMockTransaction(utterance)
        }
    }
}
