/*
 * Copyright (c) 2026 KHATAWAS Project
 * Simplified Technical English (ASD STE-100) compliant.
 * On-Device Chatbot Intent and Query Structure Extractor using ONNX Runtime.
 */

package com.khatawas.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.LongBuffer
import java.util.Locale

class ChatbotModelInference(private val context: Context) {

    companion object {
        private const val TAG = "KHATAWAS_CHATBOT_AI"
        private const val MODEL_ASSET = "models/khatawas_chatbot_int8.onnx"
        private const val MAX_SEQ_LEN = 64

        val INTENT_CLASSES = listOf(
            "GET_CUSTOMER_BALANCE",
            "GET_CUSTOMER_HISTORY",
            "GET_CUSTOMER_LAST_PAYMENT",
            "LIST_OUTSTANDING_CUSTOMERS",
            "GET_TOTAL_OUTSTANDING",
            "GET_PERIOD_SUMMARY",
            "LIST_CATEGORY_TRANSACTIONS",
            "GET_TRANSACTIONS_BY_DATE",
            "GET_TOP_DEBTOR",
            "FOLLOW_UP_QUERY",
            "UNKNOWN"
        )

        val DATE_CLASSES = listOf("NONE", "TODAY", "YESTERDAY", "THIS_WEEK", "THIS_MONTH", "LAST_MONTH")
        val CATEGORY_CLASSES = listOf("NONE", "CREDIT", "DEBIT", "STUCK", "ILLEGAL")
        val RELATION_CLASSES = listOf("NONE", "FATHER", "BROTHER", "SON", "FAMILY")
    }

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var tokenizer: KhatawasTokenizer? = null
    private var isInitialized = false

    @Synchronized
    fun initialize(): Boolean {
        if (isInitialized) return true
        return try {
            tokenizer = KhatawasTokenizer.loadFromAssets(context)
            val modelFile = copyAssetToInternalStorage(MODEL_ASSET)
            if (modelFile.exists() && modelFile.length() > 0) {
                ortEnv = OrtEnvironment.getEnvironment()
                val sessionOpts = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(2)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                }
                ortSession = ortEnv?.createSession(modelFile.absolutePath, sessionOpts)
                isInitialized = true
                Log.i(TAG, "Chatbot ONNX Runtime model loaded successfully (${modelFile.length()} bytes)")
                true
            } else {
                Log.w(TAG, "Chatbot ONNX asset missing; falling back to rule-based extractor.")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing Chatbot ONNX model: ${e.message}", e)
            false
        }
    }

    /**
     * Extract structured intent, entities, and flags from user text query.
     */
    fun extractStructuredIntent(queryText: String): StructuredQueryIntent {
        val trimmed = queryText.trim()
        if (trimmed.isEmpty()) {
            return StructuredQueryIntent(
                intent = "UNKNOWN",
                requiresClarification = true,
                clarificationPrompt = "Please ask a question about your ledger records."
            )
        }

        // Run local edge model if initialized
        if (isInitialized && ortSession != null && tokenizer != null) {
            try {
                val tok = tokenizer!!
                val encoded = tok.encode(trimmed, MAX_SEQ_LEN)
                val env = ortEnv ?: OrtEnvironment.getEnvironment()

                val shape = longArrayOf(1, MAX_SEQ_LEN.toLong())
                val inBuffer = LongBuffer.wrap(encoded.inputIds)
                val maskBuffer = LongBuffer.wrap(encoded.attentionMask)

                val inTensor = OnnxTensor.createTensor(env, inBuffer, shape)
                val maskTensor = OnnxTensor.createTensor(env, maskBuffer, shape)

                val inputs = mapOf("input_ids" to inTensor, "attention_mask" to maskTensor)
                val results = ortSession!!.run(inputs)

                var intentIdx = 0
                var clarIdx = 0
                var dateIdx = 0
                var catIdx = 0
                var relIdx = 0

                val intentRaw = results.get(0).value
                if (intentRaw is Array<*> && intentRaw.isNotEmpty() && intentRaw[0] is FloatArray) {
                    intentIdx = argmax(intentRaw[0] as FloatArray)
                }

                val clarRaw = results.get(1).value
                if (clarRaw is Array<*> && clarRaw.isNotEmpty() && clarRaw[0] is FloatArray) {
                    clarIdx = argmax(clarRaw[0] as FloatArray)
                }

                val dateRaw = results.get(2).value
                if (dateRaw is Array<*> && dateRaw.isNotEmpty() && dateRaw[0] is FloatArray) {
                    dateIdx = argmax(dateRaw[0] as FloatArray)
                }

                val catRaw = results.get(3).value
                if (catRaw is Array<*> && catRaw.isNotEmpty() && catRaw[0] is FloatArray) {
                    catIdx = argmax(catRaw[0] as FloatArray)
                }

                val relRaw = results.get(4).value
                if (relRaw is Array<*> && relRaw.isNotEmpty() && relRaw[0] is FloatArray) {
                    relIdx = argmax(relRaw[0] as FloatArray)
                }

                inTensor.close()
                maskTensor.close()
                results.close()

                val predIntent = INTENT_CLASSES.getOrElse(intentIdx) { "GET_CUSTOMER_BALANCE" }
                val requiresClarification = (clarIdx == 1)
                val predDate = DATE_CLASSES.getOrElse(dateIdx) { "NONE" }
                val predCat = CATEGORY_CLASSES.getOrElse(catIdx) { "NONE" }
                val predRel = RELATION_CLASSES.getOrElse(relIdx) { "NONE" }

                // Extract customer name via regex / heuristics
                val extractedName = extractCustomerNameHeuristic(trimmed, predRel)

                return StructuredQueryIntent(
                    intent = predIntent,
                    customerName = extractedName,
                    relation = predRel,
                    dateFilter = predDate,
                    categoryFilter = predCat,
                    requiresClarification = requiresClarification
                )
            } catch (e: Exception) {
                Log.w(TAG, "Chatbot model inference failed, using rule-based fallback: ${e.message}")
            }
        }

        // Deterministic Fallback Extractor
        return extractIntentFallback(trimmed)
    }

    private fun extractIntentFallback(text: String): StructuredQueryIntent {
        val lower = text.lowercase(Locale.ROOT)

        // Ambiguous ungrounded reference check
        if (lower.startsWith("uska ") || lower.startsWith("uske ") || 
            lower.contains("his balance") || lower.contains("how much does he owe") ||
            lower.contains("avara baki") || lower == "what is his balance?") {
            return StructuredQueryIntent(
                intent = "GET_CUSTOMER_BALANCE",
                customerName = null,
                requiresClarification = true,
                clarificationPrompt = "Which customer are you referring to?"
            )
        }

        // Out of domain check
        if (lower.contains("weather") || lower.contains("joke") || lower.contains("cricket") || lower.contains("chutkula") || lower.contains("mausam")) {
            return StructuredQueryIntent(
                intent = "UNKNOWN",
                requiresClarification = false
            )
        }

        // Follow up check
        if (lower.contains("previous") || lower.contains("pichle") || lower.contains("what about") || lower.contains("last month") || lower.contains("compare")) {
            val dateFilter = if (lower.contains("last month") || lower.contains("pichle mahine")) "LAST_MONTH" else if (lower.contains("this month")) "THIS_MONTH" else "NONE"
            return StructuredQueryIntent(
                intent = "FOLLOW_UP_QUERY",
                dateFilter = dateFilter,
                customerName = extractCustomerNameHeuristic(text, "NONE")
            )
        }

        // Top debtor check
        if (lower.contains("highest") || lower.contains("sabse jyada") || lower.contains("sabse adhik") || lower.contains("most money") || lower.contains("ಅತಿ ಹೆಚ್ಚು")) {
            return StructuredQueryIntent(intent = "GET_TOP_DEBTOR")
        }

        // Total outstanding check
        if (lower.contains("total") || lower.contains("kul बकाया") || lower.contains("ಒಟ್ಟು ಬಾಕಿ") || lower.contains("total balance")) {
            return StructuredQueryIntent(intent = "GET_TOTAL_OUTSTANDING")
        }

        // List outstanding customers
        if (lower.contains("who has not paid") || lower.contains("which customers") || lower.contains("kin grahko") || lower.contains("unpaid") || lower.contains("ಯಾವ ಗ್ರಾಹಕರ")) {
            return StructuredQueryIntent(intent = "LIST_OUTSTANDING_CUSTOMERS")
        }

        // Category queries
        if (lower.contains("stuck") || lower.contains("atke")) {
            return StructuredQueryIntent(intent = "LIST_CATEGORY_TRANSACTIONS", categoryFilter = "STUCK")
        }
        if (lower.contains("illegal") || lower.contains("avaidh") || lower.contains("ಅಕ್ರಮ")) {
            return StructuredQueryIntent(intent = "LIST_CATEGORY_TRANSACTIONS", categoryFilter = "ILLEGAL")
        }

        // Date queries
        if (lower.contains("yesterday") || lower.contains("kal ") || lower.contains("ನಿನ್ನೆ")) {
            return StructuredQueryIntent(intent = "GET_TRANSACTIONS_BY_DATE", dateFilter = "YESTERDAY")
        }

        // Today collection / period summary
        if (lower.contains("today") || lower.contains("aaj ") || lower.contains("ಇವತ್ತು")) {
            val cat = if (lower.contains("received") || lower.contains("mila") || lower.contains("collection")) "DEBIT" else if (lower.contains("credit") || lower.contains("udhar")) "CREDIT" else "NONE"
            return StructuredQueryIntent(intent = "GET_PERIOD_SUMMARY", dateFilter = "TODAY", categoryFilter = cat)
        }

        // This month credit
        if (lower.contains("this month") || lower.contains("is mahine") || lower.contains("ಈ ತಿಂಗಳು")) {
            return StructuredQueryIntent(intent = "GET_PERIOD_SUMMARY", dateFilter = "THIS_MONTH", categoryFilter = "CREDIT")
        }

        // History query
        if (lower.contains("history") || lower.contains("ithihas") || lower.contains("transactions") || lower.contains("hisab") || lower.contains("ಲೆಕ್ಕ")) {
            val rel = detectRelation(lower)
            val name = extractCustomerNameHeuristic(text, rel)
            return StructuredQueryIntent(intent = "GET_CUSTOMER_HISTORY", customerName = name, relation = rel)
        }

        // Last payment query
        if (lower.contains("last payment") || lower.contains("aakhiri bhugtan") || lower.contains("ಕೊನೆಯ ಪಾವತಿ")) {
            val name = extractCustomerNameHeuristic(text, "NONE")
            return StructuredQueryIntent(intent = "GET_CUSTOMER_LAST_PAYMENT", customerName = name)
        }

        // Default: Customer balance
        val rel = detectRelation(lower)
        val name = extractCustomerNameHeuristic(text, rel)
        return StructuredQueryIntent(intent = "GET_CUSTOMER_BALANCE", customerName = name, relation = rel)
    }

    private fun detectRelation(lowerText: String): String {
        return when {
            lowerText.contains("papa") || lowerText.contains("father") || lowerText.contains("pita") || lowerText.contains("thande") -> "FATHER"
            lowerText.contains("bhai") || lowerText.contains("brother") || lowerText.contains("sahodara") -> "BROTHER"
            lowerText.contains("beta") || lowerText.contains("son") || lowerText.contains("maga") -> "SON"
            lowerText.contains("family") || lowerText.contains("parivar") || lowerText.contains("kutumba") -> "FAMILY"
            else -> "NONE"
        }
    }

    private fun extractCustomerNameHeuristic(text: String, relation: String): String? {
        val clean = text.replace("?", "").replace("!", "").trim()

        // Match English: "What is Ravi's balance?", "Show Ravi's history", "How much does Ravi owe"
        val enMatch = Regex("""(?i)(?:what is|how much does|show|balance of)\s+([A-Za-z\u0900-\u097F\u0C80-\u0CFF]+(?:'s\s+\w+)?)""").find(clean)
        if (enMatch != null) {
            val raw = enMatch.groupValues[1].replace("'s", "").replace(" current balance", "").trim()
            if (raw.isNotEmpty() && !isStopword(raw)) return raw
        }

        // Match Hindi: "रवि का बकाया कितना है", "सुरेश के पापा का हिसाब"
        val hiMatch = Regex("""([\u0900-\u097F\u0C80-\u0CFF\w]+(?:\s+के\s+पापा|\s+के\s+पिता|\s+ತಂದೆ)?)\s+(?:का|के|की|पर|ಅವರ|baki|ka)""").find(clean)
        if (hiMatch != null) {
            val raw = hiMatch.groupValues[1].trim()
            if (raw.isNotEmpty() && !isStopword(raw)) return raw
        }

        // First capitalized word or Indic word
        val words = clean.split(Regex("""\s+"""))
        for (w in words) {
            val cleanW = w.replace("'s", "").replace("?", "").trim()
            if (cleanW.length >= 3 && !isStopword(cleanW) && (cleanW[0].isUpperCase() || cleanW[0].code >= 0x0900)) {
                return if (relation == "FATHER") "$cleanW's father" else cleanW
            }
        }
        return null
    }

    private fun isStopword(w: String): Boolean {
        val s = w.lowercase(Locale.ROOT)
        val stopwords = setOf(
            "what", "is", "my", "the", "how", "much", "does", "show", "give", "me", "a",
            "kya", "hai", "kitna", "ka", "ke", "ki", "mera", "batao", "dikhao",
            "eshtu", "ide", "thorisi", "yava", "ivattu", "today", "yesterday", "summary"
        )
        return stopwords.contains(s)
    }

    private fun argmax(arr: FloatArray): Int {
        var maxIdx = 0
        var maxVal = arr[0]
        for (i in 1 until arr.size) {
            if (arr[i] > maxVal) {
                maxVal = arr[i]
                maxIdx = i
            }
        }
        return maxIdx
    }

    private fun copyAssetToInternalStorage(assetPath: String): File {
        val file = File(context.filesDir, File(assetPath).name)
        if (!file.exists() || file.length() == 0L) {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
        }
        return file
    }
}
