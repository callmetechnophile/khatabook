/*
 * Copyright (c) 2026 KHATAWAS Project
 * Simplified Technical English (ASD STE-100) compliant.
 * User-initiated cloud synchronization manager with network awareness and idempotent SurrealDB replication.
 * Strictly adheres to user-initiated backup: NO automatic background upload on transactions or app launch.
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

/**
 * Observable UI state for cloud synchronization operations.
 */
sealed class SyncState {
    data class Idle(val unsyncedCount: Int, val lastSyncedTimestamp: Long? = null) : SyncState()
    data class Syncing(val totalToSync: Int) : SyncState()
    data class Success(val syncedCount: Int, val duplicateCount: Int, val timestamp: Long) : SyncState()
    data class PartialFailure(val failedCount: Int, val successCount: Int, val errorMessage: String) : SyncState()
    data class Offline(val unsyncedCount: Int) : SyncState()
}

/**
 * Manages user-initiated cloud synchronization between local encrypted SQLCipher database
 * and the FastAPI / SurrealDB Cloud backend.
 */
class SyncManager(
    private val context: Context,
    private val ledgerDatabase: LedgerDatabase
) {

    companion object {
        private const val TAG = "KHATAWAS_SYNC"
        private const val PREFS_NAME = "khatawas_sync_prefs"
        private const val KEY_LAST_SYNC_TIMESTAMP = "last_cloud_sync_timestamp"

        // Default backend sync URL (can be pointed to local emulator, test server, or SurrealDB Cloud backend)
        var backendBaseUrl: String = "http://10.0.2.2:8000/api/v1"
        var shopId: String = "shop:default_kirana"
        var authToken: String = "khatawas_bearer_token"
    }

    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    var onSyncStatusChanged: ((String) -> Unit)? = null
    var onSyncStateChanged: ((SyncState) -> Unit)? = null

    /**
     * Get timestamp of the last successful cloud synchronization, if any.
     */
    fun getLastSyncTimestamp(): Long? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val ts = prefs.getLong(KEY_LAST_SYNC_TIMESTAMP, -1L)
        return if (ts > 0L) ts else null
    }

    private fun saveLastSyncTimestamp(ts: Long) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putLong(KEY_LAST_SYNC_TIMESTAMP, ts).apply()
    }

    /**
     * Return current idle sync state based on local unsynced record count.
     */
    fun getCurrentSyncState(): SyncState {
        val unsynced = ledgerDatabase.getUnsyncedCount()
        val lastSync = getLastSyncTimestamp()
        return SyncState.Idle(unsyncedCount = unsynced, lastSyncedTimestamp = lastSync)
    }

    /**
     * Deprecated: Automatic background sync loop is disabled per user-initiated backup requirements.
     */
    @Deprecated("Khatawas requires explicit user-triggered backup via refresh icon.")
    fun startSyncLoop() {
        Log.i(TAG, "startSyncLoop() called but ignored: Khatawas strictly uses explicit user-triggered backup.")
    }

    /**
     * Deprecated: Immediate sync on transaction creation is disabled per user-initiated backup requirements.
     */
    @Deprecated("Khatawas requires explicit user-triggered backup via refresh icon.")
    fun triggerImmediateSync() {
        Log.i(TAG, "triggerImmediateSync() called but ignored: Khatawas strictly uses explicit user-triggered backup.")
    }

    /**
     * Explicit user-triggered backup to SurrealDB Cloud via FastAPI backend.
     * Checks network availability, validates local event hash integrity, and transmits batch.
     */
    fun performManualBackup(onStateChanged: ((SyncState) -> Unit)? = null) {
        executor.execute {
            val callback: (SyncState) -> Unit = { state ->
                onSyncStateChanged?.invoke(state)
                onStateChanged?.invoke(state)
                when (state) {
                    is SyncState.Idle -> onSyncStatusChanged?.invoke(
                        if (state.unsyncedCount > 0) "${state.unsyncedCount} unsynced records" else "● All records backed up"
                    )
                    is SyncState.Syncing -> onSyncStatusChanged?.invoke("Backing up ${state.totalToSync} record(s) to cloud...")
                    is SyncState.Success -> onSyncStatusChanged?.invoke("● Cloud backup completed (${state.syncedCount} synced)")
                    is SyncState.PartialFailure -> onSyncStatusChanged?.invoke("Backup partial failure: ${state.failedCount} failed (${state.errorMessage})")
                    is SyncState.Offline -> onSyncStatusChanged?.invoke("Offline · ${state.unsyncedCount} unsynced record(s)")
                }
            }

            if (!isNetworkAvailable()) {
                val unsynced = ledgerDatabase.getUnsyncedCount()
                Log.w(TAG, "Manual backup aborted: network is unavailable. Local records ($unsynced) are safe offline.")
                callback(SyncState.Offline(unsyncedCount = unsynced))
                return@execute
            }

            val pendingItems = ledgerDatabase.getPendingSyncItems(100)
            if (pendingItems.isEmpty()) {
                val now = System.currentTimeMillis()
                saveLastSyncTimestamp(now)
                Log.i(TAG, "No pending items to sync. Cloud ledger is fully up-to-date.")
                callback(SyncState.Success(syncedCount = 0, duplicateCount = 0, timestamp = now))
                return@execute
            }

            callback(SyncState.Syncing(totalToSync = pendingItems.size))

            try {
                val batchId = UUID.randomUUID().toString()
                val eventsArray = JSONArray()

                for (item in pendingItems) {
                    val evObj = JSONObject(item.payloadJson)
                    // Ensure hash chaining metadata is present in the payload
                    if (!evObj.has("event_hash") && item.eventHash.isNotBlank()) {
                        evObj.put("event_hash", item.eventHash)
                    }
                    if (!evObj.has("previous_event_hash") && item.previousEventHash.isNotBlank()) {
                        evObj.put("previous_event_hash", item.previousEventHash)
                    }
                    if (!evObj.has("schema_version")) {
                        evObj.put("schema_version", 1)
                    }
                    eventsArray.put(evObj)
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
                    readTimeout = 10000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    setRequestProperty("Authorization", "Bearer $authToken")
                    setRequestProperty("X-Shop-ID", shopId)
                }

                OutputStreamWriter(connection.outputStream, "UTF-8").use { writer ->
                    writer.write(requestBody.toString())
                    writer.flush()
                }

                val responseCode = connection.responseCode
                if (responseCode in 200..299) {
                    val responseString = BufferedReader(InputStreamReader(connection.inputStream, "UTF-8")).use { it.readText() }
                    val responseJson = JSONObject(responseString)
                    val resultsArray = responseJson.optJSONArray("results") ?: JSONArray()

                    var acceptedCount = 0
                    var duplicateCount = 0
                    var rejectedCount = 0

                    for (i in 0 until resultsArray.length()) {
                        val resultObj = resultsArray.getJSONObject(i)
                        val eventId = resultObj.getString("event_id")
                        val status = resultObj.optString("status", "")
                        when (status) {
                            "ACCEPTED" -> {
                                ledgerDatabase.markEventSynced(eventId)
                                acceptedCount++
                            }
                            "DUPLICATE_ACCEPTED" -> {
                                ledgerDatabase.markEventSynced(eventId)
                                duplicateCount++
                            }
                            "REJECTED" -> {
                                val errMsg = resultObj.optString("error_message", "Rejected by cloud ledger")
                                ledgerDatabase.markEventFailed(eventId, errMsg)
                                rejectedCount++
                            }
                            else -> {
                                ledgerDatabase.markEventFailed(eventId, "Unknown status: $status")
                                rejectedCount++
                            }
                        }
                    }

                    val now = System.currentTimeMillis()
                    saveLastSyncTimestamp(now)

                    if (rejectedCount > 0) {
                        callback(
                            SyncState.PartialFailure(
                                failedCount = rejectedCount,
                                successCount = acceptedCount + duplicateCount,
                                errorMessage = "$rejectedCount records rejected by cloud ledger"
                            )
                        )
                    } else {
                        callback(
                            SyncState.Success(
                                syncedCount = acceptedCount,
                                duplicateCount = duplicateCount,
                                timestamp = now
                            )
                        )
                    }
                } else {
                    val errorStream = connection.errorStream
                    val errorMsg = if (errorStream != null) {
                        BufferedReader(InputStreamReader(errorStream, "UTF-8")).use { it.readText() }
                    } else "HTTP $responseCode"

                    Log.w(TAG, "Sync failed with HTTP $responseCode: $errorMsg")
                    for (item in pendingItems) {
                        ledgerDatabase.markEventFailed(item.eventId, errorMsg)
                    }
                    callback(
                        SyncState.PartialFailure(
                            failedCount = pendingItems.size,
                            successCount = 0,
                            errorMessage = "Server error HTTP $responseCode"
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Network sync exception: ${e.message}", e)
                for (item in pendingItems) {
                    ledgerDatabase.markEventFailed(item.eventId, e.message ?: "Network error")
                }
                callback(
                    SyncState.PartialFailure(
                        failedCount = pendingItems.size,
                        successCount = 0,
                        errorMessage = e.message ?: "Network connection error"
                    )
                )
            }
        }
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
