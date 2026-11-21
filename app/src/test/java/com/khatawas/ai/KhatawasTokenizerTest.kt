/*
 * Copyright (c) 2026 KHATAWAS Project
 * Unit tests for KhatawasTokenizer.
 */

package com.khatawas.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KhatawasTokenizerTest {

    private val sampleVocab = mapOf(
        "[PAD]" to 0L,
        "[UNK]" to 1L,
        "[CLS]" to 2L,
        "[SEP]" to 3L,
        "रवि" to 4L,
        "को" to 5L,
        "500" to 6L,
        "रुपये" to 7L,
        "उधार" to 8L,
        "ದಿನ" to 9L,
        "ಸಾಲ" to 10L,
        "ravi" to 11L,
        "credit" to 12L
    )

    private val tokenizer = KhatawasTokenizer(
        vocabMap = sampleVocab,
        padId = 0L,
        unkId = 1L,
        clsId = 2L,
        sepId = 3L
    )

    @Test
    fun testHindiTokenization() {
        val encoded = tokenizer.encode("रवि को 500 रुपये उधार", maxLen = 16)
        assertEquals(2L, encoded.inputIds[0]) // [CLS]
        assertEquals(4L, encoded.inputIds[1]) // रवि
        assertEquals(5L, encoded.inputIds[2]) // को
        assertEquals(6L, encoded.inputIds[3]) // 500
        assertEquals(7L, encoded.inputIds[4]) // रुपये
        assertEquals(8L, encoded.inputIds[5]) // उधार
        assertEquals(3L, encoded.inputIds[6]) // [SEP]
        assertEquals(0L, encoded.inputIds[7]) // [PAD]

        assertEquals(1L, encoded.attentionMask[0])
        assertEquals(1L, encoded.attentionMask[6])
        assertEquals(0L, encoded.attentionMask[7])
    }

    @Test
    fun testEnglishTokenization() {
        val encoded = tokenizer.encode("Ravi credit 500", maxLen = 8)
        assertEquals(2L, encoded.inputIds[0]) // [CLS]
        assertEquals(11L, encoded.inputIds[1]) // ravi (case-insensitive fallback)
        assertEquals(12L, encoded.inputIds[2]) // credit
        assertEquals(6L, encoded.inputIds[3]) // 500
        assertEquals(3L, encoded.inputIds[4]) // [SEP]
        assertEquals(0L, encoded.inputIds[5]) // [PAD]
    }

    @Test
    fun testSequenceLengthClamping() {
        val encoded = tokenizer.encode("रवि को 500 रुपये उधार दिए और बाकी लिखा", maxLen = 6)
        assertEquals(6, encoded.inputIds.size)
        assertEquals(6, encoded.attentionMask.size)
        assertEquals(2L, encoded.inputIds[0]) // [CLS]
        assertEquals(3L, encoded.inputIds[5]) // [SEP] truncated end
    }
}
