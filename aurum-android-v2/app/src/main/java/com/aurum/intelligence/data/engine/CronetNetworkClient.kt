package com.aurum.intelligence.data.engine
import com.aurum.intelligence.data.db.*
import com.aurum.intelligence.data.engine.*
import com.aurum.intelligence.data.model.*
import com.aurum.intelligence.data.repository.*
import com.aurum.intelligence.data.validation.*

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
    private var appContext: Context? = null
    var initError: String? = null
        private set

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
        val ctx = appContext ?: return
        synchronized(this) {
            runCatching {
                cronetEngine?.shutdown()
            }
            cronetEngine = null
            initialize(ctx)
            Log.i("CronetClient", "Cronet session successfully reset/reinitialized.")
        }
    }

    suspend fun executeCronetWithHeaders(
        targetUrl: String,
        headers: Map<String, String>,
        onDataChunk: ((chunkText: String, isFinal: Boolean) -> Unit)? = null,
    ): ProductFetchResponse = suspendCancellableCoroutine { continuation ->
        val startTime = System.currentTimeMillis()
        val engine = cronetEngine
        if (engine == null) {
            Log.w("CronetClient", "Cronet engine is NULL! Falling back to standard HttpURLConnection. Init error: $initError")
            val response = executeStandardRequestWithHeaders(targetUrl, headers)
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
                    val fallback = executeStandardRequestWithHeaders(targetUrl, headers)
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

        val requestBuilder = engine.newUrlRequestBuilder(targetUrl, callback, java.util.concurrent.Executors.newSingleThreadExecutor())
        headers.forEach { (k, v) ->
            requestBuilder.addHeader(k, v)
        }

        val request = requestBuilder.build()
        continuation.invokeOnCancellation { request.cancel() }
        request.start()
    }

    private fun executeStandardRequestWithHeaders(targetUrl: String, headers: Map<String, String>): ProductFetchResponse {
        val startTime = System.currentTimeMillis()
        val netConfig = ScraperConfigProvider.get().network
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
