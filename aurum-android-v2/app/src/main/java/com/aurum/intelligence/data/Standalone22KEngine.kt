package com.aurum.intelligence.data

import android.util.Log
import com.aurum.intelligence.parsers.AjioNativeParser
import com.aurum.intelligence.parsers.FlipkartNativeParser
import com.aurum.intelligence.parsers.MyntraNativeParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

data class Store22KReport(
    val store: String,
    val targetName: String,
    val productsFound: Int,
    val itemsWithCompletePlpData: Int,
    val itemsRequiringPdp: Int,
    val durationMs: Long,
    val error: String? = null,
)

data class Summary22KReport(
    val storeReports: Map<String, Store22KReport>,
    val total22kProducts: Int,
    val totalRequiringPdp: Int,
    val totalDurationMs: Long,
)

object Standalone22KEngine {
    private const val TAG = "Standalone22KEngine"

    suspend fun audit22kAcrossStores(pincode: String = "560048"): Summary22KReport = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val config = ScraperConfigProvider.get()

        val reports = coroutineScope {
            val ajioDeferred = async { auditAjio22K(config, pincode) }
            val flipkartDeferred = async { auditFlipkart22K(config, pincode) }
            val myntraDeferred = async { auditMyntra22K(config, pincode) }

            listOf(ajioDeferred.await(), flipkartDeferred.await(), myntraDeferred.await())
        }

        val storeMap = reports.associateBy { it.store }
        val totalProducts = reports.sumOf { it.productsFound }
        val totalPdpNeeded = reports.sumOf { it.itemsRequiringPdp }
        val totalDuration = System.currentTimeMillis() - start

        Log.i(TAG, "22K Audit Complete: $totalProducts 22K products found, $totalPdpNeeded requiring PDP (${totalDuration}ms)")

        Summary22KReport(
            storeReports = storeMap,
            total22kProducts = totalProducts,
            totalRequiringPdp = totalPdpNeeded,
            totalDurationMs = totalDuration,
        )
    }

    private suspend fun auditAjio22K(config: ScraperConfig, pincode: String): Store22KReport {
        val start = System.currentTimeMillis()
        val target = config.targets22k.firstOrNull { it.name.contains("Ajio", ignoreCase = true) }
            ?: return Store22KReport("ajio.com", "Ajio 22K", 0, 0, 0, 0, "No target configured")

        return try {
            val url = "${target.url}&currentPage=0&pincode=$pincode"
            val resp = CronetNetworkClient.executeCronetApiRequest(url, pincode)
            if (resp.status in 200..299) {
                val parsed = AjioNativeParser.parse(resp.body, null)
                val total = parsed.totalResults
                val count = parsed.candidates.size
                val complete = parsed.candidates.count { it.price > 0 && it.grams != null && it.grams > 0 }
                val pdpNeeded = count - complete
                Store22KReport("ajio.com", target.name, total.coerceAtLeast(count), complete, pdpNeeded, System.currentTimeMillis() - start)
            } else {
                Store22KReport("ajio.com", target.name, 0, 0, 0, System.currentTimeMillis() - start, "HTTP ${resp.status}")
            }
        } catch (e: Exception) {
            Store22KReport("ajio.com", target.name, 0, 0, 0, System.currentTimeMillis() - start, e.message)
        }
    }

    private suspend fun auditFlipkart22K(config: ScraperConfig, pincode: String): Store22KReport {
        val start = System.currentTimeMillis()
        val target = config.targets22k.firstOrNull { it.name.contains("Flipkart", ignoreCase = true) }
            ?: return Store22KReport("flipkart.com", "Flipkart 22K", 0, 0, 0, 0, "No target configured")

        val desktopHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        )

        return try {
            val url = "${target.url}&pinCode=$pincode"
            val resp = CronetNetworkClient.executeCronetWithHeaders(url, desktopHeaders)
            if (resp.status in 200..299) {
                val parsed = FlipkartNativeParser.parse(resp.body, "flipkart.com", null)
                val total = parsed.totalResults
                val count = parsed.candidates.size
                val complete = parsed.candidates.count { it.price > 0 && it.grams != null && it.grams > 0 }
                val pdpNeeded = count - complete
                Store22KReport("flipkart.com", target.name, total.coerceAtLeast(count), complete, pdpNeeded, System.currentTimeMillis() - start)
            } else {
                Store22KReport("flipkart.com", target.name, 0, 0, 0, System.currentTimeMillis() - start, "HTTP ${resp.status}")
            }
        } catch (e: Exception) {
            Store22KReport("flipkart.com", target.name, 0, 0, 0, System.currentTimeMillis() - start, e.message)
        }
    }

    private suspend fun auditMyntra22K(config: ScraperConfig, pincode: String): Store22KReport {
        val start = System.currentTimeMillis()
        val target = config.targets22k.firstOrNull { it.name.contains("Myntra", ignoreCase = true) }
            ?: return Store22KReport("myntra.com", "Myntra 22K", 0, 0, 0, 0, "No target configured")

        val gatewayHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "application/json",
            "x-myntraweb" to "Yes",
            "x-requested-with" to "browser",
            "x-meta-app" to "channel=web",
        )

        return try {
            val url = "https://www.myntra.com/gateway/v4/search/${target.slug}?rows=50&o=0&p=1&plaEnabled=true&xdEnabled=false&isFacet=true&pincode=$pincode&${target.filterQuery}"
            val resp = CronetNetworkClient.executeCronetWithHeaders(url, gatewayHeaders)
            if (resp.status in 200..299) {
                val parsed = MyntraNativeParser.parse(resp.body, null)
                val total = parsed.totalCount
                val count = parsed.candidates.size
                val complete = parsed.candidates.count { it.price > 0 && it.grams != null && it.grams > 0 }
                val pdpNeeded = count - complete
                Store22KReport("myntra.com", target.name, total.coerceAtLeast(count), complete, pdpNeeded, System.currentTimeMillis() - start)
            } else {
                Store22KReport("myntra.com", target.name, 0, 0, 0, System.currentTimeMillis() - start, "HTTP ${resp.status}")
            }
        } catch (e: Exception) {
            Store22KReport("myntra.com", target.name, 0, 0, 0, System.currentTimeMillis() - start, e.message)
        }
    }
}

