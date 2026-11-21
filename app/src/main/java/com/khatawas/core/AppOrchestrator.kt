/*
 * Copyright (c) 2026 KHATAWAS Project
 * Simplified Technical English (ASD STE-100) compliant.
 * Core application orchestrator connecting audio DSP, deterministic parser, DB, and TTS.
 */

package com.khatawas.core

import android.content.Context
import com.khatawas.audio.MockSttBridge
import com.khatawas.audio.RnnoiseProcessor
import com.khatawas.audio.VoiceFeedback
import com.khatawas.db.LedgerDatabase

/**
 * Main application orchestrator for KHATAWAS.
 * Connects the real-time audio pipeline, speech recognition bridge,
 * encrypted local database, and text-to-speech audio feedback.
 */
class AppOrchestrator(context: Context) {

    // Component instances
    val rnnoiseProcessor: RnnoiseProcessor = RnnoiseProcessor()
    val ledgerDatabase: LedgerDatabase = LedgerDatabase(context)
    val voiceFeedback: VoiceFeedback = VoiceFeedback(context)
    val mockSttBridge: MockSttBridge = MockSttBridge(ledgerDatabase)

    // Pre-allocated output buffer for 480 float samples (10 ms frame at 48 kHz).
    // Prevents object allocation inside the real-time audio processing loop.
    private val outputBuffer = FloatArray(RnnoiseProcessor.FRAME_SIZE)

    init {
        // Initialize the static bridge with the encrypted database
        MockSttBridge.init(ledgerDatabase)
    }

    /**
     * Process an incoming audio chunk from the microphone stream.
     * Passes the frame through RNNoise C++ library to suppress background noise.
     * Passes the mock speech input to the deterministic parser and database.
     * Triggers voice feedback when database storage succeeds.
     *
     * Parameter audioData: Array of 480 float audio samples (10 ms at 48000 Hz).
     */
    fun processAudioChunk(audioData: FloatArray) {
        // 1. Pass audio through RNNoise noise suppression
        rnnoiseProcessor.nativeProcessFrame(audioData, outputBuffer)

        // 2. Simulate speech recognition input to verify end-to-end flow
        val transaction = MockSttBridge.simulateSttInput("Ramesh ka 340 udhaar")

        // 3. Trigger voice confirmation when database insert succeeds
        if (transaction != null) {
            voiceFeedback.speakConfirmation(transaction)
        }
    }

    /**
     * Release all native and platform resources.
     */
    fun destroy() {
        rnnoiseProcessor.destroy()
        ledgerDatabase.close()
        voiceFeedback.shutdown()
    }
}
