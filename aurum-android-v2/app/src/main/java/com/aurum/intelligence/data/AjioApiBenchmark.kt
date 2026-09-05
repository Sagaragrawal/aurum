package com.aurum.intelligence.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AjioApiBenchmark {

    private const val TAG = "AllStoresBenchmark"

    suspend fun runAllTests() = withContext(Dispatchers.IO) {
        Log.i(TAG, "==========================================================")
        Log.i(TAG, "  STARTING COMPLETE PARALLEL ENGINE STORE & BULLION PROBE")
        Log.i(TAG, "==========================================================")

        val testTargets = listOf(
            "AJIO Category 8303" to "https://www.ajio.com/api/category/8303?fields=SITE&currentPage=0&pageSize=45&format=json&query=%3Arelevance%3Averticalmetalpurity%3A24%20Kt%20%28995%29%3Averticalmetalpurity%3A24%20Kt%3Averticalmetalpurity%3A999%3Averticalmetalpurity%3A24%20Kt%20%28999.9%29%3Averticalmetalpurity%3A24%20Kt%20%28999%29%3Averticalmetalpurity%3A22%20Kt&facets=verticalmetalpurity%3A24%20Kt%20%28995%29%3Averticalmetalpurity%3A24%20Kt%3Averticalmetalpurity%3A999%3Averticalmetalpurity%3A24%20Kt%20%28999.9%29%3Averticalmetalpurity%3A24%20Kt%20%28999%29%3Averticalmetalpurity%3A22%20Kt&gridColumns=3&cohortIds=nontransacted%7Cp_null%2Cfalse%2Cunisex%2Cnoasp&advfilter=true&platform=Android&showAdsOnNextPage=false&is_ads_enable_plp=true&displayRatings=true&store=ajio&pincode=560048&enableRushDelivery=true&vertexEnabled=false&previousSource=Saas",
            "FLIPKART Coins" to "https://www.flipkart.com/gold-silver-coins/pr?sid=mcr%2C73x%2Cydh&marketplace=FLIPKART&p%5B%5D=facets.material%255B%255D%3DYellow%2BGold&p%5B%5D=facets.material%255B%255D%3DGold",
            "SHOPSY Coins" to "https://www.shopsy.in/gold-silver-coins/pr?sid=mcr,73x&marketplace=FLIPKART&p[]=facets.material[]=Gold&p[]=facets.material[]=Yellow+Gold&p[]=facets.gold_purity%5B%5D=24+%28999%29+K&p%5B%5D=facets.gold_purity%255B%255D%3D24%2B%25289999%2529%2BK",
            "AMAZON Jewelry Search" to "https://www.amazon.in/s?i=jewelry&rh=n%3A2908910031%2Cp_n_material_two_browse-bin%3A2160347031&s=popularity-rank&dc&fs=true&rnid=2160329031&xpid=ZrCUqOwcv7FyR&ref=sr_pg_1",
            "MYNTRA Gold Coin Page" to "https://www.myntra.com/gold-coin",
            "BULLION - Malabar GraphQL" to "https://www.malabargoldanddiamonds.com/graphql-magento?query=query%20getMetalRate(%24filter%3A%20MetalRateFilterInput)%20%7B%20getMetalRate(filter%3A%20%24filter)%20%7B%20items%20%7B%20entry_date%20entry_time%20purity%20unit%20rate%20country%20state%20%7D%20%7D%20%7D&variables=%7B%22filter%22%3A%7B%22metal_type%22%3A%22gold%22%2C%22country%22%3A%22India%22%7D%7D",
            "BULLION - MMTC Rate Page" to "https://www.mmtcpamp.com/gold-silver-rate-today",
            "BULLION - Kalyan Rate Page" to "https://store.kalyanjewellers.net/gold-rate/india/en",
            "BULLION - Tanishq Rate Page" to "https://www.tanishq.co.in/gold-rate.html"
        )

        for ((name, url) in testTargets) {
            val start = System.currentTimeMillis()
            runCatching {
                val response = CronetNetworkClient.executeCronetRequest(url)
                val duration = System.currentTimeMillis() - start
                Log.i(TAG, "[$name] HTTP ${response.status} (${duration}ms) | Body: ${response.body.length} chars | Snippet: ${response.body.take(150).replace("\n", " ")}")
            }.onFailure {
                val duration = System.currentTimeMillis() - start
                Log.e(TAG, "[$name] FAILED (${duration}ms): ${it.message}")
            }
        }

        Log.i(TAG, "==========================================================")
        Log.i(TAG, "  FINISHED COMPLETE PROBE")
        Log.i(TAG, "==========================================================")
    }
}
