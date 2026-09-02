package com.aurum.intelligence.data

import androidx.room.withTransaction
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.math.round
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class BullionRefreshProgress(
    val running: Boolean = false,
    val total: Int = 0,
    val checked: Int = 0,
    val live: Int = 0,
    val current: String? = null,
    val note: String? = null,
)

class BullionRepository(private val database: AurumDatabase) {
    val sources = database.dao().observeBullionSources()
    val history = database.dao().observeRecentBullionHistory(480)
    private val refreshMutex = Mutex()
    private val mutableProgress = MutableStateFlow(BullionRefreshProgress())
    val progress = mutableProgress.asStateFlow()

    suspend fun ensureSources() = withContext(Dispatchers.IO) {
        database.dao().deleteImplausibleBullionHistory()
        defaultSources.forEach { source ->
            val existing = database.dao().bullionSourceById(source.id)
            when {
                existing == null -> database.dao().upsertBullionSource(source)
                BullionRatePolicy.isPlausible24(existing.price24) &&
                    !BullionRatePolicy.isPlausible22(existing.price22, existing.price24) -> {
                    val price24 = requireNotNull(existing.price24)
                    database.dao().upsertBullionSource(
                        existing.copy(
                            price22 = round(price24 * (22.0 / 24.0) * 100) / 100,
                            price22Derived = true,
                        ),
                    )
                }
            }
        }
    }

    suspend fun refresh(sourceId: String? = null) = refreshMutex.withLock {
        withContext(Dispatchers.IO) {
            ensureSources()
            val selected = database.dao().allBullionSources().filter { source ->
                if (sourceId == null) source.transport != TRANSPORT_BROWSER_REQUIRED else source.id == sourceId
            }
            require(selected.isNotEmpty()) { "Unknown bullion source: $sourceId" }
            val refreshStartedAt = System.currentTimeMillis()
            var checked = 0
            var live = 0
            mutableProgress.value = BullionRefreshProgress(running = true, total = selected.size)
            selected.forEach { previous ->
                val attemptedAt = refreshStartedAt
                database.dao().upsertBullionSource(
                    previous.copy(status = "checking", lastAttemptAt = attemptedAt, error = null),
                )
                mutableProgress.value = BullionRefreshProgress(true, selected.size, checked, live, previous.id)
                val result = runCatching { fetch(previous) }
                result.onSuccess { rates ->
                    val derived22 = rates.price22 == null
                    val price22 = rates.price22 ?: round(rates.price24!! * (22.0 / 24.0) * 100) / 100
                    val updated = previous.copy(
                        price24 = rates.price24,
                        price22 = price22,
                        price22Derived = derived22,
                        status = "live",
                        fetchedAt = attemptedAt,
                        lastLiveAt = attemptedAt,
                        lastAttemptAt = attemptedAt,
                        error = null,
                    )
                    database.withTransaction {
                        database.dao().upsertBullionSource(updated)
                        database.dao().insertBullionHistory(
                            BullionHistoryEntity(
                                sourceId = updated.id,
                                price24 = updated.price24!!,
                                price22 = price22,
                                price22Derived = derived22,
                                fetchedAt = attemptedAt,
                            ),
                        )
                    }
                    live += 1
                }.onFailure { failure ->
                    database.dao().upsertBullionSource(
                        previous.copy(
                            status = if (previous.price24 != null && previous.price24 > 0) "stale" else "unavailable",
                            lastAttemptAt = attemptedAt,
                            error = failure.message ?: "Rate fetch failed",
                        ),
                    )
                }
                checked += 1
                mutableProgress.value = BullionRefreshProgress(true, selected.size, checked, live, previous.id)
            }
            val note = when {
                live == selected.size -> "Live rates updated."
                live > 0 -> "$live of ${selected.size} sources updated; retained values are stale."
                else -> "No fresh rates received; retained values are stale."
            }
            mutableProgress.value = BullionRefreshProgress(false, selected.size, checked, live, note = note)
        }
    }

    suspend fun recordBrowserRates(sourceId: String, rates: BullionRates) = withContext(Dispatchers.IO) {
        require(BullionRatePolicy.isPlausible24(rates.price24)) { "Rendered 24K rate is invalid" }
        val price24 = requireNotNull(rates.price24)
        val previous = requireNotNull(database.dao().bullionSourceById(sourceId)) { "Unknown bullion source: $sourceId" }
        val now = System.currentTimeMillis()
        val supplied22 = rates.price22?.takeIf { BullionRatePolicy.isPlausible22(it, price24) }
        val derived22 = supplied22 == null
        val price22 = supplied22 ?: round(price24 * (22.0 / 24.0) * 100) / 100
        database.withTransaction {
            database.dao().upsertBullionSource(
                previous.copy(
                    price24 = price24,
                    price22 = price22,
                    price22Derived = derived22,
                    status = "live",
                    fetchedAt = now,
                    lastLiveAt = now,
                    lastAttemptAt = now,
                    error = null,
                ),
            )
            database.dao().insertBullionHistory(
                BullionHistoryEntity(
                    sourceId = sourceId,
                    price24 = price24,
                    price22 = price22,
                    price22Derived = derived22,
                    fetchedAt = now,
                ),
            )
        }
    }

    private fun fetch(source: BullionSourceEntity): BullionRates {
        if (source.transport == TRANSPORT_BROWSER_REQUIRED) {
            error("Browser rendering required; direct Android HTTP is unavailable for ${source.source}")
        }
        val response = when (source.id) {
            "malabar" -> request(malabarApiUrl(), "GET", accept = "application/json")
            "mmtc" -> request(
                "https://www.mmtcpamp.com/api/getQuote",
                "POST",
                body = "{\"currencyPair\":\"XAU/INR\",\"type\":\"BUY\"}",
                accept = "application/json",
                referer = source.url,
            )
            "kalyan" -> request(source.url, "GET")
            else -> error("No direct Android collector for ${source.id}")
        }
        return BullionRateParser.parse(response, source.id).also {
            require(it.price24 != null && it.price24 > 0) { "24K rate not found in ${source.source} response" }
        }
    }

    private fun request(
        url: String,
        method: String,
        body: String? = null,
        accept: String = "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
        referer: String? = null,
    ): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 12_000
            connection.readTimeout = 12_000
            connection.setRequestProperty("Accept", accept)
            connection.setRequestProperty("Accept-Language", "en-IN,en;q=0.9")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            referer?.let { connection.setRequestProperty("Referer", it) }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            }
            val status = connection.responseCode
            if (status == 403 || status == 429) error("Bot challenge ($status)")
            if (status !in 200..299) error("HTTP $status")
            return connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun malabarApiUrl(): String {
        val query = "query getMetalRate(\$filter: MetalRateFilterInput) { getMetalRate(filter: \$filter) { items { entry_date entry_time purity unit rate country state } } }"
        val variables = "{\"filter\":{\"metal_type\":\"gold\",\"country\":\"India\"}}"
        return "https://www.malabargoldanddiamonds.com/graphql-magento?query=" +
            URLEncoder.encode(query, StandardCharsets.UTF_8.name()) + "&variables=" +
            URLEncoder.encode(variables, StandardCharsets.UTF_8.name())
    }

    companion object {
        const val TRANSPORT_DIRECT_HTTP = "direct_http"
        const val TRANSPORT_BROWSER_REQUIRED = "browser_required"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/140 Mobile Safari/537.36"
        private val defaultSources = listOf(
            BullionSourceEntity("tan", "Tanishq", "Tanishq gold rate", "https://www.tanishq.co.in/gold-rate.html", null, null, false, "unavailable", TRANSPORT_BROWSER_REQUIRED, null, null, null, "Browser rendering required"),
            BullionSourceEntity("malabar", "Malabar Gold & Diamonds", "Malabar Gold & Diamonds", "https://www.malabargoldanddiamonds.com/in/pan-india/en/live-gold-rate.html", null, null, false, "unavailable", TRANSPORT_DIRECT_HTTP, null, null, null, null),
            BullionSourceEntity("mmtc", "MMTC-PAMP", "MMTC-PAMP", "https://www.mmtcpamp.com/gold-silver-rate-today", null, null, false, "unavailable", TRANSPORT_DIRECT_HTTP, null, null, null, null),
            BullionSourceEntity("kalyan", "Kalyan Jewellers", "Kalyan Jewellers", "https://store.kalyanjewellers.net/gold-rate/india/en", null, null, false, "unavailable", TRANSPORT_DIRECT_HTTP, null, null, null, null),
        )
    }
}