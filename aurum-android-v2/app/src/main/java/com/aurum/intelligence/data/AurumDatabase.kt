package com.aurum.intelligence.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "products",
    indices = [
        Index(value = ["store", "retailerId"], unique = true),
        Index(value = ["canonicalUrl"], unique = true),
        Index(value = ["status"]),
    ],
)
data class ProductEntity(
    @PrimaryKey val id: String,
    val store: String,
    val retailerId: String,
    val canonicalUrl: String,
    val name: String,
    val brand: String?,
    val grams: Double?,
    val karat: Double?,
    val purity: String?,
    val price: Double,
    val couponPrice: Double?,
    val status: String,
    val refreshMethod: String,
    val checkedAt: Long,
    val lastLiveAt: Long,
    val manuallyEditedAt: Long? = null,
    val unitWeightGrams: Double? = null,
    val quantity: Int = 1,
    val totalWeightGrams: Double? = null,
    val weightConfidence: String = "High",
    val pincode: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val formattedAddress: String? = null,
    val isBlinkDeal: Boolean = false,
    val blinkDealPrice: Double? = null,
    val blinkDealEndTime: Long? = null,
    val deliverable: Boolean = true,
    val isMicroCoin: Boolean = false,
)

@Entity(
    tableName = "product_price_history",
    indices = [Index(value = ["productId", "checkedAt"])],
    foreignKeys = [
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["productId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ProductPriceHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val productId: String,
    val price: Double,
    val couponPrice: Double?,
    val checkedAt: Long,
)

@Entity(tableName = "raw_bridge_payloads", indices = [Index(value = ["receivedAt"])])
data class RawBridgePayloadEntity(
    @PrimaryKey val id: String,
    val store: String,
    val receivedAt: Long,
    val json: String,
)

@Entity(tableName = "bullion_sources", indices = [Index(value = ["status"])])
data class BullionSourceEntity(
    @PrimaryKey val id: String,
    val source: String,
    val label: String,
    val url: String,
    val price24: Double?,
    val price22: Double?,
    val price22Derived: Boolean,
    val status: String,
    val transport: String,
    val fetchedAt: Long?,
    val lastLiveAt: Long?,
    val lastAttemptAt: Long?,
    val error: String?,
)

@Entity(tableName = "bullion_history", indices = [Index(value = ["sourceId", "fetchedAt"])])
data class BullionHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: String,
    val price24: Double,
    val price22: Double,
    val price22Derived: Boolean,
    val fetchedAt: Long,
)

@Entity(tableName = "refresh_activity_logs", indices = [Index(value = ["timestamp"])])
data class RefreshActivityLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val severity: String,
    val store: String?,
    val message: String,
)

@Dao
interface AurumDao {
    @Query("SELECT COUNT(*) FROM products")
    suspend fun productCount(): Int

    @Query("SELECT * FROM products ORDER BY COALESCE(name, '') COLLATE NOCASE")
    fun observeProducts(): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products ORDER BY id")
    suspend fun allProducts(): List<ProductEntity>

    @Query("SELECT * FROM product_price_history ORDER BY checkedAt, id")
    suspend fun allProductHistory(): List<ProductPriceHistoryEntity>

    @Query("SELECT * FROM raw_bridge_payloads ORDER BY receivedAt, id")
    suspend fun allRawPayloads(): List<RawBridgePayloadEntity>

    @Query("SELECT * FROM bullion_sources ORDER BY id")
    fun observeBullionSources(): Flow<List<BullionSourceEntity>>

    @Query("SELECT * FROM bullion_sources ORDER BY id")
    suspend fun allBullionSources(): List<BullionSourceEntity>

    @Query("SELECT * FROM bullion_history ORDER BY fetchedAt, id")
    suspend fun allBullionHistory(): List<BullionHistoryEntity>

    @Query("SELECT * FROM bullion_history ORDER BY fetchedAt DESC, id DESC LIMIT :limit")
    fun observeRecentBullionHistory(limit: Int): Flow<List<BullionHistoryEntity>>

    @Query("SELECT * FROM refresh_activity_logs ORDER BY timestamp DESC, id DESC LIMIT :limit")
    fun observeRecentRefreshActivity(limit: Int): Flow<List<RefreshActivityLogEntity>>

    @Query("SELECT * FROM bullion_sources WHERE id = :id LIMIT 1")
    suspend fun bullionSourceById(id: String): BullionSourceEntity?

    @Query("SELECT * FROM bullion_history ORDER BY fetchedAt DESC LIMIT 1")
    suspend fun latestBullionHistory(): BullionHistoryEntity?

    @Query("SELECT * FROM products WHERE store = :store AND retailerId = :retailerId LIMIT 1")
    suspend fun productByRetailerId(store: String, retailerId: String): ProductEntity?

    @Query("SELECT * FROM products WHERE canonicalUrl = :canonicalUrl LIMIT 1")
    suspend fun productByCanonicalUrl(canonicalUrl: String): ProductEntity?

    @Query("SELECT * FROM products WHERE store = :store AND checkedAt < :checkedBefore")
    suspend fun getStaleProducts(store: String, checkedBefore: Long): List<ProductEntity>

    @Query("UPDATE products SET status = 'out_of_stock', checkedAt = :now WHERE id = :id")
    suspend fun markOutOfStock(id: String, now: Long)

    @Query("SELECT * FROM products WHERE id = :id LIMIT 1")
    suspend fun productById(id: String): ProductEntity?

    @Upsert
    suspend fun upsertProduct(product: ProductEntity)

    @Query("DELETE FROM products WHERE id = :id")
    suspend fun deleteProduct(id: String)

    @Query("DELETE FROM products WHERE (karat IS NOT NULL AND karat < 24.0) OR (CAST(purity AS REAL) > 0 AND CAST(purity AS REAL) < 995.0 AND CAST(purity AS REAL) >= 1.0) OR (CAST(purity AS REAL) > 0 AND CAST(purity AS REAL) < 0.995 AND CAST(purity AS REAL) < 1.0) OR name LIKE '%22K%' OR name LIKE '%22 K%' OR name LIKE '%22 Kt%' OR name LIKE '%22Kt%' OR name LIKE '%22 Karat%' OR name LIKE '%916%' OR name LIKE '%18K%' OR name LIKE '%14K%' OR price <= 0")
    suspend fun deleteNon24KProducts(): Int

    @Query("DELETE FROM product_price_history WHERE productId = :productId")
    suspend fun deleteProductHistory(productId: String)

    @Insert
    suspend fun insertPriceHistory(history: ProductPriceHistoryEntity)

    @Query(
        "SELECT EXISTS(SELECT 1 FROM product_price_history " +
            "WHERE productId = :productId AND price = :price AND couponPrice IS :couponPrice AND checkedAt = :checkedAt)",
    )
    suspend fun hasPriceHistory(productId: String, price: Double, couponPrice: Double?, checkedAt: Long): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRawPayload(payload: RawBridgePayloadEntity): Long

    @Query("DELETE FROM raw_bridge_payloads WHERE id NOT IN (SELECT id FROM raw_bridge_payloads ORDER BY receivedAt DESC, id DESC LIMIT :keep)")
    suspend fun trimRawPayloads(keep: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBullionSource(source: BullionSourceEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBullionHistory(history: BullionHistoryEntity): Long

    @Query(
        "SELECT EXISTS(SELECT 1 FROM bullion_history WHERE sourceId = :sourceId " +
            "AND price24 = :price24 AND price22 = :price22 AND fetchedAt = :fetchedAt)",
    )
    suspend fun hasBullionHistory(sourceId: String, price24: Double, price22: Double, fetchedAt: Long): Boolean

    @Query(
        "DELETE FROM bullion_history WHERE price24 < 3000 OR price24 > 50000 " +
            "OR price22 < price24 * 0.72 OR price22 > price24 * 1.02",
    )
    suspend fun deleteImplausibleBullionHistory()

    @Insert
    suspend fun insertRefreshActivity(log: RefreshActivityLogEntity)

    @Query("DELETE FROM refresh_activity_logs WHERE id NOT IN (SELECT id FROM refresh_activity_logs ORDER BY timestamp DESC, id DESC LIMIT :keep)")
    suspend fun trimRefreshActivity(keep: Int)

    @Query("DELETE FROM refresh_activity_logs")
    suspend fun clearRefreshActivity()
}

@Database(
    entities = [
        ProductEntity::class,
        ProductPriceHistoryEntity::class,
        RawBridgePayloadEntity::class,
        BullionSourceEntity::class,
        BullionHistoryEntity::class,
        RefreshActivityLogEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class AurumDatabase : RoomDatabase() {
    abstract fun dao(): AurumDao

    companion object {
        fun create(context: Context): AurumDatabase = Room.databaseBuilder(
            context.applicationContext,
            AurumDatabase::class.java,
            "aurum.db",
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build()

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `bullion_sources` (`id` TEXT NOT NULL, `source` TEXT NOT NULL, " +
                        "`label` TEXT NOT NULL, `url` TEXT NOT NULL, `price24` REAL, `price22` REAL, " +
                        "`price22Derived` INTEGER NOT NULL, `status` TEXT NOT NULL, `transport` TEXT NOT NULL, " +
                        "`fetchedAt` INTEGER, `lastLiveAt` INTEGER, `lastAttemptAt` INTEGER, `error` TEXT, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_bullion_sources_status` ON `bullion_sources` (`status`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `bullion_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`sourceId` TEXT NOT NULL, `price24` REAL NOT NULL, `price22` REAL NOT NULL, " +
                        "`price22Derived` INTEGER NOT NULL, `fetchedAt` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_bullion_history_sourceId_fetchedAt` " +
                        "ON `bullion_history` (`sourceId`, `fetchedAt`)",
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `refresh_activity_logs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`timestamp` INTEGER NOT NULL, `severity` TEXT NOT NULL, `store` TEXT, `message` TEXT NOT NULL)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_refresh_activity_logs_timestamp` " +
                        "ON `refresh_activity_logs` (`timestamp`)",
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `product_price_history_new` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `productId` TEXT NOT NULL, " +
                        "`price` REAL NOT NULL, `couponPrice` REAL, `checkedAt` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`productId`) REFERENCES `products`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "INSERT INTO `product_price_history_new` (`id`, `productId`, `price`, `couponPrice`, `checkedAt`) " +
                        "SELECT history.`id`, history.`productId`, history.`price`, history.`couponPrice`, history.`checkedAt` " +
                        "FROM `product_price_history` AS history INNER JOIN `products` AS product ON product.`id` = history.`productId`",
                )
                db.execSQL("DROP TABLE `product_price_history`")
                db.execSQL("ALTER TABLE `product_price_history_new` RENAME TO `product_price_history`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_product_price_history_productId_checkedAt` " +
                        "ON `product_price_history` (`productId`, `checkedAt`)",
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `products` ADD COLUMN `unitWeightGrams` REAL")
                db.execSQL("ALTER TABLE `products` ADD COLUMN `quantity` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `products` ADD COLUMN `totalWeightGrams` REAL")
                db.execSQL("ALTER TABLE `products` ADD COLUMN `weightConfidence` TEXT NOT NULL DEFAULT 'High'")
                db.execSQL("ALTER TABLE `products` ADD COLUMN `pincode` TEXT")
                db.execSQL("ALTER TABLE `products` ADD COLUMN `latitude` REAL")
                db.execSQL("ALTER TABLE `products` ADD COLUMN `longitude` REAL")
                db.execSQL("ALTER TABLE `products` ADD COLUMN `formattedAddress` TEXT")
                db.execSQL("ALTER TABLE `products` ADD COLUMN `isBlinkDeal` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `products` ADD COLUMN `blinkDealPrice` REAL")
                db.execSQL("ALTER TABLE `products` ADD COLUMN `blinkDealEndTime` INTEGER")
                db.execSQL("ALTER TABLE `products` ADD COLUMN `deliverable` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `products` ADD COLUMN `isMicroCoin` INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}