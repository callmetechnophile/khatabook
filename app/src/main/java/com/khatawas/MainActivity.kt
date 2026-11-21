/*
 * Copyright (c) 2026 KHATAWAS Project
 * Simplified Technical English (ASD STE-100) compliant.
 * Minimal push-to-talk activity integrating real-time audio, Gnani STT, DB, and TTS.
 */

package com.khatawas

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.khatawas.audio.GnaniSttClient
import com.khatawas.audio.SttListener
import com.khatawas.core.AppOrchestrator

/**
 * Main Activity for KHATAWAS voice-first ledger.
 * Features high-contrast UI with a single Push-To-Talk button and status display.
 * Captures microphone audio, streams to Gnani STT, updates encrypted DB, and plays TTS confirmation.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_AUDIO = 2001
        private const val GNANI_API_KEY = "GNANI_API_KEY_PLACEHOLDER"
        private const val AUDIO_SAMPLE_RATE = 16000
    }

    private lateinit var appOrchestrator: AppOrchestrator
    private lateinit var gnaniSttClient: GnaniSttClient

    private lateinit var btnPushToTalk: Button
    private lateinit var tvStatus: TextView

    @Volatile
    private var isRecording = false
    private var audioRecord: AudioRecord? = null
    private var audioCaptureThread: Thread? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Bind layout views
        btnPushToTalk = findViewById(R.id.btn_push_to_talk)
        tvStatus = findViewById(R.id.tv_status)

        // 1. Initialize core orchestrator and STT client
        appOrchestrator = AppOrchestrator(this)
        gnaniSttClient = GnaniSttClient()

        // 2. Set listener on GnaniSttClient to process recognized text
        gnaniSttClient.setListener(object : SttListener {
            override fun onTranscriptionReceived(text: String) {
                Log.d(TAG, "Transcription received from Gnani STT: \"$text\"")
                appOrchestrator.processRealTransaction(text)
            }
        })

        // 4. Update the tv_status TextView on the Main Thread when transaction saves
        appOrchestrator.onTransactionSaved = { transaction ->
            runOnUiThread {
                val statusText = "${transaction.name} | ${transaction.amount} | ${transaction.type}"
                Log.i(TAG, "Updating status text view: $statusText")
                tvStatus.text = statusText
            }
        }

        // Request microphone permission if not yet granted
        checkAudioPermission()

        // 3. Set OnTouchListener on Push-to-Talk button
        btnPushToTalk.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    Log.d(TAG, "Push-To-Talk button pressed (ACTION_DOWN)")
                    btnPushToTalk.text = "LISTENING..."
                    startRecordingPipeline()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    Log.d(TAG, "Push-To-Talk button released (ACTION_UP)")
                    btnPushToTalk.text = "PUSH TO TALK"
                    stopRecordingPipeline()
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Start AudioRecord and streaming connection to Gnani STT.
     */
    private fun startRecordingPipeline() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            checkAudioPermission()
            return
        }

        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, channelConfig, audioFormat)
        val bufferSize = maxOf(minBufferSize, 640)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                AUDIO_SAMPLE_RATE,
                channelConfig,
                audioFormat,
                bufferSize
            )

            audioRecord?.startRecording()
            isRecording = true

            // Connect WebSocket for STT streaming
            gnaniSttClient.connect(GNANI_API_KEY, AUDIO_SAMPLE_RATE)

            // Start audio capture loop on dedicated thread
            audioCaptureThread = Thread({
                val audioBuffer = ByteArray(640) // 20 ms chunk at 16kHz 16-bit mono
                while (isRecording) {
                    val bytesRead = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: -1
                    if (bytesRead > 0) {
                        gnaniSttClient.sendAudioChunk(audioBuffer.copyOf(bytesRead))
                    }
                }
            }, "KhatawasAudioCapture").apply { start() }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioRecord pipeline: ${e.message}", e)
        }
    }

    /**
     * Stop AudioRecord and close WebSocket connection.
     */
    private fun stopRecordingPipeline() {
        isRecording = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord: ${e.message}", e)
        } finally {
            audioRecord = null
            audioCaptureThread = null
        }

        // Close WebSocket connection
        gnaniSttClient.disconnect()
    }

    /**
     * Verify and request microphone permissions.
     */
    private fun checkAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                PERMISSION_REQUEST_AUDIO
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecordingPipeline()
        appOrchestrator.destroy()
        gnaniSttClient.shutdown()
    }
}
