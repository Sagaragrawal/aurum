package com.aurum.intelligence.data

import android.util.Log
import com.aurum.intelligence.parsers.AjioNativeParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

object AjioApiBenchmark {

    private const val TAG = "AllStoresBenchmark"

    data class ProbeTarget(
        val name: String,
        val url: String
    )

    suspend fun runAllTests() = withContext(Dispatchers.IO) {
        Log.i(TAG, "==========================================================")
        Log.i(TAG, "  STARTING ADVANCED AJIO ENDPOINT RECOVERY PROBE")
        Log.i(TAG, "==========================================================")

        val pincode = "560048"

        val targets = listOf(
            ProbeTarget(
                "AJIO Category 8303 (Gold / Jewellery)",
                "https://www.ajio.com/api/category/8303?fields=SITE&currentPage=0&pageSize=45&format=json&query=%3Arelevance%3Averticalmetalpurity%3A24%20Kt%20%28995%29%3Averticalmetalpurity%3A24%20Kt%3Averticalmetalpurity%3A999%3Averticalmetalpurity%3A24%20Kt%20%28999.9%29%3Averticalmetalpurity%3A24%20Kt%20%28999%29%3Averticalmetalpurity%3A22%20Kt&facets=verticalmetalpurity%3A24%20Kt%20%28995%29%3Averticalmetalpurity%3A24%20Kt%3Averticalmetalpurity%3A999%3Averticalmetalpurity%3A24%20Kt%20%28999.9%29%3Averticalmetalpurity%3A24%20Kt%20%28999%29%3Averticalmetalpurity%3A22%20Kt&gridColumns=3&platform=Android&store=ajio&pincode=$pincode"
            ),
            ProbeTarget(
                "AJIO Search ('gold coin')",
                "https://www.ajio.com/api/search?fields=SITE&currentPage=0&pageSize=45&format=json&query=gold%20coin%3Arelevance&text=gold%20coin&gridColumns=3&platform=Android&store=ajio&pincode=$pincode"
            ),
            ProbeTarget(
                "AJIO Search ('gold bar')",
                "https://www.ajio.com/api/search?fields=SITE&currentPage=0&pageSize=45&format=json&query=gold%20bar%3Arelevance&text=gold%20bar&gridColumns=3&platform=Android&store=ajio&pincode=$pincode"
            ),
            ProbeTarget(
                "AJIO Search ('24 kt gold coin')",
                "https://www.ajio.com/api/search?fields=SITE&currentPage=0&pageSize=45&format=json&query=24%20kt%20gold%20coin%3Arelevance&text=24%20kt%20gold%20coin&gridColumns=3&platform=Android&store=ajio&pincode=$pincode"
            ),
            ProbeTarget(
                "AJIO Search ('22 kt gold coin')",
                "https://www.ajio.com/api/search?fields=SITE&currentPage=0&pageSize=45&format=json&query=22%20kt%20gold%20coin%3Arelevance&text=22%20kt%20gold%20coin&gridColumns=3&platform=Android&store=ajio&pincode=$pincode"
            )
        )

        for (target in targets) {
            runCatching {
                val response = CronetNetworkClient.executeCronetApiRequest(target.url)

                var parsedTotalCount = -1
                var parsedTotalPages = -1
                var parsedProductCount = 0

                if (response.status == 200 && response.body.isNotBlank()) {
                    runCatching {
                        val parsedResult = AjioNativeParser.parse(response.body, null)
                        parsedProductCount = parsedResult.candidates.size
                        parsedTotalPages = parsedResult.totalPages
                    }
                    runCatching {
                        val json = JSONObject(response.body)
                        val pagination = json.optJSONObject("pagination")
                        if (pagination != null) {
                            parsedTotalCount = pagination.optInt("totalResults", -1)
                        }
                    }
                }

                Log.i(
                    TAG,
                    "Target: ${target.name} | HTTP ${response.status} (${response.durationMs}ms) | Products: $parsedProductCount | totalResults: $parsedTotalCount | totalPages: $parsedTotalPages"
                )
            }.onFailure { err ->
                Log.e(TAG, "Target: ${target.name} FAILED: ${err.message}", err)
            }
        }

        Log.i(TAG, "==========================================================")
        Log.i(TAG, "  ADVANCED PROBE COMPLETE")
        Log.i(TAG, "==========================================================")
    }
}
