/*
 * Copyright (c) 2026 KHATAWAS Project
 * Simplified Technical English (ASD STE-100) compliant.
 * Core Conversational Chatbot Engine unifying Edge Intent Model, Unified Database Query Layer, and Encrypted History.
 */

package com.khatawas.ai

import android.content.Context
import android.util.Log
import com.khatawas.auth.PrivacyManager
import com.khatawas.db.ChatMessageEntity
import com.khatawas.db.LedgerDatabase
import java.util.UUID

data class ChatbotResponse(
    val userMessage: ChatMessageEntity,
    val assistantMessage: ChatMessageEntity,
    val requiresClarification: Boolean = false,
    val isConflict: Boolean = false
)

/**
 * Main coordinator for KHATAWAS Private Database-Grounded Chatbot.
 */
class ChatbotEngine(
    private val context: Context,
    private val ledgerDatabase: LedgerDatabase
) {
    companion object {
        private const val TAG = "KHATAWAS_CHATBOT_ENGINE"
        const val DEFAULT_CONVERSATION_ID = "conv:default_session"
    }

    private val modelInference = ChatbotModelInference(context)
    private val unifiedQueryService = UnifiedLedgerQueryService(context, ledgerDatabase)
    private val queryPlanner = QueryPlanner(context, ledgerDatabase, unifiedQueryService)
    val voiceAssistant = ChatbotVoiceAssistant(context)

    fun initialize(): Boolean {
        return modelInference.initialize()
    }

    /**
     * Process an incoming user query (from text or speech transcript) and generate a grounded answer.
     */
    fun processQuery(
        queryText: String,
        modality: String = "TEXT",
        conversationId: String = DEFAULT_CONVERSATION_ID
    ): ChatbotResponse {
        val now = System.currentTimeMillis()
        val userMsgId = UUID.randomUUID().toString()

        // 1. Create and persist user message in encrypted database
        val userMessage = ChatMessageEntity(
            conversationId = conversationId,
            messageId = userMsgId,
            role = "user",
            content = queryText.trim(),
            modality = modality,
            timestamp = now
        )
        ledgerDatabase.insertChatMessage(userMessage)

        // 2. Load recent conversation history for contextual follow-ups
        val recentContext = ledgerDatabase.getConversationMessages(conversationId, limit = 10)

        // 3. Extract structured query intent using Edge Model
        val structuredIntent = modelInference.extractStructuredIntent(queryText)
        Log.i(TAG, "Extracted Query Intent: ${structuredIntent.intent}, Customer: ${structuredIntent.customerName}, Filter: ${structuredIntent.dateFilter}")

        // 4. Plan and execute authorized read-only query
        val groundedAnswer = queryPlanner.planAndExecute(structuredIntent, recentContext)

        // 5. Apply privacy masking if application is currently locked
        var finalContent = groundedAnswer.answerText
        if (!PrivacyManager.isUnlocked) {
            finalContent = maskSensitiveFinancialDetails(finalContent)
        }

        // 6. Create and persist assistant message in encrypted database
        val assistantMsgId = UUID.randomUUID().toString()
        val assistantMessage = ChatMessageEntity(
            conversationId = conversationId,
            messageId = assistantMsgId,
            role = "assistant",
            content = finalContent,
            modality = modality,
            timestamp = System.currentTimeMillis(),
            sourceMetadata = groundedAnswer.sourceIndicator,
            retrievedEventIds = groundedAnswer.retrievedEventIds.joinToString(","),
            isConflict = groundedAnswer.isConflict
        )
        ledgerDatabase.insertChatMessage(assistantMessage)

        // 7. If audio modality and user consented, trigger TTS playback
        if (modality == "AUDIO" && PrivacyManager.isUnlocked) {
            voiceAssistant.speakResponse(groundedAnswer.answerText)
        }

        return ChatbotResponse(
            userMessage = userMessage,
            assistantMessage = assistantMessage,
            requiresClarification = groundedAnswer.requiresClarification,
            isConflict = groundedAnswer.isConflict
        )
    }

    /**
     * Retrieve all messages for a conversation, applying privacy masking if locked.
     */
    fun getConversationHistory(conversationId: String = DEFAULT_CONVERSATION_ID): List<ChatMessageEntity> {
        val rawMessages = ledgerDatabase.getConversationMessages(conversationId)
        val isLocked = !PrivacyManager.isUnlocked

        return if (isLocked) {
            rawMessages.map { msg ->
                if (msg.role == "assistant") {
                    msg.copy(content = maskSensitiveFinancialDetails(msg.content))
                } else {
                    msg
                }
            }
        } else {
            rawMessages
        }
    }

    /**
     * Clear conversation history.
     */
    fun clearConversation(conversationId: String = DEFAULT_CONVERSATION_ID): Boolean {
        voiceAssistant.stopPlayback()
        return ledgerDatabase.clearConversation(conversationId)
    }

    private fun maskSensitiveFinancialDetails(text: String): String {
        // Mask currency amounts (₹1,250 or Rs. 500) with ###XXX
        val currencyRegex = Regex("""(?:₹|Rs\.?\s*)\s*[\d,]+(?:\.\d{1,2})?""")
        return currencyRegex.replace(text, "###XXX")
    }
}
