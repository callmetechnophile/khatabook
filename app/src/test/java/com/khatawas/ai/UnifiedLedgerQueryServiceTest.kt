/*
 * Copyright (c) 2026 KHATAWAS Project
 * Simplified Technical English (ASD STE-100) compliant.
 * Unit tests for UnifiedLedgerQueryService deduplication, conflict detection, and offline handling.
 */

package com.khatawas.ai

import com.khatawas.db.TransactionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class UnifiedLedgerQueryServiceTest {

    @Test
    fun testDeduplicationOfEventPresentInBothLocalAndCloud() {
        val sharedEventId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        val localTx = TransactionEntity(
            id = 1L,
            eventId = sharedEventId,
            customerName = "Ravi",
            amountPaise = 50000L, // ₹500
            type = "CREDIT",
            timestamp = now,
            note = "Udhar"
        )

        val cloudTx = TransactionEntity(
            id = 101L,
            eventId = sharedEventId,
            customerName = "Ravi",
            amountPaise = 50000L, // ₹500
            type = "CREDIT",
            timestamp = now,
            note = "Udhar"
        )

        val result = UnifiedLedgerQueryService.mergeAndDeduplicate(
            localTxs = listOf(localTx),
            cloudTxs = listOf(cloudTx),
            customerName = "Ravi",
            isOffline = false
        )

        // Must count EXACTLY ONCE
        assertEquals(1, result.transactions.size)
        assertEquals(sharedEventId, result.transactions[0].eventId)
        assertEquals(EventSource.UNIFIED, result.transactions[0].source)
        assertTrue(result.transactions[0].isSynced)
        assertFalse(result.transactions[0].isConflict)

        // Financial balance: ₹500 credit (50000 paise)
        assertEquals(50000L, result.totalCreditPaise)
        assertEquals(0L, result.totalDebitPaise)
        assertEquals(50000L, result.netBalancePaise)
        assertEquals(FreshnessDisclosure.LOCAL_AND_CLOUD, result.freshness)
    }

    @Test
    fun testDistinctEventsWithSameAmountAreNotMerged() {
        val eventId1 = UUID.randomUUID().toString()
        val eventId2 = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        // Two distinct purchases of ₹250 by Ravi
        val tx1 = TransactionEntity(
            id = 1L,
            eventId = eventId1,
            customerName = "Ravi",
            amountPaise = 25000L, // ₹250
            type = "CREDIT",
            timestamp = now - 1000L,
            note = "Morning purchase"
        )

        val tx2 = TransactionEntity(
            id = 2L,
            eventId = eventId2,
            customerName = "Ravi",
            amountPaise = 25000L, // ₹250 (same amount)
            type = "CREDIT",
            timestamp = now,
            note = "Evening purchase"
        )

        val result = UnifiedLedgerQueryService.mergeAndDeduplicate(
            localTxs = listOf(tx1, tx2),
            cloudTxs = emptyList(),
            customerName = "Ravi",
            isOffline = false
        )

        // Must preserve BOTH distinct events
        assertEquals(2, result.transactions.size)
        assertEquals(50000L, result.totalCreditPaise)
        assertEquals(50000L, result.netBalancePaise)
    }

    @Test
    fun testContentMismatchTriggersConflictFlag() {
        val sharedEventId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        // Local says ₹500, cloud says ₹800
        val localTx = TransactionEntity(
            id = 1L,
            eventId = sharedEventId,
            customerName = "Suresh",
            amountPaise = 50000L,
            type = "CREDIT",
            timestamp = now
        )

        val cloudTx = TransactionEntity(
            id = 102L,
            eventId = sharedEventId,
            customerName = "Suresh",
            amountPaise = 80000L, // Conflict
            type = "CREDIT",
            timestamp = now
        )

        val result = UnifiedLedgerQueryService.mergeAndDeduplicate(
            localTxs = listOf(localTx),
            cloudTxs = listOf(cloudTx),
            customerName = "Suresh",
            isOffline = false
        )

        assertEquals(1, result.transactions.size)
        assertTrue(result.transactions[0].isConflict)
        assertEquals(1, result.conflictCount)
        assertEquals(FreshnessDisclosure.HAS_CONFLICTS, result.freshness)
        assertTrue(result.sourceIndicator.contains("conflict verification"))

        // Conflicted transactions must be excluded from unverified financial aggregates
        assertEquals(0L, result.totalCreditPaise)
        assertEquals(0L, result.netBalancePaise)
    }

    @Test
    fun testOfflineModeSetsFreshnessDisclosure() {
        val eventId = UUID.randomUUID().toString()
        val localTx = TransactionEntity(
            id = 1L,
            eventId = eventId,
            customerName = "Mahesh",
            amountPaise = 30000L,
            type = "CREDIT",
            timestamp = System.currentTimeMillis()
        )

        val result = UnifiedLedgerQueryService.mergeAndDeduplicate(
            localTxs = listOf(localTx),
            cloudTxs = emptyList(),
            customerName = "Mahesh",
            isOffline = true
        )

        assertEquals(FreshnessDisclosure.OFFLINE_LOCAL, result.freshness)
        assertEquals("Offline result: cloud-only records may be missing.", result.sourceIndicator)
    }
}
