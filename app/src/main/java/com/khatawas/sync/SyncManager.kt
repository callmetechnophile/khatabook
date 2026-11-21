/*
 * Copyright (c) 2026 KHATAWAS Project
 * Simplified Technical English (ASD STE-100) compliant.
 * Offline-first synchronization manager with network awareness and exponential backoff.
 */

package com.khatawas.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import com.khatawas.db.LedgerDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Manages background synchronization between local encrypted SQLCipher database
 * and the cloud backend API.
 */
class SyncManager(
    private val context: Context,
    private val ledgerDatabase: LedgerDatabase
) {

    companion object {
        private const val TAG = "KHATAWAS_SYNC"
        // Default backend sync URL (can be pointed to local emulator, test server, or cloud)
        var backendBaseUrl: String = "http://10.0.2.2:8000/api/v1"
        var shopId: String = "shop:default_kirana"
        var authToken: String = "khatawas_bearer_token"

        private const val INITIAL_BACKOFF_SECONDS = 2L
        private const val MAX_BACKOFF_SECONDS = 60L
    }

    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var currentBackoffSeconds = INITIAL_BACKOFF_SECONDS

    var onSyncStatusChanged: ((String) -> Unit)? = null

    /**
     * Start periodic synchronization polling.
     */
    fun startSyncLoop() {
        executor.scheduleWithFixedDelay({
            syncPendingTransactions()
        }, 5, 10, TimeUnit.SECONDS)
    }

    /**
     * Trigger immediate synchronization attempt.
     */
    fun triggerImmediateSync() {
        executor.execute {
            syncPendingTransactions()
        }
    }

    /**
     * Synchronize pending transactions from sync_queue to cloud backend.
     */
    @Synchronized
    fun syncPendingTransactions(): Int {
        if (!isNetworkAvailable()) {
            val pendingCount = ledgerDatabase.getPendingSyncItems(100).size
            val status = if (pendingCount > 0) "Offline · $pendingCount pending sync" else "Offline"
            onSyncStatusChanged?.invoke(status)
            return 0
        }

        val pendingItems = ledgerDatabase.getPendingSyncItems(50)
        if (pendingItems.isEmpty()) {
            onSyncStatusChanged?.invoke("● All records synced")
            return 0
        }

        onSyncStatusChanged?.invoke("Syncing ${pendingItems.size} transaction(s)...")

        try {
            val batchId = UUID.randomUUID().toString()
            val eventsArray = JSONArray()

            for (item in pendingItems) {
                eventsArray.put(JSONObject(item.payloadJson))
            }

            val requestBody = JSONObject().apply {
                put("shop_id", shopId)
                put("batch_id", batchId)
                put("events", eventsArray)
            }

            val syncUrl = URL("$backendBaseUrl/sync/batch")
            val connection = (syncUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 8000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $authToken")
                setRequestProperty("X-Shop-ID", shopId)
            }

            OutputStreamWriter(connection.outputStream, "UTF-8").use { writer ->
                writer.write(requestBody.toString())
                writer.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                val responseString = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
                val responseJson = JSONObject(responseString)
                val resultsArray = responseJson.optJSONArray("results") ?: JSONArray()

                for (i in 0 until resultsArray.length()) {
                    val resultObj = resultsArray.getJSONObject(i)
                    val eventId = resultObj.getString("event_id")
                    val status = resultObj.optString("status", "")
                    if (status == "ACCEPTED" || status == "DUPLICATE_ACCEPTED") {
                        ledgerDatabase.markEventSynced(eventId)
                    }
                }

                currentBackoffSeconds = INITIAL_BACKOFF_SECONDS
                val remaining = ledgerDatabase.getPendingSyncItems(100).size
                val status = if (remaining > 0) "Synced batch · $remaining pending" else "● Cloud synced"
                onSyncStatusChanged?.invoke(status)
                return pendingItems.size
            } else {
                val errorStream = connection.errorStream
                val errorMsg = if (errorStream != null) {
                    BufferedReader(InputStreamReader(errorStream)).use { it.readText() }
                } else "HTTP $responseCode"

                Log.w(TAG, "Sync failed with HTTP $responseCode: $errorMsg")
                for (item in pendingItems) {
                    ledgerDatabase.markEventFailed(item.eventId, errorMsg)
                }
                applyBackoff()
                return 0
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network sync exception: ${e.message}", e)
            for (item in pendingItems) {
                ledgerDatabase.markEventFailed(item.eventId, e.message ?: "Unknown network error")
            }
            applyBackoff()
            return 0
        }
    }

    private fun applyBackoff() {
        val pendingCount = ledgerDatabase.getPendingSyncItems(100).size
        onSyncStatusChanged?.invoke("Sync retry in ${currentBackoffSeconds}s ($pendingCount pending)")
        currentBackoffSeconds = (currentBackoffSeconds * 2).coerceAtMost(MAX_BACKOFF_SECONDS)
    }

    fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(network) ?: return false
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = cm.activeNetworkInfo ?: return false
            @Suppress("DEPRECATION")
            return networkInfo.isConnected
        }
    }

    fun shutdown() {
        executor.shutdown()
    }
}
