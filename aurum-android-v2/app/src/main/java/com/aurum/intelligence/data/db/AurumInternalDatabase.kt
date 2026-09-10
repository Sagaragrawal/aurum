package com.aurum.intelligence.data.db
import com.aurum.intelligence.data.db.*
import com.aurum.intelligence.data.engine.*
import com.aurum.intelligence.data.model.*
import com.aurum.intelligence.data.repository.*
import com.aurum.intelligence.data.validation.*

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "scraper_execution_metrics", indices = [Index(value = ["timestamp"])])
data class ScraperExecutionMetricsEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val store: String,
    val plpRequests: Int,
    val plpProductsDiscovered: Int,
    val pdpRequests: Int,
    val pdpSuccessful: Int,
    val pdpFailed: Int,
    val pdpRequired: Int,
    val plpProcessedFully: Int,
    val is403Encountered: Boolean,
    val durationMs: Long,
)

@Dao
interface AurumInternalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRefreshActivity(log: RefreshActivityLogEntity): Long

    @Query("SELECT * FROM refresh_activity_logs WHERE runId = :runId ORDER BY timestamp ASC, id ASC")
    fun observeRunRefreshActivity(runId: Long): Flow<List<RefreshActivityLogEntity>>

    @Query("SELECT * FROM refresh_activity_logs ORDER BY timestamp ASC, id ASC")
    fun observeAllRefreshActivity(): Flow<List<RefreshActivityLogEntity>>

    @Query("SELECT DISTINCT runId FROM refresh_activity_logs ORDER BY runId DESC LIMIT 1")
    suspend fun getLatestRunId(): Long?

    @Query("SELECT * FROM refresh_activity_logs ORDER BY timestamp DESC, id DESC LIMIT :limit")
    fun observeRecentRefreshActivity(limit: Int): Flow<List<RefreshActivityLogEntity>>

    @Query("DELETE FROM refresh_activity_logs WHERE id NOT IN (SELECT id FROM refresh_activity_logs ORDER BY timestamp DESC, id DESC LIMIT :keepCount)")
    suspend fun trimRefreshActivity(keepCount: Int)

    @Query("DELETE FROM refresh_activity_logs WHERE runId NOT IN (SELECT DISTINCT runId FROM refresh_activity_logs ORDER BY runId DESC LIMIT :maxRuns)")
    suspend fun trimRefreshActivityToMaxRuns(maxRuns: Int)

    @Query("DELETE FROM refresh_activity_logs")
    suspend fun clearRefreshActivity()

    @Query("DELETE FROM refresh_activity_logs WHERE store = :store")
    suspend fun clearStoreRefreshActivity(store: String)

    @Query("DELETE FROM refresh_activity_logs WHERE store = :store AND runId = :runId")
    suspend fun clearStoreRefreshActivityForRun(store: String, runId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRawPayload(payload: RawBridgePayloadEntity)

    @Query("SELECT * FROM raw_bridge_payloads ORDER BY receivedAt, id")
    suspend fun allRawPayloads(): List<RawBridgePayloadEntity>

    @Query("DELETE FROM raw_bridge_payloads WHERE id NOT IN (SELECT id FROM raw_bridge_payloads ORDER BY receivedAt DESC, id DESC LIMIT :keepCount)")
    suspend fun trimRawPayloads(keepCount: Int)

    @Query("DELETE FROM raw_bridge_payloads")
    suspend fun clearRawPayloads()

    @Query("DELETE FROM raw_bridge_payloads WHERE id = :id")
    suspend fun deleteRawPayload(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExecutionMetrics(metrics: ScraperExecutionMetricsEntity): Long

    @Query("SELECT * FROM scraper_execution_metrics ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecentExecutionMetrics(limit: Int): Flow<List<ScraperExecutionMetricsEntity>>
}

@Database(
    entities = [
        RefreshActivityLogEntity::class,
        RawBridgePayloadEntity::class,
        ScraperExecutionMetricsEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AurumInternalDatabase : RoomDatabase() {
    abstract fun dao(): AurumInternalDao

    companion object {
        private const val DB_NAME = "aurum_internal.db"

        fun create(context: Context): AurumInternalDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AurumInternalDatabase::class.java,
                DB_NAME,
            )
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}

