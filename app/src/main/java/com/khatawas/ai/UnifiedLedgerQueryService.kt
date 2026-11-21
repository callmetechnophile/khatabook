/*
 * Copyright (c) 2026 KHATAWAS Project
 * Simplified Technical English (ASD STE-100) compliant.
 * Unified Local and Cloud Read-Only Database Query Service.
 * Deduplicates financial events by stable UUID, detects conflicts, and calculates integer paise aggregates.
 */

package com.khatawas.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.khatawas.db.LedgerDatabase
import com.khatawas.db.TransactionEntity
import com.khatawas.sync.SyncManager
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

enum class EventSource {
    LOCAL,
    CLOUD,
    UNIFIED // Present in both local and cloud with verified identical content
}

enum class FreshnessDisclosure {
    LOCAL_AND_CLOUD,
    OFFLINE_LOCAL,
    LOCAL_ONLY,
    HAS_CONFLICTS
}

data class UnifiedTransaction(
    val eventId: String,
    val customerName: String,
    val amountPaise: Long,
    val type: String,
    val timestamp: Long,
    val note: String = "",
    val source: EventSource,
    val isSynced: Boolean = false,
    val isConflict: Boolean = false,
    val conflictReason: String = ""
)

data class UnifiedQueryResult(
    val transactions: List<UnifiedTransaction>,
    val totalCreditPaise: Long,
    val totalDebitPaise: Long,
    val netBalancePaise: Long,
    val customerName: String? = null,
    val freshness: FreshnessDisclosure,
    val sourceIndicator: String,
    val conflictCount: Int = 0,
    val dateRangeDescription: String = ""
)

/**
 * Unified ledger query service retrieving authorized financial records from both
 * local encrypted SQLCipher database and SurrealDB Cloud via FastAPI backend.
 * All financial arithmetic is performed deterministically using integer paise.
 */
class UnifiedLedgerQueryService(
    private val context: Context,
    private val ledgerDatabase: LedgerDatabase
) : UnifiedQueryDataSource {
    companion object {
        private const val TAG = "KHATAWAS_UNIFIED_QUERY"
        private const val NETWORK_TIMEOUT_MS = 3000

        /**
         * Core deduplication and conflict-detection engine.
         * Merges events using stable event UUIDs.
         * A financial event existing in both local and cloud is counted exactly once.
         * Two separate transactions with identical amounts are never merged.
         */
        fun mergeAndDeduplicate(
            localTxs: List<TransactionEntity>,
            cloudTxs: List<TransactionEntity>,
            customerName: String?,
            isOffline: Boolean
        ): UnifiedQueryResult {
            val localMap = LinkedHashMap<String, TransactionEntity>()
            for (tx in localTxs) {
                if (tx.eventId.isNotEmpty()) {
                    localMap[tx.eventId] = tx
                }
            }

            val cloudMap = LinkedHashMap<String, TransactionEntity>()
            for (tx in cloudTxs) {
                if (tx.eventId.isNotEmpty()) {
                    cloudMap[tx.eventId] = tx
                }
            }

            val unifiedList = ArrayList<UnifiedTransaction>()
            var conflictCount = 0

            // Process all local events first
            for ((eventId, localTx) in localMap) {
                val cloudTx = cloudMap[eventId]
                if (cloudTx != null) {
                    // Event exists in BOTH local and cloud
                    // Verify content integrity: amount and type must match
                    val isConflict = (localTx.amountPaise != cloudTx.amountPaise) || 
                                     (!localTx.type.equals(cloudTx.type, ignoreCase = true))

                    if (isConflict) {
                        conflictCount++
                        unifiedList.add(
                            UnifiedTransaction(
                                eventId = eventId,
                                customerName = localTx.customerName,
                                amountPaise = localTx.amountPaise,
                                type = localTx.type,
                                timestamp = localTx.timestamp,
                                note = localTx.note,
                                source = EventSource.LOCAL,
                                isSynced = false,
                                isConflict = true,
                                conflictReason = "Local amount (₹${localTx.amountPaise/100.0}) differs from cloud amount (₹${cloudTx.amountPaise/100.0})"
                            )
                        )
                    } else {
                        unifiedList.add(
                            UnifiedTransaction(
                                eventId = eventId,
                                customerName = localTx.customerName,
                                amountPaise = localTx.amountPaise,
                                type = localTx.type,
                                timestamp = localTx.timestamp,
                                note = localTx.note,
                                source = EventSource.UNIFIED,
                                isSynced = true,
                                isConflict = false
                            )
                        )
                    }
                } else {
                    // Event exists only locally (pending cloud sync)
                    unifiedList.add(
                        UnifiedTransaction(
                            eventId = eventId,
                            customerName = localTx.customerName,
                            amountPaise = localTx.amountPaise,
                            type = localTx.type,
                            timestamp = localTx.timestamp,
                            note = localTx.note,
                            source = EventSource.LOCAL,
                            isSynced = false,
                            isConflict = false
                        )
                    )
                }
            }

            // Process cloud-only events (not present locally)
            for ((eventId, cloudTx) in cloudMap) {
                if (!localMap.containsKey(eventId)) {
                    unifiedList.add(
                        UnifiedTransaction(
                            eventId = eventId,
                            customerName = cloudTx.customerName,
                            amountPaise = cloudTx.amountPaise,
                            type = cloudTx.type,
                            timestamp = cloudTx.timestamp,
                            note = cloudTx.note,
                            source = EventSource.CLOUD,
                            isSynced = true,
                            isConflict = false
                        )
                    )
                }
            }

            // Sort unified transactions descending by timestamp
            unifiedList.sortByDescending { it.timestamp }

            // Compute deterministic financial aggregates over non-conflicted events
            var totalCreditPaise = 0L
            var totalDebitPaise = 0L

            for (tx in unifiedList) {
                if (tx.isConflict) {
                    // Exclude conflicted events from unverified financial aggregates
                    continue
                }
                when (tx.type.uppercase(Locale.ROOT)) {
                    "CREDIT" -> totalCreditPaise += tx.amountPaise
                    "PAYMENT", "DEBIT" -> totalDebitPaise += tx.amountPaise
                    "REVERSAL" -> totalCreditPaise -= tx.amountPaise
                }
            }

            val netBalancePaise = totalCreditPaise - totalDebitPaise

            // Determine data freshness disclosure
            val freshness = when {
                conflictCount > 0 -> FreshnessDisclosure.HAS_CONFLICTS
                isOffline -> FreshnessDisclosure.OFFLINE_LOCAL
                cloudMap.isNotEmpty() && localMap.isNotEmpty() -> FreshnessDisclosure.LOCAL_AND_CLOUD
                cloudMap.isNotEmpty() -> FreshnessDisclosure.LOCAL_AND_CLOUD
                else -> FreshnessDisclosure.LOCAL_ONLY
            }

            val sourceIndicator = when (freshness) {
                FreshnessDisclosure.LOCAL_AND_CLOUD -> "Based on local and cloud ledger data."
                FreshnessDisclosure.OFFLINE_LOCAL -> "Offline result: cloud-only records may be missing."
                FreshnessDisclosure.LOCAL_ONLY -> "Based on local ledger data (pending cloud sync)."
                FreshnessDisclosure.HAS_CONFLICTS -> "Some records require conflict verification."
            }

            return UnifiedQueryResult(
                transactions = unifiedList,
                totalCreditPaise = totalCreditPaise,
                totalDebitPaise = totalDebitPaise,
                netBalancePaise = netBalancePaise,
                customerName = customerName,
                freshness = freshness,
                sourceIndicator = sourceIndicator,
                conflictCount = conflictCount
            )
        }
    }

    private val executor = Executors.newCachedThreadPool()

    /**
     * Check if network connectivity is currently available.
     */
    fun isOnline(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = cm?.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Query customer balance and recent transaction history across local and cloud.
     */
    override fun getCustomerLedgerUnified(customerName: String, limit: Int): UnifiedQueryResult {
        val trimmedName = customerName.trim()
        val localTxs = ledgerDatabase.getTransactionsForCustomer(trimmedName, limit)
        val cloudTxs = if (isOnline()) fetchCloudCustomerTransactions(trimmedName) else emptyList()
        val isOffline = !isOnline()

        return mergeAndDeduplicate(
            localTxs = localTxs,
            cloudTxs = cloudTxs,
            customerName = trimmedName,
            isOffline = isOffline
        )
    }

    /**
     * Query all recent transactions across local and cloud.
     */
    override fun getRecentTransactionsUnified(limit: Int): UnifiedQueryResult {
        val localTxs = ledgerDatabase.getRecentTransactions(limit)
        val isOffline = !isOnline()
        val cloudTxs = if (!isOffline) fetchCloudRecentTransactions(limit) else emptyList()

        return mergeAndDeduplicate(
            localTxs = localTxs,
            cloudTxs = cloudTxs,
            customerName = null,
            isOffline = isOffline
        )
    }

    /**
     * Query transactions filtered by category (CREDIT, DEBIT, STUCK, ILLEGAL).
     */
    override fun getTransactionsByCategoryUnified(category: String, limit: Int): UnifiedQueryResult {
        val localTxs = ledgerDatabase.getTransactionsByCategory(category, limit)
        val isOffline = !isOnline()
        val cloudTxs = if (!isOffline) fetchCloudRecentTransactions(limit).filter {
            matchesCategory(it, category)
        } else emptyList()

        return mergeAndDeduplicate(
            localTxs = localTxs,
            cloudTxs = cloudTxs,
            customerName = null,
            isOffline = isOffline
        )
    }

    /**
     * Query transactions filtered by date range (e.g. today, yesterday, this month).
     */
    override fun getTransactionsByDateRangeUnified(startTimestamp: Long, endTimestamp: Long, dateDesc: String): UnifiedQueryResult {
        val localTxs = ledgerDatabase.getTransactionsByDateRange(startTimestamp, endTimestamp)
        val isOffline = !isOnline()
        val cloudTxs = if (!isOffline) {
            fetchCloudRecentTransactions(100).filter {
                it.timestamp in startTimestamp..endTimestamp
            }
        } else emptyList()

        val result = mergeAndDeduplicate(
            localTxs = localTxs,
            cloudTxs = cloudTxs,
            customerName = null,
            isOffline = isOffline
        )
        return result.copy(dateRangeDescription = dateDesc)
    }

    /**
     * Get total outstanding balance across all customers (deduplicated).
     */
    override fun getTotalSummaryUnified(): UnifiedQueryResult {
        val allLocal = ledgerDatabase.getRecentTransactions(500)
        val isOffline = !isOnline()
        val allCloud = if (!isOffline) fetchCloudRecentTransactions(500) else emptyList()

        return mergeAndDeduplicate(
            localTxs = allLocal,
            cloudTxs = allCloud,
            customerName = null,
            isOffline = isOffline
        )
    }

    /**
     * Core deduplication and conflict-detection engine.
     * Merges events using stable event UUIDs.
     * A financial event existing in both local and cloud is counted exactly once.
     * Two separate transactions with identical amounts are never merged.
     */
    fun mergeAndDeduplicate(
        localTxs: List<TransactionEntity>,
        cloudTxs: List<TransactionEntity>,
        customerName: String?,
        isOffline: Boolean
    ): UnifiedQueryResult = Companion.mergeAndDeduplicate(localTxs, cloudTxs, customerName, isOffline)

    private fun matchesCategory(tx: TransactionEntity, category: String): Boolean {
        return when (category.uppercase(Locale.ROOT)) {
            "CREDIT" -> tx.type.equals("CREDIT", ignoreCase = true)
            "DEBIT" -> tx.type.equals("PAYMENT", ignoreCase = true) || tx.type.equals("DEBIT", ignoreCase = true)
            "STUCK" -> tx.note.contains("STUCK", ignoreCase = true)
            "ILLEGAL" -> tx.note.contains("ILLEGAL", ignoreCase = true)
            else -> true
        }
    }

    /**
     * Fetch cloud customer ledger via authenticated FastAPI backend.
     */
    private fun fetchCloudCustomerTransactions(customerName: String): List<TransactionEntity> {
        val future: Future<List<TransactionEntity>> = executor.submit(Callable {
            val list = ArrayList<TransactionEntity>()
            var conn: HttpURLConnection? = null
            try {
                val encodedName = java.net.URLEncoder.encode(customerName, "UTF-8")
                val url = URL("${SyncManager.backendBaseUrl}/customers/$encodedName/ledger?shop_id=${SyncManager.shopId}")
                conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = NETWORK_TIMEOUT_MS
                conn.readTimeout = NETWORK_TIMEOUT_MS
                conn.setRequestProperty("Authorization", "Bearer ${SyncManager.authToken}")
                conn.setRequestProperty("Accept", "application/json")

                if (conn.responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val resp = reader.readText()
                    reader.close()

                    val json = JSONObject(resp)
                    val txArray = json.optJSONArray("transactions")
                    if (txArray != null) {
                        for (i in 0 until txArray.length()) {
                            val item = txArray.getJSONObject(i)
                            val eventId = item.optString("event_id")
                            val type = item.optString("type", "CREDIT")
                            val amountPaise = item.optLong("amount_paise", 0L)
                            val note = item.optString("note", "")
                            val isoTimestamp = item.optString("timestamp", "")
                            val timestampMillis = parseIsoTimestamp(isoTimestamp)

                            list.add(
                                TransactionEntity(
                                    id = 0,
                                    eventId = eventId,
                                    customerName = customerName,
                                    amountPaise = amountPaise,
                                    type = type,
                                    timestamp = timestampMillis,
                                    note = note
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch cloud customer ledger for '$customerName': ${e.message}")
            } finally {
                conn?.disconnect()
            }
            list
        })

        return try {
            future.get(NETWORK_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Fetch recent cloud transactions for the shop.
     */
    private fun fetchCloudRecentTransactions(limit: Int): List<TransactionEntity> {
        val future: Future<List<TransactionEntity>> = executor.submit(Callable {
            val list = ArrayList<TransactionEntity>()
            var conn: HttpURLConnection? = null
            try {
                val url = URL("${SyncManager.backendBaseUrl}/customers?shop_id=${SyncManager.shopId}")
                conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = NETWORK_TIMEOUT_MS
                conn.readTimeout = NETWORK_TIMEOUT_MS
                conn.setRequestProperty("Authorization", "Bearer ${SyncManager.authToken}")
                conn.setRequestProperty("Accept", "application/json")

                if (conn.responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val resp = reader.readText()
                    reader.close()

                    val json = JSONObject(resp)
                    val custArray = json.optJSONArray("customers")
                    if (custArray != null) {
                        for (i in 0 until custArray.length()) {
                            val custObj = custArray.getJSONObject(i)
                            val custName = custObj.optString("name")
                            // Fetch customer ledger if needed or use known transactions
                            val custTxs = fetchCloudCustomerTransactions(custName)
                            list.addAll(custTxs)
                            if (list.size >= limit) break
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch cloud recent transactions: ${e.message}")
            } finally {
                conn?.disconnect()
            }
            list
        })

        return try {
            future.get(NETWORK_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseIsoTimestamp(isoString: String): Long {
        if (isoString.isEmpty()) return System.currentTimeMillis()
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            sdf.parse(isoString)?.time ?: System.currentTimeMillis()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }
}
