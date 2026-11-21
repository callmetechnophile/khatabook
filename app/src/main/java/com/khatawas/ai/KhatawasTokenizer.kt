/*
 * Copyright (c) 2026 KHATAWAS Project
 * Fast multilingual tokenizer for on-device Android inference.
 * Matches the student model vocabulary (Devanagari, Kannada, Latin scripts).
 */

package com.khatawas.ai

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

class KhatawasTokenizer(
    val vocabMap: Map<String, Long>,
    val padId: Long = 0L,
    val unkId: Long = 1L,
    val clsId: Long = 2L,
    val sepId: Long = 3L
) {
    companion object {
        private const val MAX_SEQ_LEN = 32

        fun loadFromAssets(context: Context, assetPath: String = "models/vocab.json"): KhatawasTokenizer {
            val jsonString = context.assets.open(assetPath).bufferedReader().use { it.readText() }
            val json = JSONObject(jsonString)
            val map = HashMap<String, Long>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = json.getLong(key)
            }
            return KhatawasTokenizer(
                vocabMap = map,
                padId = map["[PAD]"] ?: 0L,
                unkId = map["[UNK]"] ?: 1L,
                clsId = map["[CLS]"] ?: 2L,
                sepId = map["[SEP]"] ?: 3L
            )
        }
    }

    data class EncodedInput(
        val inputIds: LongArray,
        val attentionMask: LongArray,
        val tokens: List<String>
    )

    fun encode(text: String, maxLen: Int = MAX_SEQ_LEN): EncodedInput {
        // Unicode-aware regex matching words (letters + marks + numbers) or single symbols
        val regex = Regex("[\\p{L}\\p{M}\\p{N}]+|[^\\s\\p{L}\\p{M}\\p{N}]")
        val rawTokens = regex.findAll(text).map { it.value }.toList()

        val tokenIds = ArrayList<Long>()
        val cleanTokens = ArrayList<String>()

        tokenIds.add(clsId)
        cleanTokens.add("[CLS]")

        for (token in rawTokens) {
            val exactId = vocabMap[token]
            if (exactId != null) {
                tokenIds.add(exactId)
                cleanTokens.add(token)
            } else {
                val lowerId = vocabMap[token.lowercase()]
                if (lowerId != null) {
                    tokenIds.add(lowerId)
                    cleanTokens.add(token.lowercase())
                } else {
                    // Character fallback
                    for (c in token) {
                        val cid = vocabMap[c.toString()] ?: unkId
                        tokenIds.add(cid)
                        cleanTokens.add(c.toString())
                    }
                }
            }
        }

        tokenIds.add(sepId)
        cleanTokens.add("[SEP]")

        val finalIds = LongArray(maxLen) { padId }
        val finalMask = LongArray(maxLen) { 0L }

        if (tokenIds.size > maxLen) {
            for (i in 0 until (maxLen - 1)) {
                finalIds[i] = tokenIds[i]
                finalMask[i] = 1L
            }
            finalIds[maxLen - 1] = sepId
            finalMask[maxLen - 1] = 1L
        } else {
            for (i in tokenIds.indices) {
                finalIds[i] = tokenIds[i]
                finalMask[i] = 1L
            }
        }

        return EncodedInput(
            inputIds = finalIds,
            attentionMask = finalMask,
            tokens = cleanTokens.take(maxLen)
        )
    }
}
