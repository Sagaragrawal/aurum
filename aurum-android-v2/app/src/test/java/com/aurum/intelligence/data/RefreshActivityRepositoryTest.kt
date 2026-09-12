package com.aurum.intelligence.data

import com.aurum.intelligence.data.db.AurumInternalDao
import com.aurum.intelligence.data.db.AurumInternalDatabase
import com.aurum.intelligence.data.db.RawBridgePayloadEntity
import com.aurum.intelligence.data.db.RefreshActivityLogEntity
import com.aurum.intelligence.data.db.ScraperExecutionMetricsEntity
import com.aurum.intelligence.data.repository.RefreshActivityRepository
import com.aurum.intelligence.data.repository.RefreshLogSeverity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshActivityRepositoryTest {

    @Test
    fun logsStoredForMax3RunsAndTrimmed() = runBlocking {
        var clockTime = 1000L
        val fakeDao = FakeAurumInternalDao()
        val fakeDb = FakeAurumInternalDatabase(fakeDao)
        val repository = RefreshActivityRepository(fakeDb, clock = { clockTime })

        // Run 1
        repository.startNewRun(null)
        repository.log(RefreshLogSeverity.Info, "ajio.com", "Run 1 log A")
        repository.log(RefreshLogSeverity.Info, "amazon.in", "Run 1 log B")

        // Run 2
        clockTime = 2000L
        repository.startNewRun(null)
        repository.log(RefreshLogSeverity.Info, "ajio.com", "Run 2 log A")

        // Run 3
        clockTime = 3000L
        repository.startNewRun(null)
        repository.log(RefreshLogSeverity.Info, "flipkart.com", "Run 3 log A")

        assertEquals(3, fakeDao.getDistinctRunCount())

        // Run 4: should trim Run 1, keeping Runs 2, 3, 4 (total 3 runs)
        clockTime = 4000L
        repository.startNewRun(null)
        repository.log(RefreshLogSeverity.Info, "shopsy.in", "Run 4 log A")

        assertEquals(3, fakeDao.getDistinctRunCount())
        val remainingRunIds = fakeDao.getDistinctRunIds()
        assertEquals(listOf(4000L, 3000L, 2000L), remainingRunIds)
    }

    @Test
    fun refreshAllClearsWindowByStartingNewRun() = runBlocking {
        var clockTime = 1000L
        val fakeDao = FakeAurumInternalDao()
        val fakeDb = FakeAurumInternalDatabase(fakeDao)
        val repository = RefreshActivityRepository(fakeDb, clock = { clockTime })

        repository.startNewRun(null)
        repository.log(RefreshLogSeverity.Info, "ajio.com", "Previous run log")

        // Refresh All
        clockTime = 2000L
        repository.startNewRun(null)

        val currentRunLogs = repository.logs.first()
        assertTrue("Refresh activity window should be empty/cleared for the new run", currentRunLogs.isEmpty())
    }

    @Test
    fun refreshStoreClearsTargetStoreLogsOnlyInCurrentRun() = runBlocking {
        var clockTime = 1000L
        val fakeDao = FakeAurumInternalDao()
        val fakeDb = FakeAurumInternalDatabase(fakeDao)
        val repository = RefreshActivityRepository(fakeDb, clock = { clockTime })

        repository.startNewRun(null)
        repository.log(RefreshLogSeverity.Info, "ajio.com", "Old AJIO log")
        repository.log(RefreshLogSeverity.Info, "amazon.in", "Amazon log")

        // Refresh AJIO only
        repository.startNewRun(setOf("ajio.com"))

        val currentRunLogs = repository.logs.first()
        assertEquals(1, currentRunLogs.size)
        assertEquals("amazon.in", currentRunLogs.single().store)
        assertEquals("Amazon log", currentRunLogs.single().message)
    }

    @Test
    fun logsAreNotTruncatedAt100() = runBlocking {
        var clockTime = 1000L
        val fakeDao = FakeAurumInternalDao()
        val fakeDb = FakeAurumInternalDatabase(fakeDao)
        val repository = RefreshActivityRepository(fakeDb, clock = { clockTime })

        repository.startNewRun(null)
        for (i in 1..250) {
            repository.log(RefreshLogSeverity.Info, "ajio.com", "Log message $i")
        }

        val currentRunLogs = repository.logs.first()
        assertEquals("Should contain all 250 logs without 100 truncation", 250, currentRunLogs.size)
    }
}

private class FakeAurumInternalDatabase(private val dao: AurumInternalDao) : AurumInternalDatabase() {
    override fun dao(): AurumInternalDao = dao
    override fun createInvalidationTracker(): androidx.room.InvalidationTracker {
        val tracker = androidx.room.InvalidationTracker(this, hashMapOf<String, String>(), hashMapOf<String, Set<String>>(), "refresh_activity_logs", "raw_bridge_payloads", "scraper_execution_metrics")
        return tracker
    }
    override fun clearAllTables() {}
}

private class FakeAurumInternalDao : AurumInternalDao {
    private val logs = mutableListOf<RefreshActivityLogEntity>()
    private val logsFlow = MutableStateFlow<List<RefreshActivityLogEntity>>(emptyList())

    private fun notifyChanges() {
        logsFlow.value = logs.toList()
    }

    fun getDistinctRunCount(): Int = logs.map { it.runId }.distinct().size

    fun getDistinctRunIds(): List<Long> = logs.map { it.runId }.distinct().sortedDescending()

    override suspend fun insertRefreshActivity(log: RefreshActivityLogEntity): Long {
        val nextId = (logs.maxOfOrNull { it.id } ?: 0L) + 1L
        val newEntity = log.copy(id = nextId)
        logs.add(newEntity)
        notifyChanges()
        return nextId
    }

    override fun observeRunRefreshActivity(runId: Long): Flow<List<RefreshActivityLogEntity>> {
        return logsFlow.map { list ->
            list.filter { it.runId == runId }.sortedWith(compareBy({ it.timestamp }, { it.id }))
        }
    }

    override fun observeAllRefreshActivity(): Flow<List<RefreshActivityLogEntity>> {
        return logsFlow.map { list ->
            list.sortedWith(compareBy({ it.timestamp }, { it.id }))
        }
    }

    override suspend fun getLatestRunId(): Long? {
        return logs.map { it.runId }.filter { it > 0L }.maxOrNull()
    }

    override fun observeRecentRefreshActivity(limit: Int): Flow<List<RefreshActivityLogEntity>> {
        return logsFlow.map { list ->
            list.sortedWith(compareByDescending<RefreshActivityLogEntity> { it.timestamp }.thenByDescending { it.id }).take(limit)
        }
    }

    override suspend fun trimRefreshActivity(keepCount: Int) {
        if (logs.size > keepCount) {
            val toRemove = logs.size - keepCount
            repeat(toRemove) { logs.removeAt(0) }
            notifyChanges()
        }
    }

    override suspend fun trimRefreshActivityToMaxRuns(maxRuns: Int) {
        val distinctRuns = logs.map { it.runId }.distinct().sortedDescending()
        if (distinctRuns.size > maxRuns) {
            val allowedRuns = distinctRuns.take(maxRuns).toSet()
            logs.removeAll { !allowedRuns.contains(it.runId) }
            notifyChanges()
        }
    }

    override suspend fun clearRefreshActivity() {
        logs.clear()
        notifyChanges()
    }

    override suspend fun clearStoreRefreshActivity(store: String) {
        logs.removeAll { it.store == store }
        notifyChanges()
    }

    override suspend fun clearStoreRefreshActivityForRun(store: String, runId: Long) {
        logs.removeAll { it.store == store && it.runId == runId }
        notifyChanges()
    }

    override suspend fun insertRawPayload(payload: RawBridgePayloadEntity) {}
    override suspend fun allRawPayloads(): List<RawBridgePayloadEntity> = emptyList()
    override suspend fun trimRawPayloads(keepCount: Int) {}
    override suspend fun clearRawPayloads() {}
    override suspend fun deleteRawPayload(id: String) {}
    override suspend fun insertExecutionMetrics(metrics: ScraperExecutionMetricsEntity): Long = 0L
    override fun observeRecentExecutionMetrics(limit: Int): Flow<List<ScraperExecutionMetricsEntity>> = MutableStateFlow(emptyList())
}
