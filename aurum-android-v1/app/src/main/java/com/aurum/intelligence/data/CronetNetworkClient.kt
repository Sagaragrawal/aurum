package com.aurum.intelligence.data

import android.content.Context
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import org.chromium.net.CronetEngine
import org.chromium.net.CronetException
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import java.nio.ByteBuffer
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

object CronetNetworkClient {

    private var cronetEngine: CronetEngine? = null
    var initError: String? = null
        private set

    fun initialize(context: Context) {
        if (cronetEngine == null) {
            try {
                com.google.android.gms.net.CronetProviderInstaller.installProvider(context)
            } catch (e: Exception) {
                Log.w("CronetClient", "CronetProviderInstaller error: ${e.message}")
            }
            try {
                cronetEngine = CronetEngine.Builder(context)
                    .enableHttp2(true)
                    .enableQuic(true)
                    .setUserAgent("Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Mobile Safari/537.36")
                    .build()
                Log.i("CronetClient", "CronetEngine successfully created: ${cronetEngine?.versionString}")
            } catch (e: Exception) {
                initError = e.message
                Log.e("CronetClient", "Failed to create CronetEngine: ${e.message}", e)
            }
        }
    }

    suspend fun executeCronetWithHeaders(
        targetUrl: String,
        headers: Map<String, String>
    ): ProductFetchResponse = suspendCancellableCoroutine { continuation ->
        val engine = cronetEngine
        if (engine == null) {
            Log.w("CronetClient", "Cronet engine is NULL! Falling back to standard HttpURLConnection. Init error: $initError")
            val response = executeStandardRequestWithHeaders(targetUrl, headers)
            continuation.resume(response)
            return@suspendCancellableCoroutine
        }

        val outputStream = ByteArrayOutputStream()
        val callback = object : UrlRequest.Callback() {
            override fun onRedirectReceived(request: UrlRequest, info: UrlResponseInfo, newLocationUrl: String) {
                request.followRedirect()
            }

            override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
                request.read(ByteBuffer.allocateDirect(32768))
            }

            override fun onReadCompleted(request: UrlRequest, info: UrlResponseInfo, byteBuffer: ByteBuffer) {
                byteBuffer.flip()
                val bytes = ByteArray(byteBuffer.remaining())
                byteBuffer.get(bytes)
                outputStream.write(bytes)
                byteBuffer.clear()
                request.read(byteBuffer)
            }

            override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) {
                val body = outputStream.toString("UTF-8")
                Log.i("CronetClient", "Cronet request succeeded with HTTP ${info.httpStatusCode}, protocol=${info.negotiatedProtocol}")
                continuation.resume(ProductFetchResponse(info.httpStatusCode, body))
            }

            override fun onFailed(request: UrlRequest, info: UrlResponseInfo?, error: CronetException) {
                val body = outputStream.toString("UTF-8")
                Log.e("CronetClient", "Cronet request failed HTTP ${info?.httpStatusCode}: ${error.message}")
                continuation.resume(ProductFetchResponse(info?.httpStatusCode ?: 500, body))
            }
        }

        val requestBuilder = engine.newUrlRequestBuilder(targetUrl, callback, java.util.concurrent.Executors.newSingleThreadExecutor())
        headers.forEach { (k, v) ->
            requestBuilder.addHeader(k, v)
        }

        val request = requestBuilder.build()
        continuation.invokeOnCancellation { request.cancel() }
        request.start()
    }

    private fun executeStandardRequestWithHeaders(targetUrl: String, headers: Map<String, String>): ProductFetchResponse {
        return try {
            val url = URL(targetUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            ProductFetchResponse(code, text)
        } catch (e: Exception) {
            ProductFetchResponse(500, e.message.orEmpty())
        }
    }

    suspend fun executeCronetRequest(
        targetUrl: String,
        pincode: String = "560048",
        latitude: Double? = 12.9716,
        longitude: Double? = 77.5946,
    ): ProductFetchResponse {
        val headers = mutableMapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Mobile Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
            "Accept-Language" to "en-IN,en-US;q=0.9,en;q=0.8",
            "Cache-Control" to "no-cache",
            "Pragma" to "no-cache",
            "DNT" to "1",
            "Upgrade-Insecure-Requests" to "1",
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "none",
            "Sec-Fetch-User" to "?1"
        )
        return executeCronetWithHeaders(targetUrl, headers)
    }
}
