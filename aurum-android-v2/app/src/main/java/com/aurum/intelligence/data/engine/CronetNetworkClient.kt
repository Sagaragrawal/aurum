package com.aurum.intelligence.data.engine

import android.content.Context
import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import org.chromium.net.CronetEngine
import org.chromium.net.CronetException
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo

object CronetNetworkClient {

    private var cronetEngine: CronetEngine? = null
    private var appContext: Context? = null
    var initError: String? = null
        private set

    private val cookieManager = CookieManager().apply {
        setCookiePolicy(CookiePolicy.ACCEPT_ALL)
    }

    private val META_REFRESH_REGEX = Regex(
        """<meta\s+http-equiv=["']?refresh["']?\s+content=["']?\d+;\s*URL=['"]?([^'"]+)['"]?""",
        RegexOption.IGNORE_CASE
    )

    fun initialize(context: Context) {
        appContext = context.applicationContext
        if (cronetEngine == null) {
            try {
                com.google.android.gms.net.CronetProviderInstaller.installProvider(context)
            } catch (e: Exception) {
                Log.w("CronetClient", "CronetProviderInstaller error: ${e.message}")
            }
            try {
                val netConfig = ScraperConfigProvider.get().network
                cronetEngine = CronetEngine.Builder(context)
                    .enableHttp2(true)
                    .enableQuic(true)
                    .setUserAgent(netConfig.defaultUserAgent)
                    .build()
                Log.i("CronetClient", "CronetEngine successfully created: ${cronetEngine?.versionString}")
            } catch (e: Exception) {
                initError = e.message
                Log.e("CronetClient", "Failed to create CronetEngine: ${e.message}", e)
            }
        }
    }

    fun resetSession() {
        synchronized(this) {
            runCatching {
                cookieManager.cookieStore.removeAll()
                (java.net.CookieHandler.getDefault() as? CookieManager)?.cookieStore?.removeAll()
            }
            Log.i("CronetClient", "Cronet session cookies successfully reset.")
        }
    }

    private val cronetExecutor by lazy {
        java.util.concurrent.Executors.newFixedThreadPool(8)
    }

    suspend fun executeCronetWithHeaders(
        targetUrl: String,
        headers: Map<String, String>,
        onDataChunk: ((chunkText: String, isFinal: Boolean) -> Unit)? = null,
    ): ProductFetchResponse = suspendCancellableCoroutine { continuation ->
        val startTime = System.currentTimeMillis()
        val engine = cronetEngine

        val uri = runCatching { URI.create(targetUrl) }.getOrNull()
        val cookieHeaders = if (uri != null) {
            runCatching { cookieManager.get(uri, emptyMap()) }.getOrDefault(emptyMap())
        } else emptyMap()

        val mergedHeaders = headers.toMutableMap()
        if (!mergedHeaders.containsKey("Cookie") && !mergedHeaders.containsKey("cookie")) {
            val existingCookies = cookieHeaders["Cookie"] ?: cookieHeaders["cookie"]
            if (!existingCookies.isNullOrEmpty()) {
                mergedHeaders["Cookie"] = existingCookies.joinToString("; ")
            }
        }

        if (engine == null) {
            Log.w("CronetClient", "Cronet engine is NULL! Falling back to standard HttpURLConnection. Init error: $initError")
            val response = executeStandardRequestWithHeaders(targetUrl, mergedHeaders)
            onDataChunk?.invoke(response.body, true)
            continuation.resume(response)
            return@suspendCancellableCoroutine
        }

        val outputStream = ByteArrayOutputStream()
        val callback = object : UrlRequest.Callback() {
            override fun onRedirectReceived(request: UrlRequest, info: UrlResponseInfo, newLocationUrl: String) {
                request.followRedirect()
            }

            override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
                val bufferSize = ScraperConfigProvider.get().network.bufferSize
                request.read(ByteBuffer.allocateDirect(bufferSize))
            }

            override fun onReadCompleted(request: UrlRequest, info: UrlResponseInfo, byteBuffer: ByteBuffer) {
                byteBuffer.flip()
                val bytes = ByteArray(byteBuffer.remaining())
                byteBuffer.get(bytes)
                outputStream.write(bytes)
                if (onDataChunk != null) {
                    val chunkText = String(bytes, Charsets.UTF_8)
                    runCatching { onDataChunk.invoke(chunkText, false) }
                }
                byteBuffer.clear()
                request.read(byteBuffer)
            }

            override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) {
                val body = outputStream.toString("UTF-8")
                if (onDataChunk != null) {
                    runCatching { onDataChunk.invoke("", true) }
                }
                val durationMs = System.currentTimeMillis() - startTime
                val respHeaders = info.allHeaders ?: emptyMap()
                val protocol = info.negotiatedProtocol ?: ""

                if (uri != null && info.allHeaders != null) {
                    val setCookieList = info.allHeaders["set-cookie"] ?: info.allHeaders["Set-Cookie"] ?: emptyList()
                    if (setCookieList.isNotEmpty()) {
                        val map = mapOf("Set-Cookie" to setCookieList)
                        runCatching { cookieManager.put(uri, map) }
                    }
                }

                // Detect meta-refresh challenge tag (Amazon bm-verify, etc.)
                val metaMatch = META_REFRESH_REGEX.find(body)
                if (metaMatch != null) {
                    val rawChallengeUrl = metaMatch.groupValues[1].trim()
                    val resolvedChallengeUrl = try {
                        URI.create(targetUrl).resolve(rawChallengeUrl).toString()
                    } catch (e: Exception) {
                        if (rawChallengeUrl.startsWith("/")) {
                            val baseHost = targetUrl.substringBefore('/', targetUrl)
                            "$baseHost$rawChallengeUrl"
                        } else rawChallengeUrl
                    }
                    Log.i("CronetClient", "Detected meta-refresh challenge tag ($rawChallengeUrl). Auto-following challenge URL: $resolvedChallengeUrl")

                    // Execute challenge URL directly
                    val challengeResponse = executeStandardRequestWithHeaders(resolvedChallengeUrl, mergedHeaders)
                    continuation.resume(challengeResponse)
                    return
                }

                Log.i("CronetClient", "Cronet request succeeded HTTP ${info.httpStatusCode}, protocol=$protocol, duration=${durationMs}ms, bytes=${body.length}")
                continuation.resume(
                    ProductFetchResponse(
                        status = info.httpStatusCode,
                        body = body,
                        headers = respHeaders,
                        protocol = protocol,
                        durationMs = durationMs
                    )
                )
            }

            override fun onFailed(request: UrlRequest, info: UrlResponseInfo?, error: CronetException) {
                Log.w("CronetClient", "Cronet request failed (${error.message}). Attempting fallback to standard HttpURLConnection for $targetUrl...")
                try {
                    val fallback = executeStandardRequestWithHeaders(targetUrl, mergedHeaders)
                    onDataChunk?.invoke(fallback.body, true)
                    continuation.resume(fallback)
                } catch (e: Exception) {
                    val body = outputStream.toString("UTF-8")
                    if (onDataChunk != null) {
                        runCatching { onDataChunk.invoke("", true) }
                    }
                    val durationMs = System.currentTimeMillis() - startTime
                    val respHeaders = info?.allHeaders ?: emptyMap()
                    val protocol = info?.negotiatedProtocol ?: ""
                    Log.e("CronetClient", "Fallback also failed HTTP ${info?.httpStatusCode ?: 500}: ${e.message}, duration=${durationMs}ms")
                    continuation.resume(
                        ProductFetchResponse(
                            status = info?.httpStatusCode ?: 500,
                            body = body,
                            headers = respHeaders,
                            protocol = protocol,
                            durationMs = durationMs
                        )
                    )
                }
            }
        }

        val requestBuilder = engine.newUrlRequestBuilder(targetUrl, callback, cronetExecutor)
        mergedHeaders.forEach { (k, v) ->
            requestBuilder.addHeader(k, v)
        }

        val request = requestBuilder.build()
        continuation.invokeOnCancellation { request.cancel() }
        request.start()
    }

    private fun executeStandardRequestWithHeaders(targetUrl: String, headers: Map<String, String>): ProductFetchResponse {
        val startTime = System.currentTimeMillis()
        val netConfig = ScraperConfigProvider.get().network
        val uri = runCatching { URI.create(targetUrl) }.getOrNull()

        return try {
            val url = URL(targetUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = netConfig.cronetFallbackTimeoutMs
                readTimeout = netConfig.cronetFallbackTimeoutMs
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val durationMs = System.currentTimeMillis() - startTime
            val respHeaders = conn.headerFields.filterKeys { it != null }

            if (uri != null) {
                val setCookieList = respHeaders["Set-Cookie"] ?: respHeaders["set-cookie"] ?: emptyList()
                if (setCookieList.isNotEmpty()) {
                    runCatching { cookieManager.put(uri, mapOf("Set-Cookie" to setCookieList)) }
                }
            }

            // Check meta-refresh on fallback too
            val metaMatch = META_REFRESH_REGEX.find(text)
            if (metaMatch != null) {
                val rawChallengeUrl = metaMatch.groupValues[1].trim()
                val resolvedChallengeUrl = try {
                    URI.create(targetUrl).resolve(rawChallengeUrl).toString()
                } catch (e: Exception) {
                    if (rawChallengeUrl.startsWith("/")) {
                        val baseHost = targetUrl.substringBefore('/', targetUrl)
                        "$baseHost$rawChallengeUrl"
                    } else rawChallengeUrl
                }
                Log.i("CronetClient", "Standard fallback detected meta-refresh challenge tag ($rawChallengeUrl). Following: $resolvedChallengeUrl")
                return executeStandardRequestWithHeaders(resolvedChallengeUrl, headers)
            }

            ProductFetchResponse(
                status = code,
                body = text,
                headers = respHeaders,
                protocol = "http/1.1",
                durationMs = durationMs
            )
        } catch (e: Exception) {
            val durationMs = System.currentTimeMillis() - startTime
            ProductFetchResponse(
                status = 500,
                body = e.message.orEmpty(),
                headers = emptyMap(),
                protocol = "unknown",
                durationMs = durationMs
            )
        }
    }

    suspend fun executeCronetRequest(
        targetUrl: String,
        pincode: String = ScraperConfigProvider.get().location.defaultPincode,
        latitude: Double? = ScraperConfigProvider.get().location.defaultLatitude,
        longitude: Double? = ScraperConfigProvider.get().location.defaultLongitude,
        onDataChunk: ((chunkText: String, isFinal: Boolean) -> Unit)? = null,
    ): ProductFetchResponse {
        val headers = ScraperConfigProvider.get().network.cronetRequestHeaders.toMutableMap()
        return executeCronetWithHeaders(targetUrl, headers, onDataChunk)
    }

    suspend fun executeCronetApiRequest(
        targetUrl: String,
        pincode: String = ScraperConfigProvider.get().location.defaultPincode,
        latitude: Double? = ScraperConfigProvider.get().location.defaultLatitude,
        longitude: Double? = ScraperConfigProvider.get().location.defaultLongitude,
        onDataChunk: ((chunkText: String, isFinal: Boolean) -> Unit)? = null,
    ): ProductFetchResponse {
        val headers = ScraperConfigProvider.get().network.cronetApiRequestHeaders.toMutableMap()
        return executeCronetWithHeaders(targetUrl, headers, onDataChunk)
    }
}
