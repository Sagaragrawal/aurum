package com.aurum.intelligence.data.engine

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URL

class ShopsyCronetSession {

    init {
        if (CookieHandler.getDefault() == null) {
            CookieHandler.setDefault(CookieManager(null, CookiePolicy.ACCEPT_ALL))
        }
    }

    private fun getStandardHeaders(): Map<String, String> = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-IN,en-US;q=0.9,en;q=0.8",
        "Referer" to "https://www.shopsy.in/",
    )

    suspend fun bootstrap(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val response = CronetNetworkClient.executeCronetWithHeaders("https://www.shopsy.in/", getStandardHeaders())
            response.status in 200..299
        }.getOrDefault(false)
    }

    suspend fun setPincode(pincode: String, productId: String? = null): Boolean = withContext(Dispatchers.IO) {
        if (!pincode.matches(Regex("\\d{6}"))) return@withContext false
        val actionUrl = "https://www.shopsy.in/api/3/action/view"
        val payload = JSONObject().apply {
            put("actionRequestContext", JSONObject().apply {
                put("type", "PINCODE_SELECT")
                put("pincode", pincode)
                if (!productId.isNullOrBlank()) {
                    put("productId", productId)
                }
            })
        }.toString()

        val headers = getStandardHeaders().toMutableMap().apply {
            put("Content-Type", "application/json")
            put("Origin", "https://www.shopsy.in")
        }

        runCatching {
            val url = URL(actionUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 8000
                doOutput = true
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
                outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            conn.inputStream?.close()
            code in 200..299
        }.getOrDefault(false)
    }

    suspend fun fetchPlp(url: String): ProductFetchResponse = withContext(Dispatchers.IO) {
        CronetNetworkClient.executeCronetWithHeaders(url, getStandardHeaders())
    }

    suspend fun fetchPdp(url: String): ProductFetchResponse = withContext(Dispatchers.IO) {
        CronetNetworkClient.executeCronetWithHeaders(url, getStandardHeaders())
    }
}
