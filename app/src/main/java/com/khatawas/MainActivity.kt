/*
 * Copyright (c) 2026 KHATAWAS Project
 * Main voice-ledger screen with visible connection, transcript, and saved-entry feedback.
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
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "KHATAWAS_AUDIO"
        private const val REQUEST_RECORD_AUDIO = 101
    }

    private lateinit var appOrchestrator: AppOrchestrator
    private lateinit var audioPipeline: AudioPipeline
    private var gnaniSttClient: GnaniSttClient? = null

    @Volatile
    private var sessionActive = false

    private lateinit var btnPushToTalk: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvConnection: TextView
    private lateinit var tvTranscript: TextView
    private lateinit var tvResult: TextView
    private lateinit var tvResultDetail: TextView
    private lateinit var switchRnnoise: SwitchCompat

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnPushToTalk = findViewById(R.id.btn_push_to_talk)
        tvStatus = findViewById(R.id.tv_status)
        tvConnection = findViewById(R.id.tv_connection)
        tvTranscript = findViewById(R.id.tv_transcript)
        tvResult = findViewById(R.id.tv_result)
        tvResultDetail = findViewById(R.id.tv_result_detail)
        switchRnnoise = findViewById(R.id.switch_rnnoise)

        appOrchestrator = AppOrchestrator(this)
        audioPipeline = AudioPipeline(appOrchestrator.rnnoiseProcessor)

        audioPipeline.onPcm16kReady = { chunk ->
            gnaniSttClient?.sendAudioChunk(chunk)
        }

        appOrchestrator.onTransactionSaved = { transaction ->
            val direction = if (transaction.type == "CREDIT") "Credit added" else "Payment received"
            tvResult.text = "${transaction.name}  ·  ₹${transaction.amount}"
            tvResultDetail.text = "$direction  •  Date offset: ${transaction.dateOffset} day(s)"
            tvStatus.text = "Saved to your local ledger."
        }

        appOrchestrator.onTransactionRejected = { reason ->
            tvStatus.text = reason
            tvResult.text = "No entry saved"
            tvResultDetail.text = "Review the transcript before trying again."
        }

        switchRnnoise.setOnCheckedChangeListener { _, isChecked ->
            audioPipeline.isRnnoiseEnabled = isChecked
            switchRnnoise.text = if (isChecked) "Noise filter" else "Noise filter off"
        }

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

        btnPushToTalk.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (!btnPushToTalk.isEnabled || sessionActive) return@setOnTouchListener true
                    btnPushToTalk.text = "CONNECTING…"
                    tvStatus.text = "Opening speech session. Keep holding the button."
                    tvTranscript.text = "Waiting for speech…"
                    tvConnection.text = "●  Connecting to speech service…"
                    startListening()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    btnPushToTalk.text = "HOLD TO SPEAK"
                    stopListening()
                    true
                }
                else -> false
            }
        }
    }

    private fun startListening() {
        sessionActive = true
        val client = GnaniSttClient()
        gnaniSttClient = client

        client.setListener(object : SttListener {
            override fun onTranscriptionReceived(text: String) {
                runOnUiThread {
                    tvTranscript.text = text
                    tvStatus.text = "Transcript received. Checking the ledger entry…"
                }
                appOrchestrator.processRealTransaction(text)
            }

            override fun onSttFailed() {
                runOnUiThread {
                    tvStatus.text = "Speech service failed. No entry was confirmed."
                    tvConnection.text = "●  Speech service error"
                }
            }
        })

        client.onConnectionState = { state ->
            runOnUiThread {
                tvConnection.text = "●  $state"
                if (state.startsWith("Connected") && sessionActive) {
                    btnPushToTalk.text = "LISTENING…"
                    tvStatus.text = "Microphone active. Speak now."
                    audioPipeline.start()
                }
            }
        }

        client.connectOnBackgroundThread()
    }

    private fun stopListening() {
        sessionActive = false
        audioPipeline.stop()

        val client = gnaniSttClient
        gnaniSttClient = null
        try {
            client?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing STT client: ${e.message}", e)
        }

        tvConnection.text = "●  Ready — press and hold to speak"
        tvStatus.text = "Recording stopped. Check the transcript and latest entry below."
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                btnPushToTalk.isEnabled = true
                tvStatus.text = "Microphone permission granted. Hold the button to speak."
            } else {
                btnPushToTalk.isEnabled = false
                tvStatus.text = "Microphone permission is required."
                Toast.makeText(this, "Mic permission required", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        stopListening()
        appOrchestrator.destroy()
        super.onDestroy()
    }
}
