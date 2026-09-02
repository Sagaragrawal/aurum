package com.aurum.intelligence.bridge

import com.aurum.intelligence.data.BridgeRepository
import java.io.BufferedInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class LoopbackBridgeServer(
    private val repository: BridgeRepository,
    private val scope: CoroutineScope,
) : AutoCloseable {
    private var socket: ServerSocket? = null
    private var job: Job? = null

    fun start(): Result<Unit> = runCatching {
        if (job != null) return@runCatching
        val server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 8788), 8)
        }
        socket = server
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                launch { client.use(::handle) }
            }
        }
    }.onFailure { close() }

    private fun handle(client: Socket) {
        client.soTimeout = 15_000
        val input = BufferedInputStream(client.getInputStream())
        val output = client.getOutputStream()
        val requestLine = readLine(input)?.split(' ') ?: return
        val method = requestLine.getOrNull(0).orEmpty()
        val path = requestLine.getOrNull(1).orEmpty()
        val headers = buildMap {
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val split = line.indexOf(':')
                if (split > 0) put(line.substring(0, split).lowercase(), line.substring(split + 1).trim())
            }
        }
        val origin = headers["origin"]?.takeIf(::allowedOrigin)

        when {
            method == "OPTIONS" && path == "/api/browser-bridge/products" && origin == null ->
                respond(output, 403, "{\"error\":\"origin not allowed\"}", null)
            method == "OPTIONS" && path == "/api/browser-bridge/products" -> respond(output, 204, "", origin)
            method == "GET" && path == "/api/health" -> respond(output, 200, "{\"ok\":true}", origin)
            method == "POST" && path == "/api/browser-bridge/products" && origin == null ->
                respond(output, 403, "{\"error\":\"origin not allowed\"}", null)
            method == "POST" && path == "/api/browser-bridge/products" -> {
                val length = headers["content-length"]?.toIntOrNull() ?: 0
                if (length !in 1..MAX_BODY_BYTES) {
                    respond(output, 413, "{\"error\":\"invalid body size\"}", origin)
                    return
                }
                val body = readExact(input, length).toString(Charsets.UTF_8)
                val sessionId = headers["x-aurum-refresh-session"].orEmpty()
                val isAjio = body.contains("\"store\":\"ajio.com\"")
                if (isAjio) android.util.Log.i("AurumAjio", "AJIO_BRIDGE_HTTP_RECEIVED session=${sessionId.take(8)} bytes=$length")
                val result = runCatching { runBlocking { repository.merge(body, sessionId) } }
                result.onSuccess { merged ->
                    if (isAjio) android.util.Log.i("AurumAjio", "AJIO_BRIDGE_SESSION_ACCEPTED session=${sessionId.take(8)}")
                    if (isAjio) android.util.Log.i("AurumAjio", "AJIO_BRIDGE_PARSE accepted=${merged.accepted} rejected=${merged.skipped}")
                    if (isAjio) android.util.Log.i("AurumAjio", "AJIO_BRIDGE_EVENT_EMITTED")
                    respond(
                        output,
                        200,
                        "{\"received\":${merged.received},\"accepted\":${merged.accepted},\"updated\":${merged.updated},\"discovered\":${merged.discovered},\"skipped\":${merged.skipped}}",
                        origin,
                    )
                }.onFailure { error ->
                    if (isAjio) android.util.Log.w("AurumAjio", "AJIO_BRIDGE_SESSION_REJECTED session=${sessionId.take(8)} reason=${error.message}")
                    respond(output, 400, "{\"error\":${jsonString(error.message ?: "invalid payload")}}", origin)
                }
            }
            else -> respond(output, 404, "{\"error\":\"not found\"}", origin)
        }
    }

    private fun respond(output: java.io.OutputStream, status: Int, body: String, origin: String?) {
        val reason = when (status) {
            200 -> "OK"
            204 -> "No Content"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            413 -> "Payload Too Large"
            else -> "Error"
        }
        val bytes = body.toByteArray()
        try {
            output.write(
                buildString {
                    append("HTTP/1.1 $status $reason\r\n")
                    append("Content-Type: application/json; charset=utf-8\r\n")
                    append("Content-Length: ${bytes.size}\r\n")
                    if (origin != null) append("Access-Control-Allow-Origin: $origin\r\n")
                    append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
                    append("Access-Control-Allow-Headers: content-type, x-aurum-refresh-session\r\n")
                    append("Connection: close\r\n\r\n")
                }.toByteArray(),
            )
            output.write(bytes)
            output.flush()
        } catch (_: IOException) {
            // The browser closed the loopback request before reading its response.
        }
    }

    private fun allowedOrigin(value: String): Boolean = runCatching {
        val host = java.net.URI(value).host ?: return@runCatching false
        ALLOWED_RETAILER_HOSTS.any { host == it || host.endsWith(".$it") }
    }.getOrDefault(false)

    private fun readLine(input: BufferedInputStream): String? {
        val bytes = ArrayList<Byte>()
        while (bytes.size < MAX_HEADER_LINE_BYTES) {
            val next = input.read()
            if (next == -1) return if (bytes.isEmpty()) null else bytes.toByteArray().toString(Charsets.UTF_8)
            if (next == '\n'.code) break
            if (next != '\r'.code) bytes.add(next.toByte())
        }
        return bytes.toByteArray().toString(Charsets.UTF_8)
    }

    private fun readExact(input: BufferedInputStream, length: Int): ByteArray {
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(body, offset, length - offset)
            require(count >= 0) { "Bridge request body ended early" }
            offset += count
        }
        return body
    }

    override fun close() {
        job?.cancel()
        job = null
        socket?.close()
        socket = null
    }

    private companion object {
        const val MAX_BODY_BYTES = 16 * 1024 * 1024
        const val MAX_HEADER_LINE_BYTES = 16 * 1024
        val ALLOWED_RETAILER_HOSTS = setOf("ajio.com", "amazon.in", "flipkart.com", "myntra.com")
        fun jsonString(value: String): String = buildString {
            append('"')
            value.forEach { character ->
                when (character) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    else -> append(character)
                }
            }
            append('"')
        }
    }
}