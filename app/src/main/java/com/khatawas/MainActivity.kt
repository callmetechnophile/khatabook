/*
 * Copyright (c) 2026 KHATAWAS Project
 * Main voice-ledger screen with visible connection, real-time transcript, saved-entry feedback,
 * offline synchronization telemetry, and first-launch AI disclosure.
 */

package com.khatawas
import android.content.Context
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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.khatawas.audio.AudioPipeline
import com.khatawas.audio.GnaniSttClient
import com.khatawas.audio.SttListener
import com.khatawas.audio.SttState
import com.khatawas.core.AppOrchestrator
import com.khatawas.sync.SyncManager

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "KHATAWAS_AUDIO"
        private const val REQUEST_RECORD_AUDIO = 101
        private const val PREFS_NAME = "khatawas_app_prefs"
        private const val KEY_AI_DISCLOSURE_SHOWN = "has_shown_ai_disclosure"
    }

    private lateinit var appOrchestrator: AppOrchestrator
    private lateinit var audioPipeline: AudioPipeline
    private lateinit var syncManager: SyncManager
    private var gnaniSttClient: GnaniSttClient? = null

    @Volatile
    private var sessionActive = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var releaseTimeoutRunnable: Runnable? = null

    private lateinit var btnPushToTalk: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvConnection: TextView
    private lateinit var tvSyncStatus: TextView
    private lateinit var tvTranscript: TextView
    private lateinit var tvResult: TextView
    private lateinit var tvResultDetail: TextView
    private lateinit var switchRnnoise: SwitchCompat
    private lateinit var btnAiDisclosure: Button
    private lateinit var btnPurgeData: Button

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnPushToTalk = findViewById(R.id.btn_push_to_talk)
        tvStatus = findViewById(R.id.tv_status)
        tvConnection = findViewById(R.id.tv_connection)
        tvSyncStatus = findViewById(R.id.tv_sync_status)
        tvTranscript = findViewById(R.id.tv_transcript)
        tvResult = findViewById(R.id.tv_result)
        tvResultDetail = findViewById(R.id.tv_result_detail)
        switchRnnoise = findViewById(R.id.switch_rnnoise)
        btnAiDisclosure = findViewById(R.id.btn_ai_disclosure)
        btnPurgeData = findViewById(R.id.btn_purge_data)

        // Phase 5 Runtime JNI Verification for Milestone 2
        runMilestone2Verification()
        // Phase 5 Runtime JNI Verification for Milestone 3 (Rust FIR Decimator)
        runMilestone3Verification()

        appOrchestrator = AppOrchestrator(this)
        audioPipeline = AudioPipeline(appOrchestrator.rnnoiseProcessor)
        syncManager = SyncManager(this, appOrchestrator.ledgerDatabase)

        syncManager.onSyncStatusChanged = { status ->
            runOnUiThread {
                tvSyncStatus.text = status
            }
        }
        syncManager.startSyncLoop()

        audioPipeline.onPcm16kReady = { chunk ->
            gnaniSttClient?.sendAudioChunk(chunk)
        }

        appOrchestrator.onTransactionSaved = { transaction ->
            val direction = if (transaction.type == "CREDIT") "Credit added" else if (transaction.type == "PAYMENT") "Payment received" else "Reversal processed"
            tvResult.text = "${transaction.name}  ·  ₹${transaction.amountRupees}"
            tvResultDetail.text = "$direction  •  Date offset: ${transaction.dateOffset} day(s)"
            tvStatus.text = "Saved to your local encrypted ledger."
            syncManager.triggerImmediateSync()
        }

        appOrchestrator.onBatchTransactionsSaved = { transactions, netBalancePaise ->
            val count = transactions.size
            tvResult.text = "${transactions.first().name}  ·  $count entries"
            tvResultDetail.text = "Net outstanding balance: ₹${netBalancePaise / 100.0}"
            tvStatus.text = "Multiple transactions saved to local ledger."
            syncManager.triggerImmediateSync()
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

        btnAiDisclosure.setOnClickListener {
            showAiDisclosureDialog(spoken = false)
        }

        btnPurgeData.setOnClickListener {
            showPurgeDataConfirmation()
        }

        checkFirstLaunchDisclosure()

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

    private fun checkFirstLaunchDisclosure() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val shown = prefs.getBoolean(KEY_AI_DISCLOSURE_SHOWN, false)
        if (!shown) {
            showAiDisclosureDialog(spoken = true)
            prefs.edit().putBoolean(KEY_AI_DISCLOSURE_SHOWN, true).apply()
        }
    }

    private fun showAiDisclosureDialog(spoken: Boolean) {
        val message = """
            KHATAWAS uses speech recognition to convert your spoken transactions into digital ledger entries.
            
            • Your financial ledger is stored locally in an encrypted database (SQLCipher AES-256).
            • Your voice data is used exclusively to transcribe transaction amounts and customer names.
            • We do not sell your data, use your transcripts for advertising, or share data for lending assessments.
            
            खातावास आपकी आवाज से बही-खाता लिखने के लिए स्पीच रिकग्निशन का उपयोग करता है। आपका डेटा स्थानीय रूप से एन्क्रिप्टेड है।
        """.trimIndent()

        MaterialAlertDialogBuilder(this)
            .setTitle("AI & Privacy Disclosure")
            .setMessage(message)
            .setPositiveButton("I Understand") { dialog, _ ->
                dialog.dismiss()
            }
            .show()

        if (spoken) {
            appOrchestrator.voiceFeedback.speak("खातावास में आपका स्वागत है। बोलकर बही-खाता लिखने के लिए बटन दबाकर रखें।")
        }
    }

    private fun showPurgeDataConfirmation() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Clear All Ledger Data?")
            .setMessage("This will permanently delete all customer names, transactions, and sync records from your local encrypted ledger. This action cannot be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear Data") { _, _ ->
                val success = appOrchestrator.ledgerDatabase.purgeAllData()
                if (success) {
                    tvResult.text = "All data cleared"
                    tvResultDetail.text = "Ledger and customer database has been reset."
                    tvStatus.text = "Local database purged successfully."
                    tvTranscript.text = "Nothing recognized yet."
                    syncManager.triggerImmediateSync()
                    Toast.makeText(this, "Data purged successfully", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Failed to purge data", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
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

    private fun runMilestone2Verification() {
        Log.i("KHATAWAS_M2_VERIFICATION", "=== BEGIN MILESTONE 2 JNI RUNTIME VERIFICATION ===")
        val isLoaded = com.khatawas.rust.RustEngine.isNativeLoaded()
        Log.i("KHATAWAS_M2_VERIFICATION", "Test A [Library Loading]: isNativeLoaded = $isLoaded")

        if (isLoaded) {
            try {
                // Test B: Native Version
                val version = com.khatawas.rust.RustEngine.nativeGetVersion()
                Log.i("KHATAWAS_M2_VERIFICATION", "Test B [Native Version]: $version")

                // Test C: Native Arithmetic
                val addStandard = com.khatawas.rust.RustEngine.nativeAdd(20, 22)
                val addNegative = com.khatawas.rust.RustEngine.nativeAdd(-5, 5)
                val addBoundary = com.khatawas.rust.RustEngine.nativeAdd(Int.MAX_VALUE, 1)
                Log.i("KHATAWAS_M2_VERIFICATION", "Test C [Native Arithmetic]: 20 + 22 = $addStandard, -5 + 5 = $addNegative, MAX + 1 = $addBoundary")

                // Test D: Native Health Check
                val health = com.khatawas.rust.RustEngine.nativeHealthCheck()
                Log.i("KHATAWAS_M2_VERIFICATION", "Test D [Health Check]: $health")

                // Test E: Repeated Invocation Stability (1,000 iterations)
                var allCorrect = true
                for (i in 0 until 1000) {
                    if (com.khatawas.rust.RustEngine.nativeAdd(i, 1) != i + 1) {
                        allCorrect = false
                        break
                    }
                }
                Log.i("KHATAWAS_M2_VERIFICATION", "Test E [Repeated Invocation]: 1000 iterations successful = $allCorrect")
            } catch (e: Throwable) {
                Log.e("KHATAWAS_M2_VERIFICATION", "JNI Execution Exception", e)
            }
        }
        Log.i("KHATAWAS_M2_VERIFICATION", "=== END MILESTONE 2 JNI RUNTIME VERIFICATION ===")
    }

    private fun runMilestone3Verification() {
        Log.i("KHATAWAS_M3_VERIFICATION", "=== BEGIN MILESTONE 3 FIR RUNTIME VERIFICATION ===")
        try {
            val rustFir = com.khatawas.audio.RustFirDecimator()
            Log.i("KHATAWAS_M3_VERIFICATION", "RustFirDecimator initialized, isNativeActive = ${rustFir.isNativeActive}")

            val input = FloatArray(480) { 1000.0f }
            val output = FloatArray(160)
            val written = rustFir.process(input, output)
            Log.i("KHATAWAS_M3_VERIFICATION", "Processed 480 input samples -> $written output samples, out[0]=${output[0]}, out[159]=${output[159]}")

            rustFir.reset()
            Log.i("KHATAWAS_M3_VERIFICATION", "RustFirDecimator reset successful")
            rustFir.close()
            Log.i("KHATAWAS_M3_VERIFICATION", "RustFirDecimator closed cleanly, isNativeActive = ${rustFir.isNativeActive}")
        } catch (e: Throwable) {
            Log.e("KHATAWAS_M3_VERIFICATION", "Milestone 3 runtime verification failed", e)
        }
        Log.i("KHATAWAS_M3_VERIFICATION", "=== END MILESTONE 3 FIR RUNTIME VERIFICATION ===")
    }

    override fun onDestroy() {
        sessionActive = false
        audioPipeline.stop()
        cleanupClient()
        syncManager.shutdown()
        appOrchestrator.destroy()
        super.onDestroy()
    }
}
