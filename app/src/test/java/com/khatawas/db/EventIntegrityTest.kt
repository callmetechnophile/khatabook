/*
 * Copyright (c) 2026 KHATAWAS Project
 * Unit tests for EventIntegrity canonical deterministic serialization, SHA-256 event hashing, and hash chaining.
 */

package com.khatawas.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.nio.charset.StandardCharsets

class EventIntegrityTest {

    @Test
    fun testCanonicalSerializationFormatAndKeyOrdering() {
        val eventId = "test-uuid-1234"
        val customerName = "Ramesh"
        val amountPaise = 34000L
        val type = "CREDIT"
        val timestamp = 1732204800000L
        val prevHash = EventIntegrity.GENESIS_HASH
        val note = "Ramesh ka 340 udhaar"

        val canonical = EventIntegrity.canonicalSerialize(
            eventId = eventId,
            customerName = customerName,
            amountPaise = amountPaise,
            type = type,
            timestamp = timestamp,
            previousEventHash = prevHash,
            note = note,
            schemaVersion = 1
        )

        // Strict alphabetical key ordering verification:
        // "amount_paise" -> "customer_name" -> "event_id" -> "note" -> "previous_event_hash" -> "schema_version" -> "timestamp" -> "type"
        val expected = "{\"amount_paise\":34000,\"customer_name\":\"Ramesh\",\"event_id\":\"test-uuid-1234\",\"note\":\"Ramesh ka 340 udhaar\",\"previous_event_hash\":\"0000000000000000000000000000000000000000000000000000000000000000\",\"schema_version\":1,\"timestamp\":1732204800000,\"type\":\"CREDIT\"}"
        assertEquals(expected, canonical)
    }

    @Test
    fun testSha256DigestCalculation() {
        val canonical = "{\"amount_paise\":34000,\"customer_name\":\"Ramesh\",\"event_id\":\"test-uuid-1234\",\"note\":\"\",\"previous_event_hash\":\"0000000000000000000000000000000000000000000000000000000000000000\",\"schema_version\":1,\"timestamp\":1732204800000,\"type\":\"CREDIT\"}"
        val digest = EventIntegrity.computeDigest(canonical)

        // Independent SHA-256 calculation to verify
        val md = MessageDigest.getInstance("SHA-256")
        val expectedBytes = md.digest(canonical.toByteArray(StandardCharsets.UTF_8))
        val expectedHex = expectedBytes.joinToString("") { "%02x".format(it) }

        assertEquals(64, digest.length)
        assertEquals(expectedHex, digest)
    }

    @Test
    fun testTamperDetection() {
        val eventId = "test-uuid-5678"
        val customerName = "Suresh"
        val amountPaise = 50000L
        val type = "CREDIT"
        val timestamp = 1732205000000L
        val prevHash = EventIntegrity.GENESIS_HASH
        val note = "Groceries"

        val (_, validDigest) = EventIntegrity.computeEventDigest(
            eventId = eventId,
            customerName = customerName,
            amountPaise = amountPaise,
            type = type,
            timestamp = timestamp,
            previousEventHash = prevHash,
            note = note
        )

        // Verify valid digest matches
        assertTrue(
            EventIntegrity.verifyDigest(
                eventId = eventId,
                customerName = customerName,
                amountPaise = amountPaise,
                type = type,
                timestamp = timestamp,
                previousEventHash = prevHash,
                note = note,
                expectedDigest = validDigest
            )
        )

        // 1. Amount tampered
        assertFalse(
            EventIntegrity.verifyDigest(
                eventId = eventId,
                customerName = customerName,
                amountPaise = 50001L, // tampered by 1 paisa
                type = type,
                timestamp = timestamp,
                previousEventHash = prevHash,
                note = note,
                expectedDigest = validDigest
            )
        )

        // 2. Customer name tampered
        assertFalse(
            EventIntegrity.verifyDigest(
                eventId = eventId,
                customerName = "Ramesh", // tampered name
                amountPaise = amountPaise,
                type = type,
                timestamp = timestamp,
                previousEventHash = prevHash,
                note = note,
                expectedDigest = validDigest
            )
        )

        // 3. Transaction type tampered
        assertFalse(
            EventIntegrity.verifyDigest(
                eventId = eventId,
                customerName = customerName,
                amountPaise = amountPaise,
                type = "PAYMENT", // tampered type
                timestamp = timestamp,
                previousEventHash = prevHash,
                note = note,
                expectedDigest = validDigest
            )
        )

        // 4. Previous hash tampered (broken chain link)
        assertFalse(
            EventIntegrity.verifyDigest(
                eventId = eventId,
                customerName = customerName,
                amountPaise = amountPaise,
                type = type,
                timestamp = timestamp,
                previousEventHash = "1111111111111111111111111111111111111111111111111111111111111111",
                note = note,
                expectedDigest = validDigest
            )
        )
    }

    @Test
    fun testSequentialHashChainContinuity() {
        var prevHash = EventIntegrity.GENESIS_HASH

        val events = listOf(
            Triple("Ramesh", 10000L, "CREDIT"),
            Triple("Suresh", 20000L, "CREDIT"),
            Triple("Ramesh", 5000L, "PAYMENT"),
            Triple("Mahesh", 35000L, "CREDIT")
        )

        val chain = ArrayList<Pair<String, String>>() // (hash, prevHash)

        for ((name, amount, type) in events) {
            val eventId = java.util.UUID.randomUUID().toString()
            val (canonical, currentHash) = EventIntegrity.computeEventDigest(
                eventId = eventId,
                customerName = name,
                amountPaise = amount,
                type = type,
                timestamp = System.currentTimeMillis(),
                previousEventHash = prevHash,
                note = ""
            )
            chain.add(Pair(currentHash, prevHash))
            prevHash = currentHash
        }

        // Verify chain links
        assertEquals(EventIntegrity.GENESIS_HASH, chain[0].second)
        for (i in 1 until chain.size) {
            assertEquals("Chain broken at index $i", chain[i - 1].first, chain[i].second)
        }
    }
}
