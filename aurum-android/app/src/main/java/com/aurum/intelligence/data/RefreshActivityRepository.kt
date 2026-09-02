package com.aurum.intelligence.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class RefreshLogSeverity {
    Info,
    Warning,
    Error,
}

class RefreshActivityRepository(
    private val database: AurumDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val logs: Flow<List<RefreshActivityLogEntity>> = database.dao()
        .observeRecentRefreshActivity(MAX_LOGS)
        .map(List<RefreshActivityLogEntity>::reversed)

    suspend fun log(severity: RefreshLogSeverity, store: String?, message: String) {
        android.util.Log.println(
            when (severity) {
                RefreshLogSeverity.Info -> android.util.Log.INFO
                RefreshLogSeverity.Warning -> android.util.Log.WARN
                RefreshLogSeverity.Error -> android.util.Log.ERROR
            },
            "AurumRefresh",
            "${store?.let { "[$it] " }.orEmpty()}$message",
        )
        database.withTransaction {
            database.dao().insertRefreshActivity(
                RefreshActivityLogEntity(
                    timestamp = clock(),
                    severity = severity.name.lowercase(),
                    store = store,
                    message = message,
                ),
            )
            database.dao().trimRefreshActivity(MAX_LOGS)
        }
    }

    suspend fun clear() = database.dao().clearRefreshActivity()

    private companion object {
        const val MAX_LOGS = 2_000
    }
}