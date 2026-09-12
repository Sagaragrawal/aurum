package com.aurum.intelligence.data.engine

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class StoreTarget(
    val name: String = "",
    val url: String = "",
    val slug: String = "",
    val filterQuery: String = "",
    val isMinutes: Boolean = false,
)

@Serializable
data class StoreDetailConfig(
    val name: String = "",
    val displayName: String = "",
    val canonicalHost: String = "",
    val plpTargets: List<StoreTarget> = emptyList(),
    val targets22k: List<StoreTarget> = emptyList(),
    val pdpUrlPattern: String = "",
    val pdpApiPattern: String = "",
    val pdpWebPattern: String = "",
    val gatewayBaseUrl: String = "",
    val webBaseUrl: String = "",
    val headers: Map<String, String> = emptyMap(),
    val gatewayHeaders: Map<String, String> = emptyMap(),
    val webHeaders: Map<String, String> = emptyMap(),
)

@Serializable
data class BullionSourceConfig(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val url: String = "",
    val apiUrl: String = "",
    val requestMethod: String = "GET",
    val postBody: String = "",
    val accept: String = "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
    val transport: String = "direct_http",
    val errorNote: String? = null,
    val graphQlQuery: String = "",
    val graphQlVariables: String = "",
) {
    val sourceId: String get() = id
    val label: String get() = name
}

@Serializable
data class BullionConfig(
    val sources: List<BullionSourceConfig> = emptyList(),
    val userAgent: String = "",
    val connectTimeoutMs: Int = 12000,
    val readTimeoutMs: Int = 12000,
    val historyRetentionCount: Int = 480,
)

@Serializable
data class ScraperDelays(
    val ajioPageDelayMs: Long = 2500L,
    val ajioRateLimitBackoffMs: Long = 6000L,
    val flipkartPageDelayMs: Long = 350L,
    val shopsyPageDelayMs: Long = 200L,
    val amazonPageDelayMs: Long = 400L,
    val myntraWebPageDelayMs: Long = 400L,
    val myntraApiPageDelayMs: Long = 250L,
    val pdpInterRequestDelayMs: Long = 300L,
    val pdpAmazonDelayMs: Long = 400L,
    val pdpRateLimitBackoffMs: Long = 3000L,
    val missingProductRetryDelayMs: Long = 300L,
    val missingProductMaxRetryDelayMs: Long = 2000L,
    val uiUndoTimeoutMs: Long = 4000L,
    val uiCopyFeedbackTimeoutMs: Long = 1500L,
)

@Serializable
data class ScraperLimits(
    val maxPagesPerStore: Int = 100,
    val defaultPagesPerRefresh: Int = 3,
    val ajioPageSize: Int = 45,
    val myntraPageSize: Int = 50,
    val maxPdpItemsPerStore: Int = 500,
    val pdpConcurrency: Int = 5,
    val verifierConcurrency: Int = 30,
    val pdpMaxConsecutiveFailures: Int = 5,
    val dealMaxResults: Int = 6,
)

@Serializable
data class NetworkConfig(
    val connectTimeoutMs: Int = 15000,
    val readTimeoutMs: Int = 20000,
    val cronetFallbackTimeoutMs: Int = 5000,
    val missingProductTimeoutMs: Int = 5000,
    val maxConsecutive403sBeforeSkip: Int = 2,
    val retryBackoffMs: Long = 3000L,
    val bufferSize: Int = 32768,
    val defaultUserAgent: String = "",
    val desktopUserAgent: String = "",
    val cronetRequestHeaders: Map<String, String> = emptyMap(),
    val cronetApiRequestHeaders: Map<String, String> = emptyMap(),
    val desktopHeaders: Map<String, String> = emptyMap(),
    val ajioPdpHeaders: Map<String, String> = emptyMap(),
)

@Serializable
data class LocationConfig(
    val defaultPincode: String = "560048",
    val defaultLatitude: Double = 12.9716,
    val defaultLongitude: Double = 77.5946,
)

@Serializable
data class StorageConfig(
    val externalDirPath: String = "/storage/emulated/0/aurum",
    val backupDbFileName: String = "aurum.db",
    val internalDbFileName: String = "aurum_internal.db",
    val rawPagesDirName: String = "raw_pages",
    val seedDbFileName: String = "aurum.db",
)

@Serializable
data class NotificationConfig(
    val channelDealsId: String = "aurum_deals_channel",
    val channelDealsName: String = "Deal Alerts & Blink Deals",
    val channelRefreshId: String = "aurum_background_refresh",
    val channelRefreshName: String = "Background Refresh",
    val backgroundWorkName: String = "aurum-background-refresh",
    val notificationRefreshId: Int = 4101,
    val deduplicationWindowMs: Long = 21600000L,
)

@Serializable
data class CircuitBreakerConfig(
    val failureThreshold: Int = 3,
    val cooldownDurationMs: Long = 900000L,
    val antiBotMarkers: List<String> = emptyList(),
)

@Serializable
data class PolicyConfig(
    val minPlausibleBullionRate24: Double = 3000.0,
    val maxPlausibleBullionRate24: Double = 50000.0,
    val minBullion22Ratio: Double = 0.72,
    val maxBullion22Ratio: Double = 1.02,
    val bullionMedianTolerance: Double = 0.06,
    val staleThresholdMillis: Long = 86400000L,
    val liveFreshnessMillis: Long = 86400000L,
    val microCoinMaxGrams: Double = 0.25,
    val unserviceableTerms: List<String> = emptyList(),
)

@Serializable
data class AppSettingsDefaultsConfig(
    val theme: String = "System",
    val refreshBullionOnStart: Boolean = false,
    val refreshProductsOnStart: Boolean = false,
    val dealMode: String = "Percent",
    val dealPercentThreshold: Double = 2.0,
    val dealRupeesThreshold: Double = 200.0,
    val backgroundRefreshEnabled: Boolean = false,
    val refreshIntervalMinutes: Int = 60,
    val allowedRefreshIntervals: List<Int> = emptyList(),
)

@Serializable
data class AjioBenchmarkTarget(
    val name: String = "",
    val url: String = "",
)

@Serializable
data class DebugConfig(
    val saveRawPages: Map<String, Boolean> = emptyMap(),
)

@Serializable
data class AurumConfig(
    val stores: Map<String, StoreDetailConfig> = emptyMap(),
    val bullion: BullionConfig = BullionConfig(),
    val delays: ScraperDelays = ScraperDelays(),
    val limits: ScraperLimits = ScraperLimits(),
    val network: NetworkConfig = NetworkConfig(),
    val location: LocationConfig = LocationConfig(),
    val storage: StorageConfig = StorageConfig(),
    val notifications: NotificationConfig = NotificationConfig(),
    val circuitBreaker: CircuitBreakerConfig = CircuitBreakerConfig(),
    val policy: PolicyConfig = PolicyConfig(),
    val appSettingsDefaults: AppSettingsDefaultsConfig = AppSettingsDefaultsConfig(),
    val ajioBenchmarkTargets: List<AjioBenchmarkTarget> = emptyList(),
    val debug: DebugConfig = DebugConfig(),
) {
    val ajioTargets: List<StoreTarget> get() = stores["ajio"]?.plpTargets ?: emptyList()
    val flipkartTargets: List<StoreTarget> get() = stores["flipkart"]?.plpTargets ?: emptyList()
    val shopsyTargets: List<StoreTarget> get() = stores["shopsy"]?.plpTargets ?: emptyList()
    val amazonTargets: List<StoreTarget> get() = stores["amazon"]?.plpTargets ?: emptyList()
    val myntraTargets: List<StoreTarget> get() = stores["myntra"]?.plpTargets ?: emptyList()
    val targets22k: List<StoreTarget> get() = listOfNotNull(
        stores["ajio"]?.targets22k,
        stores["flipkart"]?.targets22k,
        stores["myntra"]?.targets22k,
    ).flatten()
    val bullionTargets: List<BullionSourceConfig> get() = bullion.sources
}

typealias ScraperConfig = AurumConfig

object ScraperConfigProvider {
    private const val TAG = "AurumConfig"
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    @Volatile
    private var cachedConfig: AurumConfig = loadConfig(null)

    fun get(): AurumConfig = cachedConfig

    fun init(context: Context? = null) {
        cachedConfig = loadConfig(context)
    }

    fun update(config: AurumConfig, context: Context? = null) {
        cachedConfig = config
        saveConfig(config, context)
    }

    private fun loadConfig(context: Context?): AurumConfig {
        val candidateFiles = listOfNotNull(
            File("/storage/emulated/0/aurum/config.json"),
            context?.let { File(it.filesDir, "config.json") },
            File("src/main/assets/config.json"),
            File("app/src/main/assets/config.json"),
        )

        for (file in candidateFiles) {
            runCatching {
                if (file.exists() && file.canRead()) {
                    val content = file.readText()
                    if (content.isNotBlank()) {
                        logI(TAG, "Loaded config from ${file.absolutePath}")
                        return json.decodeFromString<AurumConfig>(content)
                    }
                }
            }.onFailure { e ->
                logW(TAG, "Failed reading config from ${file.absolutePath}: ${e.message}")
            }
        }

        context?.let { ctx ->
            runCatching {
                ctx.assets.open("config.json").bufferedReader().use { it.readText() }.let { content ->
                    if (content.isNotBlank()) {
                        logI(TAG, "Loaded default config from assets/config.json")
                        val assetConfig = json.decodeFromString<AurumConfig>(content)
                        saveConfig(assetConfig, ctx)
                        return assetConfig
                    }
                }
            }.onFailure { e ->
                logW(TAG, "Failed reading config from assets/config.json: ${e.message}")
            }
        }

        runCatching {
            ScraperConfigProvider::class.java.classLoader?.getResourceAsStream("config.json")?.bufferedReader()?.use { it.readText() }?.let { content ->
                if (content.isNotBlank()) {
                    return json.decodeFromString<AurumConfig>(content)
                }
            }
        }

        val defaultConfig = AurumConfig()
        saveConfig(defaultConfig, context)
        return defaultConfig
    }

    private fun saveConfig(config: AurumConfig, context: Context?) {
        val serialized = json.encodeToString(config)
        runCatching {
            val aurumDir = File(config.storage.externalDirPath)
            if (!aurumDir.exists()) aurumDir.mkdirs()
            val configFile = File(aurumDir, "config.json")
            configFile.writeText(serialized)
            logI(TAG, "Saved external config to ${configFile.absolutePath}")
        }.onFailure { e ->
            logW(TAG, "Failed writing external config: ${e.message}")
        }

        context?.let { ctx ->
            runCatching {
                val internalFile = File(ctx.filesDir, "config.json")
                internalFile.writeText(serialized)
                logI(TAG, "Saved internal config to ${internalFile.absolutePath}")
            }.onFailure { e ->
                logW(TAG, "Failed saving internal config: ${e.message}")
            }
        }
    }

    private fun logI(tag: String, msg: String) {
        runCatching { Log.i(tag, msg) }
    }

    private fun logW(tag: String, msg: String) {
        runCatching { Log.w(tag, msg) }
    }
}

typealias AurumConfigProvider = ScraperConfigProvider
