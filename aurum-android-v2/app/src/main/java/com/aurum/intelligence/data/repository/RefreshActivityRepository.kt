package com.aurum.intelligence.data.repository

import com.aurum.intelligence.data.db.*
import com.aurum.intelligence.data.engine.*
import com.aurum.intelligence.data.model.*
import com.aurum.intelligence.data.repository.*
import com.aurum.intelligence.data.validation.*

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

enum class RefreshLogSeverity {
    Info,
    Warning,
    Error,
}

class RefreshActivityRepository(
    private val database: AurumInternalDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val currentRunIdState = MutableStateFlow<Long?>(null)

    val logs: Flow<List<RefreshActivityLogEntity>> = currentRunIdState.flatMapLatest { explicitRunId ->
        if (explicitRunId != null) {
            database.dao().observeRunRefreshActivity(explicitRunId)
        } else {
            flow {
                val latest = database.dao().getLatestRunId()
                if (latest != null && latest > 0L) {
                    currentRunIdState.value = latest
                    database.dao().observeRunRefreshActivity(latest).collect { emit(it) }
                } else {
                    database.dao().observeAllRefreshActivity().collect { emit(it) }
                }
            }
        }
    }

    suspend fun startNewRun(targetStores: Set<String>? = null) {
        database.withTransaction {
            if (targetStores == null) {
                // Refresh All: Generate a brand new run ID and trim DB to keep at most 3 runs total (2 past + 1 current)
                val newRunId = clock()
                currentRunIdState.value = newRunId
                database.dao().trimRefreshActivityToMaxRuns(3)
            } else {
                // Refresh Store: Clear logs for specified target stores in the current run
                var runId = currentRunIdState.value
                if (runId == null || runId == 0L) {
                    runId = database.dao().getLatestRunId() ?: clock()
                    currentRunIdState.value = runId
                }
                for (st in targetStores) {
                    database.dao().clearStoreRefreshActivityForRun(st, runId)
                }
            }
        }
    }

    suspend fun log(severity: RefreshLogSeverity, store: String?, message: String) {
        val cleanStore = store?.lowercase()?.trim()
        val storePrefixes = if (cleanStore != null) {
            val storeShort = cleanStore.substringBefore('.')
            setOf("[$cleanStore]", "[$storeShort]")
        } else emptySet()

        var formattedMessage = message.trim()
        if (cleanStore != null) {
            for (prefix in storePrefixes) {
                if (formattedMessage.lowercase().startsWith(prefix.lowercase())) {
                    val dropLength = prefix.length
                    formattedMessage = formattedMessage.substring(dropLength).trimStart()
                    break
                }
            }
        }

        android.util.Log.println(
            when (severity) {
                RefreshLogSeverity.Info -> android.util.Log.INFO
                RefreshLogSeverity.Warning -> android.util.Log.WARN
                RefreshLogSeverity.Error -> android.util.Log.ERROR
            },
            "AurumRefresh",
            "${store?.let { "[$it] " }.orEmpty()}$formattedMessage",
        )
        database.withTransaction {
            var runId = currentRunIdState.value
            if (runId == null || runId == 0L) {
                runId = database.dao().getLatestRunId() ?: clock()
                currentRunIdState.value = runId
            }
            database.dao().insertRefreshActivity(
                RefreshActivityLogEntity(
                    timestamp = clock(),
                    severity = severity.name.lowercase(),
                    store = store,
                    message = formattedMessage,
                    runId = runId,
                ),
            )
            database.dao().trimRefreshActivityToMaxRuns(3)
        }
    }

    suspend fun clear() {
        database.withTransaction {
            database.dao().clearRefreshActivity()
            database.dao().clearRawPayloads()
        }
    }
}
