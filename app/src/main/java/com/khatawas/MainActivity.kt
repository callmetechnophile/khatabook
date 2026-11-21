/*
 * Copyright (c) 2026 KHATAWAS Project
 * Main voice-ledger screen with visible connection, real-time transcript, and saved-entry feedback.
 */

package com.khatawas

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.khatawas.audio.SttState
import com.khatawas.core.AppOrchestrator

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

    private val mainHandler = Handler(Looper.getMainLooper())
    private var releaseTimeoutRunnable: Runnable? = null

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
            tvResult.text = "${transaction.name}  ·  ₹${transaction.amountRupees}"
            tvResultDetail.text = "$direction  •  Date offset: ${transaction.dateOffset} day(s)"
            tvStatus.text = "Saved to your local encrypted ledger."
        }

        appOrchestrator.onBatchTransactionsSaved = { transactions, netBalancePaise ->
            val count = transactions.size
            tvResult.text = "${transactions.first().name}  ·  $count entries"
            tvResultDetail.text = "Net outstanding balance: ₹${netBalancePaise / 100.0}"
            tvStatus.text = "Multiple transactions saved to local ledger."
        }

        appOrchestrator.onQueryResult = { customer, balancePaise ->
            tvResult.text = "$customer  ·  Balance"
            tvResultDetail.text = "Net outstanding: ₹${balancePaise / 100.0}"
            tvStatus.text = "Ledger query result."
        }

        appOrchestrator.onTransactionRejected = { reason ->
            tvStatus.text = reason
            tvResult.text = "Action required"
            tvResultDetail.text = "Review the spoken sentence or respond to clarification."
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
                    stopListening()
                    true
                }
                else -> false
            }
        }
    }

    private fun startListening() {
        releaseTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        cleanupClient()

        sessionActive = true
        val client = GnaniSttClient()
        gnaniSttClient = client

        client.setListener(object : SttListener {
            override fun onPartialTranscription(text: String) {
                runOnUiThread {
                    tvTranscript.text = text
                }
            }

            override fun onTranscriptionReceived(text: String) {
                runOnUiThread {
                    tvTranscript.text = text
                    tvStatus.text = "Transcript received: processing ledger entry…"
                    btnPushToTalk.text = "HOLD TO SPEAK"
                }
                appOrchestrator.processRealTransaction(text)
                mainHandler.postDelayed({ cleanupClient() }, 1200)
            }

            override fun onSttFailed() {
                runOnUiThread {
                    tvStatus.text = "Speech service failed. No entry was confirmed."
                    tvConnection.text = "●  Speech service error"
                    btnPushToTalk.text = "HOLD TO SPEAK"
                    cleanupClient()
                }
            }
        })

        client.onStateChanged = { state ->
            runOnUiThread {
                when (state) {
                    SttState.DISCONNECTED -> tvConnection.text = "●  Ready — press and hold to speak"
                    SttState.CONNECTING -> tvConnection.text = "●  Connecting to speech service…"
                    SttState.INITIALIZING -> tvConnection.text = "●  Initializing speech session…"
                    SttState.READY -> {
                        tvConnection.text = "●  Connected. Microphone active."
                        if (sessionActive) {
                            btnPushToTalk.text = "LISTENING…"
                            tvStatus.text = "Microphone active. Speak now."
                            audioPipeline.start()
                        }
                    }
                    SttState.STREAMING -> tvConnection.text = "●  Streaming speech to Gnani…"
                    SttState.CLOSING -> tvConnection.text = "●  Finalizing transcript…"
                    SttState.ERROR -> tvConnection.text = "●  Speech service error"
                }
            }
        }

        client.connectOnBackgroundThread()
    }

    private fun stopListening() {
        sessionActive = false
        audioPipeline.stop()

        val client = gnaniSttClient
        if (client != null && (client.currentState == SttState.READY || client.currentState == SttState.STREAMING)) {
            btnPushToTalk.text = "PROCESSING…"
            tvStatus.text = "Audio recorded. Transcribing with Gnani…"
            client.flushAndFinish()

            releaseTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            val timeoutRunnable = Runnable {
                if (gnaniSttClient == client) {
                    cleanupClient()
                    tvStatus.text = "Transcription timeout. Hold button to try again."
                    btnPushToTalk.text = "HOLD TO SPEAK"
                }
            }
            releaseTimeoutRunnable = timeoutRunnable
            mainHandler.postDelayed(timeoutRunnable, 4000)
        } else {
            cleanupClient()
            btnPushToTalk.text = "HOLD TO SPEAK"
        }
    }

    private fun cleanupClient() {
        releaseTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        releaseTimeoutRunnable = null
        val client = gnaniSttClient
        gnaniSttClient = null
        try {
            client?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing STT client: ${e.message}", e)
        }
        tvConnection.text = "●  Ready — press and hold to speak"
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
        sessionActive = false
        audioPipeline.stop()
        cleanupClient()
        appOrchestrator.destroy()
        super.onDestroy()
    }
}
