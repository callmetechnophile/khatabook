/*
 * Copyright (c) 2026 KHATAWAS Project
 * Simplified Technical English (ASD STE-100) compliant.
 * Dedicated Private Database-Grounded AI Chatbot Activity with Gnani Text and Voice Conversations.
 */

package com.khatawas.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.khatawas.R
import com.khatawas.ai.ChatbotEngine
import com.khatawas.ai.ChatbotVoiceAssistant
import com.khatawas.ai.VoiceAssistantListener
import com.khatawas.auth.BiometricAuthHelper
import com.khatawas.auth.PrivacyManager
import com.khatawas.db.LedgerDatabase
import java.util.concurrent.Executors

class ChatbotActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_RECORD_AUDIO = 201
    }

    private lateinit var ledgerDatabase: LedgerDatabase
    private lateinit var chatbotEngine: ChatbotEngine
    private lateinit var messageAdapter: ChatMessageAdapter

    private lateinit var btnBack: ImageButton
    private lateinit var btnClearChat: ImageButton
    private lateinit var llAudioConsentBanner: LinearLayout
    private lateinit var btnAcceptAudioConsent: Button
    private lateinit var llLockedBanner: LinearLayout
    private lateinit var btnUnlockChat: Button
    private lateinit var rvChatMessages: RecyclerView
    private lateinit var llSpeechPreview: LinearLayout
    private lateinit var tvSpeechPreviewText: TextView
    private lateinit var btnCancelSpeech: ImageButton
    private lateinit var btnMic: ImageButton
    private lateinit var etQueryInput: EditText
    private lateinit var btnSend: Button

    private val mainHandler = Handler(Looper.getMainLooper())
    private val workerExecutor = Executors.newSingleThreadExecutor()
    private var isRecording = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chatbot)

        ledgerDatabase = LedgerDatabase(this)
        chatbotEngine = ChatbotEngine(this, ledgerDatabase)

        initViews()
        setupRecyclerView()
        setupListeners()

        workerExecutor.execute {
            chatbotEngine.initialize()
            loadConversationHistory()
        }

        updatePrivacyBanners()
    }

    private fun initViews() {
        btnBack = findViewById(R.id.btn_back)
        btnClearChat = findViewById(R.id.btn_clear_chat)
        llAudioConsentBanner = findViewById(R.id.ll_audio_consent_banner)
        btnAcceptAudioConsent = findViewById(R.id.btn_accept_audio_consent)
        llLockedBanner = findViewById(R.id.ll_locked_banner)
        btnUnlockChat = findViewById(R.id.btn_unlock_chat)
        rvChatMessages = findViewById(R.id.rv_chat_messages)
        llSpeechPreview = findViewById(R.id.ll_speech_preview)
        tvSpeechPreviewText = findViewById(R.id.tv_speech_preview_text)
        btnCancelSpeech = findViewById(R.id.btn_cancel_speech)
        btnMic = findViewById(R.id.btn_mic)
        etQueryInput = findViewById(R.id.et_query_input)
        btnSend = findViewById(R.id.btn_send)
    }

    private fun setupRecyclerView() {
        messageAdapter = ChatMessageAdapter()
        val layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        rvChatMessages.layoutManager = layoutManager
        rvChatMessages.adapter = messageAdapter
    }

    private fun setupListeners() {
        btnBack.setOnClickListener {
            finish()
        }

        btnClearChat.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear Conversation")
                .setMessage("Are you sure you want to delete all messages in this conversation?")
                .setPositiveButton("Clear") { _, _ ->
                    workerExecutor.execute {
                        chatbotEngine.clearConversation()
                        loadConversationHistory()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        btnSend.setOnClickListener {
            val query = etQueryInput.text.toString().trim()
            if (query.isNotEmpty()) {
                submitQuery(query, modality = "TEXT")
                etQueryInput.setText("")
            }
        }

        btnAcceptAudioConsent.setOnClickListener {
            chatbotEngine.voiceAssistant.setUserConsent(true)
            llAudioConsentBanner.visibility = View.GONE
            Toast.makeText(this, "Remote voice consent recorded.", Toast.LENGTH_SHORT).show()
        }

        btnUnlockChat.setOnClickListener {
            if (BiometricAuthHelper.canAuthenticateWithBiometrics(this)) {
                BiometricAuthHelper.authenticate(
                    activity = this,
                    title = "Unlock KHATAWAS",
                    subtitle = "Verify fingerprint or face to unmask financial records",
                    onSuccess = {
                        runOnUiThread {
                            updatePrivacyBanners()
                            loadConversationHistory()
                            Toast.makeText(this@ChatbotActivity, "Financial details unlocked.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onError = { err ->
                        runOnUiThread {
                            Toast.makeText(this@ChatbotActivity, "Authentication failed: $err", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onUsePinFallback = {
                        runOnUiThread {
                            Toast.makeText(this@ChatbotActivity, "Please unlock from main screen using PIN.", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            } else {
                Toast.makeText(this, "Biometrics not available. Please unlock from main screen.", Toast.LENGTH_SHORT).show()
            }
        }

        btnMic.setOnClickListener {
            if (isRecording) {
                stopVoiceCapture()
            } else {
                startVoiceCapture()
            }
        }

        btnCancelSpeech.setOnClickListener {
            stopVoiceCapture()
        }

        chatbotEngine.voiceAssistant.setListener(object : VoiceAssistantListener {
            override fun onTranscriptPartial(text: String) {
                mainHandler.post {
                    tvSpeechPreviewText.text = text
                }
            }

            override fun onTranscriptFinal(text: String) {
                mainHandler.post {
                    stopVoiceCapture()
                    if (text.isNotEmpty()) {
                        submitQuery(text, modality = "AUDIO")
                    }
                }
            }

            override fun onVoiceError(errorMessage: String) {
                mainHandler.post {
                    stopVoiceCapture()
                    Toast.makeText(this@ChatbotActivity, errorMessage, Toast.LENGTH_LONG).show()
                }
            }

            override fun onTtsPlaybackStarted() {}
            override fun onTtsPlaybackCompleted() {}
        })
    }

    private fun startVoiceCapture() {
        if (!chatbotEngine.voiceAssistant.hasUserConsentedToRemoteAudio()) {
            showAudioConsentDialog()
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
            return
        }

        isRecording = true
        llSpeechPreview.visibility = View.VISIBLE
        tvSpeechPreviewText.text = "Listening to your question..."
        btnMic.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.holo_red_light)

        workerExecutor.execute {
            chatbotEngine.voiceAssistant.startListening()
        }
    }

    private fun stopVoiceCapture() {
        isRecording = false
        llSpeechPreview.visibility = View.GONE
        btnMic.backgroundTintList = ContextCompat.getColorStateList(this, R.color.bg_panel)
        workerExecutor.execute {
            chatbotEngine.voiceAssistant.stopListening()
        }
    }

    private fun showAudioConsentDialog() {
        AlertDialog.Builder(this)
            .setTitle("Gnani AI Voice Privacy Notice")
            .setMessage(ChatbotVoiceAssistant.PRIVACY_DISCLOSURE_TEXT)
            .setPositiveButton("I Consent") { _, _ ->
                chatbotEngine.voiceAssistant.setUserConsent(true)
                llAudioConsentBanner.visibility = View.GONE
                startVoiceCapture()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun submitQuery(queryText: String, modality: String) {
        workerExecutor.execute {
            chatbotEngine.processQuery(queryText, modality)
            loadConversationHistory()
        }
    }

    private fun loadConversationHistory() {
        val messages = chatbotEngine.getConversationHistory()
        mainHandler.post {
            messageAdapter.submitList(messages)
            if (messages.isNotEmpty()) {
                rvChatMessages.scrollToPosition(messages.size - 1)
            }
        }
    }

    private fun updatePrivacyBanners() {
        val isLocked = !PrivacyManager.isUnlocked
        llLockedBanner.visibility = if (isLocked) View.VISIBLE else View.GONE

        val hasAudioConsent = chatbotEngine.voiceAssistant.hasUserConsentedToRemoteAudio()
        llAudioConsentBanner.visibility = if (!hasAudioConsent) View.VISIBLE else View.GONE
    }

    override fun onDestroy() {
        super.onDestroy()
        chatbotEngine.voiceAssistant.stopPlayback()
        chatbotEngine.voiceAssistant.stopListening()
    }
}
