/*
 * Copyright (c) 2026 KHATAWAS Project
 * Simplified Technical English (ASD STE-100) compliant.
 * Cryptographic event integrity, deterministic canonical serialization,
 * and append-only hash chaining for tamper-evident ledger events.
 */

package com.khatawas.db

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object EventIntegrity {

    const val CURRENT_SCHEMA_VERSION = 1
    const val GENESIS_HASH = "0000000000000000000000000000000000000000000000000000000000000000"

    /**
     * Creates a deterministic canonical JSON string representation of a ledger event.
     * Enforces strictly sorted keys, integer paise representation, exact UTF-8 encoding,
     * and standardized string escaping.
     */
    fun canonicalSerialize(
        eventId: String,
        customerName: String,
        amountPaise: Long,
        type: String,
        timestamp: Long,
        note: String = "",
        previousEventHash: String = GENESIS_HASH,
        schemaVersion: Int = CURRENT_SCHEMA_VERSION
    ): String {
        val safeCust = escapeJsonString(customerName.trim())
        val safeNote = escapeJsonString(note.trim())
        val safeType = type.trim().uppercase()
        val safePrev = if (previousEventHash.isBlank()) GENESIS_HASH else previousEventHash.trim()

        return "{\"amount_paise\":$amountPaise,\"customer_name\":\"$safeCust\",\"event_id\":\"$eventId\",\"note\":\"$safeNote\",\"previous_event_hash\":\"$safePrev\",\"schema_version\":$schemaVersion,\"timestamp\":$timestamp,\"type\":\"$safeType\"}"
    }

    /**
     * Computes the SHA-256 hexadecimal digest of canonical serialized event text.
     */
    fun computeDigest(canonicalJson: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val hashBytes = md.digest(canonicalJson.toByteArray(StandardCharsets.UTF_8))
        val sb = StringBuilder(hashBytes.size * 2)
        for (b in hashBytes) {
            val i = b.toInt() and 0xFF
            if (i < 0x10) sb.append('0')
            sb.append(Integer.toHexString(i))
        }
        return sb.toString()
    }

    /**
     * Computes the event digest directly from the event parameters.
     */
    fun computeEventDigest(
        eventId: String,
        customerName: String,
        amountPaise: Long,
        type: String,
        timestamp: Long,
        note: String = "",
        previousEventHash: String = GENESIS_HASH,
        schemaVersion: Int = CURRENT_SCHEMA_VERSION
    ): Pair<String, String> {
        val canonical = canonicalSerialize(
            eventId, customerName, amountPaise, type, timestamp, note, previousEventHash, schemaVersion
        )
        val digest = computeDigest(canonical)
        return Pair(canonical, digest)
    }

    /**
     * Verifies that the given canonical JSON string produces the expected SHA-256 digest.
     */
    fun verifyDigest(canonicalJson: String, expectedDigest: String): Boolean {
        val actual = computeDigest(canonicalJson)
        return actual.equals(expectedDigest.trim(), ignoreCase = true)
    }

    /**
     * Verifies that the given event fields produce the expected SHA-256 digest.
     */
    fun verifyDigest(
        eventId: String,
        customerName: String,
        amountPaise: Long,
        type: String,
        timestamp: Long,
        previousEventHash: String,
        note: String = "",
        expectedDigest: String,
        schemaVersion: Int = CURRENT_SCHEMA_VERSION
    ): Boolean {
        val canonical = canonicalSerialize(
            eventId = eventId,
            customerName = customerName,
            amountPaise = amountPaise,
            type = type,
            timestamp = timestamp,
            note = note,
            previousEventHash = previousEventHash,
            schemaVersion = schemaVersion
        )
        return verifyDigest(canonical, expectedDigest)
    }

    private fun escapeJsonString(input: String): String {
        val sb = StringBuilder()
        for (c in input) {
            when (c) {
                '\"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (c.code in 0x00..0x1F) {
                        sb.append(String.format("\\u%04x", c.code))
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
        return sb.toString()
    }
}
