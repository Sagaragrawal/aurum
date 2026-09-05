package com.aurum.intelligence.parsers

import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class NativeParserLiveTest {

    private fun fetchUrl(targetUrl: String, headers: Map<String, String> = emptyMap()): Pair<Int, String> {
        return try {
            val url = URL(targetUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Mobile Safari/537.36")
                setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                setRequestProperty("Accept-Language", "en-IN,en-US;q=0.9,en;q=0.8")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            code to text
        } catch (e: Exception) {
            500 to (e.message ?: "Error")
        }
    }

    @Test
    fun testAjioLive() {
        val url = "https://www.ajio.com/api/category/8303?fields=SITE&pageSize=45&format=json&query=%3Arelevance%3Averticalmetalpurity%3A24%20Kt%20%28995%29%3Averticalmetalpurity%3A24%20Kt%3Averticalmetalpurity%3A999%3Averticalmetalpurity%3A24%20Kt%20%28999.9%29%3Averticalmetalpurity%3A24%20Kt%20%28999%29%3Averticalmetalpurity%3A22%20Kt&facets=verticalmetalpurity%3A24%20Kt%20%28995%29%3Averticalmetalpurity%3A24%20Kt%3Averticalmetalpurity%3A999%3Averticalmetalpurity%3A24%20Kt%20%28999.9%29%3Averticalmetalpurity%3A24%20Kt%20%28999%29%3Averticalmetalpurity%3A22%20Kt&gridColumns=3&cohortIds=nontransacted%7Cp_null%2Cfalse%2Cunisex%2Cnoasp&advfilter=true&platform=Android&showAdsOnNextPage=false&is_ads_enable_plp=true&displayRatings=true&store=ajio&pincode=560048&enableRushDelivery=true&vertexEnabled=false&previousSource=Saas&currentPage=0"
        val (status, body) = fetchUrl(url)
        println("=== AJIO LIVE RESULT ===")
        println("Status: $status, Body length: ${body.length}")
        val res = AjioNativeParser.parse(body)
        println("Discovered: ${res.candidates.size}, TotalResults: ${res.totalResults}, TotalPages: ${res.totalPages}")
        if (res.candidates.isNotEmpty()) {
            val sample = res.candidates.first()
            println("Sample: ${sample.retailerId} | ${sample.name} | Price: ${sample.price} | Grams: ${sample.grams}")
        }
    }

    @Test
    fun testFlipkartLive() {
        val url = "https://www.flipkart.com/gold-silver-coins/pr?sid=mcr%2C73x%2Cydh&marketplace=FLIPKART&p%5B%5D=facets.material%255B%255D%3DYellow%2BGold&p%5B%5D=facets.material%255B%255D%3DGold"
        val (status, body) = fetchUrl(url)
        println("=== FLIPKART LIVE RESULT ===")
        println("Status: $status, Body length: ${body.length}")
        val res = FlipkartNativeParser.parse(body, "flipkart.com")
        println("Discovered: ${res.candidates.size}, TotalResults: ${res.totalResults}")
        if (res.candidates.isNotEmpty()) {
            val sample = res.candidates.first()
            println("Sample: ${sample.retailerId} | ${sample.name} | Price: ${sample.price} | Grams: ${sample.grams}")
        } else {
            println("Body snippet (first 1000 chars): ${body.take(1000)}")
            println("Body contains __NEXT_DATA__: ${body.contains("__NEXT_DATA__")}")
            println("Body contains data-id: ${body.contains("data-id")}")
        }
    }

    @Test
    fun testShopsyLive() {
        val url = "https://www.shopsy.in/gold-silver-coins/pr?sid=mcr,73x&marketplace=FLIPKART&p[]=facets.material[]=Gold&p[]=facets.material[]=Yellow+Gold&p[]=facets.gold_purity%5B%5D=24+%28999%29+K&p%5B%5D=facets.gold_purity%255B%255D%3D24%2B%25289999%2529%2BK"
        val (status, body) = fetchUrl(url)
        println("=== SHOPSY LIVE RESULT ===")
        println("Status: $status, Body length: ${body.length}")
        val res = FlipkartNativeParser.parse(body, "shopsy.in")
        println("Discovered: ${res.candidates.size}, TotalResults: ${res.totalResults}")
        if (res.candidates.isNotEmpty()) {
            val sample = res.candidates.first()
            println("Sample: ${sample.retailerId} | ${sample.name} | Price: ${sample.price} | Grams: ${sample.grams}")
        } else {
            println("Body snippet (first 1000 chars): ${body.take(1000)}")
            println("Body contains __NEXT_DATA__: ${body.contains("__NEXT_DATA__")}")
            println("Body contains data-id: ${body.contains("data-id")}")
        }
    }

    @Test
    fun testAmazonLive() {
        val url = "https://www.amazon.in/s?i=jewelry&rh=n%3A2908910031%2Cp_n_material_two_browse-bin%3A2160347031&s=popularity-rank&dc&fs=true&rnid=2160329031&xpid=ZrCUqOwcv7FyR&ref=sr_pg_1"
        val (status, body) = fetchUrl(url)
        println("=== AMAZON LIVE RESULT ===")
        println("Status: $status, Body length: ${body.length}")
        val res = AmazonNativeParser.parse(body)
        println("Discovered: ${res.candidates.size}, TotalResults: ${res.totalResults}")
        if (res.candidates.isNotEmpty()) {
            val sample = res.candidates.first()
            println("Sample: ${sample.retailerId} | ${sample.name} | Price: ${sample.price} | Grams: ${sample.grams}")
        }
    }

    @Test
    fun testMyntraLive() {
        val gatewayHeaders = mapOf(
            "x-myntraweb" to "Yes",
            "x-requested-with" to "browser",
            "x-meta-app" to "channel=web",
            "Referer" to "https://www.myntra.com/gold-coin",
        )
        val url = "https://www.myntra.com/gateway/v4/search/gold-coin?rows=50&o=0&p=1&plaEnabled=true&xdEnabled=false&isFacet=true&pincode=560048"
        val (status, body) = fetchUrl(url, gatewayHeaders)
        println("=== MYNTRA LIVE RESULT ===")
        println("Status: $status, Body length: ${body.length}")
        val res = MyntraNativeParser.parse(body)
        println("Discovered: ${res.candidates.size}, TotalCount: ${res.totalCount}")
        if (res.candidates.isNotEmpty()) {
            val sample = res.candidates.first()
            println("Sample: ${sample.retailerId} | ${sample.name} | Price: ${sample.price} | Grams: ${sample.grams}")
        }
    }

    @Test
    fun testBullionLive() {
        val sources = mapOf(
            "malabar" to "https://www.malabargoldanddiamonds.com/graphql-magento?query=query%20getMetalRate(%24filter%3A%20MetalRateFilterInput)%20%7B%20getMetalRate(filter%3A%20%24filter)%20%7B%20items%20%7B%20entry_date%20entry_time%20purity%20unit%20rate%20country%20state%20%7D%20%7D%20%7D&variables=%7B%22filter%22%3A%7B%22metal_type%22%3A%22gold%22%2C%22country%22%3A%22India%22%7D%7D",
            "mmtc" to "https://www.mmtcpamp.com/gold-silver-rate-today",
            "kalyan" to "https://store.kalyanjewellers.net/gold-rate/india/en",
            "tan" to "https://www.tanishq.co.in/gold-rate.html"
        )
        for ((id, url) in sources) {
            val (status, body) = fetchUrl(url)
            val parsed = BullionNativeParser.parse(id, body)
            println("=== BULLION $id LIVE RESULT ===")
            println("Status: $status, Body length: ${body.length}, 24K: ${parsed.price24}, 22K: ${parsed.price22}, Derived: ${parsed.price22Derived}")
        }
    }
}
