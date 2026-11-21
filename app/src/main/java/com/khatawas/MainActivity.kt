/*
 * Copyright (c) 2026 KHATAWAS Project
 * Simplified Technical English (ASD STE-100) compliant.
 * Production-hardened activity with permission gate, A/B noise toggle, AudioPipeline, and Gnani STT.
 */

package com.khatawas

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.khatawas.audio.AudioPipeline
import com.khatawas.audio.GnaniSttClient
import com.khatawas.audio.SttListener
import com.khatawas.core.AppOrchestrator

/**
 * Main Activity for KHATAWAS.
 * Implements runtime permission gate, A/B noise suppression toggle,
 * zero-allocation AudioPipeline, and Java-WebSocket streaming to Gnani STT.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "KHATAWAS_AUDIO"
        private const val REQUEST_RECORD_AUDIO = 101
    }

    private lateinit var appOrchestrator: AppOrchestrator
    private lateinit var audioPipeline: AudioPipeline
    private var gnaniSttClient: GnaniSttClient? = null

    private lateinit var btnPushToTalk: Button
    private lateinit var tvStatus: TextView
    private lateinit var switchRnnoise: SwitchCompat

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // View bindings
        btnPushToTalk = findViewById(R.id.btn_push_to_talk)
        tvStatus = findViewById(R.id.tv_status)
        switchRnnoise = findViewById(R.id.switch_rnnoise)

        // Initialize core orchestrator and 48kHz->16kHz audio pipeline
        appOrchestrator = AppOrchestrator(this)
        audioPipeline = AudioPipeline(appOrchestrator.rnnoiseProcessor)

        // Wire AudioPipeline to send 16kHz PCM chunks to Gnani STT
        audioPipeline.onPcm16kReady = { chunk ->
            gnaniSttClient?.sendAudioChunk(chunk)
        }

        // Update status text on the main thread when a transaction is saved
        appOrchestrator.onTransactionSaved = { transaction ->
            runOnUiThread {
                val statusText = "${transaction.name} | ${transaction.amount} | ${transaction.type}"
                tvStatus.text = statusText
            }
        }

        // Step 5: A/B Noise Suppression Toggle
        switchRnnoise.setOnCheckedChangeListener { _, isChecked ->
            audioPipeline.isRnnoiseEnabled = isChecked
            switchRnnoise.text = if (isChecked) "RNNoise ON" else "RNNoise OFF"
            Log.i(TAG, "RNNoise state changed: isEnabled = $isChecked")
        }

        // Step 2: Runtime Permission Gate
        val hasPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            btnPushToTalk.isEnabled = true
        } else {
            btnPushToTalk.isEnabled = false
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_RECORD_AUDIO
            )
        }

        // Step 3 & 4: Push-to-Talk button touch handling
        btnPushToTalk.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    btnPushToTalk.text = "LISTENING..."
                    startListening()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    btnPushToTalk.text = "PUSH TO TALK"
                    stopListening()
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Start live audio capture and WebSocket streaming.
     */
    private fun startListening() {
        // Initialize fresh STT WebSocket client for each recording session
        val client = GnaniSttClient()
        client.setListener(object : SttListener {
            override fun onTranscriptionReceived(text: String) {
                appOrchestrator.processRealTransaction(text)
            }

            override fun onSttFailed() {
                runOnUiThread {
                    appOrchestrator.voiceFeedback.speak("Network slow. Try again.")
                }
            }
        })
        client.fallbackTts = {
            appOrchestrator.voiceFeedback.speak("Network slow. Try again.")
        }

        gnaniSttClient = client
        client.connectOnBackgroundThread()
        audioPipeline.start()
    }

    /**
     * Stop audio capture and close WebSocket stream.
     */
    private fun stopListening() {
        audioPipeline.stop()
        try {
            gnaniSttClient?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing STT client: ${e.message}", e)
        } finally {
            gnaniSttClient = null
        }
    }

    /**
     * Handle microphone runtime permission result.
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                btnPushToTalk.isEnabled = true
                Log.i(TAG, "RECORD_AUDIO permission granted by user.")
            } else {
                btnPushToTalk.isEnabled = false
                Toast.makeText(this, "Mic permission required", Toast.LENGTH_SHORT).show()
                Log.w(TAG, "RECORD_AUDIO permission denied by user.")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopListening()
        audioPipeline.stop()
        appOrchestrator.destroy()
    }
}
