/*
 * Copyright (c) 2026 KHATAWAS Project
 * Edge AI inference using ONNX Runtime for the distilled multilingual student model.
 *
 * SAFETY BOUNDARY:
 * This model strictly generates candidate predictions and interpretations.
 * It NEVER performs direct SQLite ledger writes, balance adjustments, or authorization.
 */

package com.khatawas.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.nio.LongBuffer

data class StudentPrediction(
    val category: String,
    val intent: String,
    val requiresClarification: Boolean,
    val customerRef: String?,
    val amountPaise: Long?,
    val latencyMs: Long
)

class StudentModelInference private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val tokenizer: KhatawasTokenizer
) : AutoCloseable {

    companion object {
        private const val TAG = "StudentModelInference"
        private val CATEGORY_LABELS = arrayOf("CREDIT", "DEBIT", "STUCK", "ILLEGAL")
        private val INTENT_LABELS = arrayOf(
            "RECORD_CREDIT",
            "RECORD_PAYMENT",
            "DISPUTE_STUCK",
            "ILLEGAL_FLAG",
            "CLARIFICATION_NEEDED"
        )
        private val BIO_TAGS = arrayOf(
            "O",
            "B-CUSTOMER",
            "I-CUSTOMER",
            "B-RELATIONSHIP",
            "I-RELATIONSHIP",
            "B-AMOUNT",
            "I-AMOUNT"
        )

        fun create(context: Context, modelAssetPath: String = "models/khatawas_student_int8.onnx"): StudentModelInference {
            val env = OrtEnvironment.getEnvironment()
            val sessionOptions = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val modelBytes = context.assets.open(modelAssetPath).use { it.readBytes() }
            val session = env.createSession(modelBytes, sessionOptions)
            val tokenizer = KhatawasTokenizer.loadFromAssets(context)
            Log.i(TAG, "StudentModelInference successfully loaded from $modelAssetPath")
            return StudentModelInference(env, session, tokenizer)
        }
    }

    @Synchronized
    fun infer(text: String): StudentPrediction {
        val startTime = SystemClock.elapsedRealtime()
        val encoded = tokenizer.encode(text)

        val shape = longArrayOf(1, encoded.inputIds.size.toLong())
        val inputIdsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(encoded.inputIds), shape)
        val maskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(encoded.attentionMask), shape)

        val inputs = mapOf(
            "input_ids" to inputIdsTensor,
            "attention_mask" to maskTensor
        )

        var predictedCategory = "CREDIT"
        var predictedIntent = "RECORD_CREDIT"
        var requiresClarification = false
        var customerRef: String? = null
        var amountPaise: Long? = null

        session.run(inputs).use { results ->
            // 1. Category Logits: [1, 4]
            val catLogits = (results.get(0).value as Array<FloatArray>)[0]
            val maxCatIdx = catLogits.indices.maxByOrNull { catLogits[it] } ?: 0
            predictedCategory = CATEGORY_LABELS[maxCatIdx]

            // 2. Intent Logits: [1, 5]
            val intLogits = (results.get(1).value as Array<FloatArray>)[0]
            val maxIntIdx = intLogits.indices.maxByOrNull { intLogits[it] } ?: 0
            predictedIntent = INTENT_LABELS[maxIntIdx]

            // 3. Clarification Logits: [1, 2]
            val clarLogits = (results.get(2).value as Array<FloatArray>)[0]
            requiresClarification = clarLogits[1] > clarLogits[0]

            // 4. Token BIO Logits: [1, seq_len, 7]
            val bioLogits = (results.get(3).value as Array<Array<FloatArray>>)[0]
            val customerTokens = mutableListOf<String>()
            val amountTokens = mutableListOf<String>()

            for (i in encoded.tokens.indices) {
                if (i >= bioLogits.size) break
                val tok = encoded.tokens[i]
                if (tok == "[CLS]" || tok == "[SEP]" || tok == "[PAD]") continue

                val tagScores = bioLogits[i]
                val maxTagIdx = tagScores.indices.maxByOrNull { tagScores[it] } ?: 0
                val tag = BIO_TAGS[maxTagIdx]

                if (tag.endsWith("CUSTOMER") || tag.endsWith("RELATIONSHIP")) {
                    customerTokens.add(tok)
                } else if (tag.endsWith("AMOUNT")) {
                    amountTokens.add(tok)
                }
            }

            if (customerTokens.isNotEmpty()) {
                customerRef = customerTokens.joinToString(" ")
            }
            if (amountTokens.isNotEmpty()) {
                val amtStr = amountTokens.joinToString("").replace(Regex("[^0-9.]"), "")
                val amtDouble = amtStr.toDoubleOrNull()
                if (amtDouble != null) {
                    amountPaise = (amtDouble * 100).toLong()
                }
            }
        }

        inputIdsTensor.close()
        maskTensor.close()

        val latency = SystemClock.elapsedRealtime() - startTime
        return StudentPrediction(
            category = predictedCategory,
            intent = predictedIntent,
            requiresClarification = requiresClarification,
            customerRef = customerRef,
            amountPaise = amountPaise,
            latencyMs = latency
        )
    }

    override fun close() {
        session.close()
        env.close()
    }
}
