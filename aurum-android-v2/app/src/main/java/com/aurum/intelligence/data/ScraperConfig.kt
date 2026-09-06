package com.aurum.intelligence.data

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class StoreTarget(
    val name: String,
    val url: String,
    val slug: String = "",
    val filterQuery: String = "",
    val isMinutes: Boolean = false,
)

@Serializable
data class ScraperDelays(
    val ajioPageDelayMs: Long = 1200L,
    val ajioRateLimitBackoffMs: Long = 3000L,
    val flipkartPageDelayMs: Long = 350L,
    val shopsyPageDelayMs: Long = 200L,
    val amazonPageDelayMs: Long = 400L,
    val myntraWebPageDelayMs: Long = 400L,
    val myntraApiPageDelayMs: Long = 250L,
    val pdpInterRequestDelayMs: Long = 300L,
    val pdpAmazonDelayMs: Long = 400L,
    val pdpRateLimitBackoffMs: Long = 3000L,
)

@Serializable
data class ScraperLimits(
    val maxPagesPerStore: Int = 100,
    val ajioPageSize: Int = 45,
    val myntraPageSize: Int = 50,
    val maxPdpItemsPerStore: Int = 30,
)

@Serializable
data class NetworkConfig(
    val connectTimeoutMs: Int = 15000,
    val readTimeoutMs: Int = 20000,
    val maxConsecutive403sBeforeSkip: Int = 2,
    val retryBackoffMs: Long = 3000L,
)

@Serializable
data class BullionTarget(
    val sourceId: String,
    val label: String,
    val url: String,
)

@Serializable
data class ScraperConfig(
    val ajioTargets: List<StoreTarget> = listOf(
        StoreTarget(
            name = "Category 83 (Master 24K Pure Gold Jewellery, Coins & Bars)",
            url = "https://www.ajio.com/api/category/83?pageSize=45&format=json&query=%3Arelevance%3Arelevance%3Aundefined%3Averticalmetalpurity%3A24+Kt+%28995%29%3Averticalmetalpurity%3A24+Kt%3Averticalmetalpurity%3A999%3Averticalmetalpurity%3A24+Kt+%28999.9%29%3Averticalmetalpurity%3A24+Kt+%28999%29&fields=SITE&facets=relevance%3Aundefined%3Averticalmetalpurity%3A24+Kt+%28995%29%3Averticalmetalpurity%3A24+Kt%3Averticalmetalpurity%3A999%3Averticalmetalpurity%3A24+Kt+%28999.9%29%3Averticalmetalpurity%3A24+Kt+%28999%29&gridColumns=3&platform=Android&store=ajio&curated=true&advfilter=true"
        )
    ),
    val flipkartTargets: List<StoreTarget> = listOf(
        StoreTarget(
            name = "Flipkart 24K Pure Gold Coins & Bars",
            url = "https://www.flipkart.com/gold-silver-coins/pr?sid=mcr%2C73x%2Cydh&marketplace=FLIPKART&p%5B%5D=facets.material%255B%255D%3DYellow%2BGold&p%5B%5D=facets.material%255B%255D%3DGold&p%5B%5D=facets.gold_purity%255B%255D%3D24%2B%2528999%2529%2BK&p%5B%5D=facets.gold_purity%255B%255D%3D24%2B%25289999%2529%2BK",
            isMinutes = false
        ),
        StoreTarget(
            name = "Flipkart Minutes Instant Gold",
            url = "https://www.flipkart.com/minutes/search?q=gold+coin",
            isMinutes = true
        )
    ),
    val shopsyTargets: List<StoreTarget> = listOf(
        StoreTarget(
            name = "Shopsy Gold Coins",
            url = "https://www.shopsy.in/search?q=gold+coin"
        )
    ),
    val amazonTargets: List<StoreTarget> = listOf(
        StoreTarget(
            name = "Amazon Gold Coins & Bars (Popularity)",
            url = "https://www.amazon.in/s?i=jewelry&rh=n%3A2908910031%2Cp_n_material_two_browse-bin%3A2160347031&s=popularity-rank&dc&fs=true&rnid=2160329031&xpid=ZrCUqOwcv7FyR"
        )
    ),
    val myntraTargets: List<StoreTarget> = listOf(
        StoreTarget(
            name = "Myntra Gold Coins",
            url = "https://www.myntra.com/gold-coin",
            slug = "gold-coin",
            filterQuery = ""
        ),
        StoreTarget(
            name = "Myntra Gold Bars (Coin Category)",
            url = "https://www.myntra.com/gold-bar?f=Categories%3AGold%20Coin",
            slug = "gold-bar",
            filterQuery = "f=Categories%3AGold%20Coin"
        )
    ),
    val bullionTargets: List<BullionTarget> = listOf(
        BullionTarget(
            sourceId = "malabar",
            label = "Malabar Gold & Diamonds",
            url = "https://www.malabargoldanddiamonds.com/graphql-magento?query=query%20getMetalRate(%24filter%3A%20MetalRateFilterInput)%20%7B%20getMetalRate(filter%3A%20%24filter)%20%7B%20items%20%7B%20entry_date%20entry_time%20purity%20unit%20rate%20country%20state%20%7D%20%7D%20%7D&variables=%7B%22filter%22%3A%7B%22metal_type%22%3A%22gold%22%2C%22country%22%3A%22India%22%7D%7D"
        ),
        BullionTarget(
            sourceId = "mmtc",
            label = "MMTC-PAMP",
            url = "https://www.mmtcpamp.com/gold-silver-rate-today"
        ),
        BullionTarget(
            sourceId = "kalyan",
            label = "Kalyan Jewellers",
            url = "https://store.kalyanjewellers.net/gold-rate/india/en"
        ),
        BullionTarget(
            sourceId = "tan",
            label = "Tanishq gold rate",
            url = "https://www.tanishq.co.in/gold-rate.html"
        )
    ),
    val targets22k: List<StoreTarget> = listOf(
        StoreTarget(
            name = "Ajio 22K Gold Coins",
            url = "https://www.ajio.com/api/category/83?pageSize=45&format=json&query=%3Arelevance%3Averticalmetalpurity%3A22+Kt+%28916%29&fields=SITE&gridColumns=3&platform=Android&store=ajio"
        ),
        StoreTarget(
            name = "Flipkart 22K Gold Coins",
            url = "https://www.flipkart.com/gold-silver-coins/pr?sid=mcr%2C73x%2Cydh&marketplace=FLIPKART&p%5B%5D=facets.gold_purity%255B%255D%3D22%2B%2528916%2529%2BK"
        ),
        StoreTarget(
            name = "Myntra 22K Gold Coins",
            url = "https://www.myntra.com/gold-coin?f=Purity:22K",
            slug = "gold-coin",
            filterQuery = "f=Purity:22K"
        )
    ),
    val delays: ScraperDelays = ScraperDelays(),
    val limits: ScraperLimits = ScraperLimits(),
    val network: NetworkConfig = NetworkConfig(),
)

object ScraperConfigProvider {
    private const val TAG = "ScraperConfig"
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    @Volatile
    private var cachedConfig: ScraperConfig = ScraperConfig()

    fun get(): ScraperConfig = cachedConfig

    fun init(context: Context? = null) {
        cachedConfig = loadConfig(context)
    }

    fun update(config: ScraperConfig, context: Context? = null) {
        cachedConfig = config
        saveConfig(config, context)
    }

    private fun loadConfig(context: Context?): ScraperConfig {
        val rootSdcard = Environment.getExternalStorageDirectory()
        val candidateFiles = listOf(
            File(rootSdcard, "Aurum/config/scraper_config.json"),
            File(rootSdcard, "aurum/scraper_config.json"),
            File(rootSdcard, "Aurum/scraper_config.json"),
            context?.let { File(it.filesDir, "scraper_config.json") }
        ).filterNotNull()

        for (file in candidateFiles) {
            runCatching {
                if (file.exists() && file.canRead()) {
                    val content = file.readText()
                    if (content.isNotBlank()) {
                        Log.i(TAG, "Loaded scraper config from ${file.absolutePath}")
                        return json.decodeFromString<ScraperConfig>(content)
                    }
                }
            }.onFailure { e ->
                Log.w(TAG, "Failed reading scraper config from ${file.absolutePath}: ${e.message}")
            }
        }

        // Auto-create default config in /sdcard/Aurum/config/scraper_config.json for easy user editing
        val defaultConfig = ScraperConfig()
        saveConfig(defaultConfig, context)
        return defaultConfig
    }

    private fun saveConfig(config: ScraperConfig, context: Context?) {
        val rootSdcard = Environment.getExternalStorageDirectory()
        runCatching {
            val configDir = File(rootSdcard, "Aurum/config")
            if (!configDir.exists()) configDir.mkdirs()
            val configFile = File(configDir, "scraper_config.json")
            configFile.writeText(json.encodeToString(config))
            Log.i(TAG, "Saved external scraper config to ${configFile.absolutePath}")
        }.onFailure { e ->
            Log.w(TAG, "Failed writing external scraper config: ${e.message}")
        }

        context?.let { ctx ->
            runCatching {
                val internalFile = File(ctx.filesDir, "scraper_config.json")
                internalFile.writeText(json.encodeToString(config))
                Log.i(TAG, "Saved internal scraper config to ${internalFile.absolutePath}")
            }.onFailure { e ->
                Log.w(TAG, "Failed saving internal scraper config: ${e.message}")
            }
        }
    }
}
