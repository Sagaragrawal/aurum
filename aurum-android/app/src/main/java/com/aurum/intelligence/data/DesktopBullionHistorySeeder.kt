package com.aurum.intelligence.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DesktopBullionHistorySeeder(
    private val context: Context,
    private val database: AurumDatabase,
) {
    suspend fun seed(): Int = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "desktop-history-seed").apply {
            deleteRecursively()
            check(mkdirs()) { "Unable to prepare bullion history seed" }
        }
        try {
            FILES.forEach { name ->
                context.assets.open("seed/history/$name").use { source ->
                    File(directory, name).outputStream().use(source::copyTo)
                }
            }
            val source = SQLiteDatabase.openDatabase(
                File(directory, "aurum.sqlite").path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            )
            val observations = source.use { desktop -> readHistory(desktop) }
            database.withTransaction {
                var inserted = 0
                observations.forEach { item ->
                    if (!database.dao().hasBullionHistory(item.sourceId, item.price24, item.price22, item.fetchedAt)) {
                        if (database.dao().insertBullionHistory(item) != -1L) inserted += 1
                    }
                }
                inserted
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun readHistory(database: SQLiteDatabase): List<BullionHistoryEntity> {
        val grouped = linkedMapOf<Pair<String, Long>, MutableMap<Int, Double>>()
        database.rawQuery(
            "SELECT source_id, karat, price, checked_at FROM bullion_history ORDER BY checked_at, source_id, karat",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val sourceId = cursor.getString(0)
                val karat = cursor.getInt(1)
                val price = cursor.getDouble(2)
                val timestamp = runCatching { Instant.parse(cursor.getString(3)).toEpochMilli() }.getOrNull() ?: continue
                if (sourceId !in SOURCES || karat !in setOf(22, 24) || !price.isFinite() || price <= 0) continue
                grouped.getOrPut(sourceId to timestamp, ::mutableMapOf)[karat] = price
            }
        }
        return grouped.mapNotNull { (key, prices) ->
            val price24 = prices[24] ?: prices[22]?.times(24.0 / 22.0) ?: return@mapNotNull null
            val price22 = prices[22] ?: price24 * (22.0 / 24.0)
            if (!BullionRatePolicy.isPlausible24(price24) || !BullionRatePolicy.isPlausible22(price22, price24)) {
                return@mapNotNull null
            }
            BullionHistoryEntity(
                sourceId = key.first,
                price24 = price24,
                price22 = price22,
                price22Derived = prices[22] == null,
                fetchedAt = key.second,
            )
        }
    }

    private companion object {
        val FILES = listOf("aurum.sqlite", "aurum.sqlite-wal", "aurum.sqlite-shm")
        val SOURCES = setOf("tan", "malabar", "mmtc", "kalyan")
    }
}