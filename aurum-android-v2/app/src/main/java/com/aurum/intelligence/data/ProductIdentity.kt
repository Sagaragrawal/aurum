package com.aurum.intelligence.data

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class ProductAddress(val store: String, val retailerId: String, val canonicalUrl: String)

object ProductIdentity {
    fun derive(value: String): ProductAddress {
        val uri = URI(value.trim())
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) {
            "Product URL must use HTTPS"
        }
        val host = uri.host.lowercase().removePrefix("www.")
        val store = when {
            host == "ajio.com" -> "ajio.com"
            host == "amazon.in" || host.endsWith(".amazon.in") -> "amazon.in"
            host == "flipkart.com" -> "flipkart.com"
            host == "myntra.com" -> "myntra.com"
            host == "shopsy.in" || host.endsWith(".shopsy.in") -> "shopsy.in"
            else -> throw IllegalArgumentException("Supported stores are AJIO, Amazon India, Flipkart, Myntra, and Shopsy")
        }
        val canonicalUrl = canonicalUrl(uri.toASCIIString())
        val segments = uri.path.orEmpty().split('/').filter(String::isNotBlank)
        val retailerId = when (store) {
            "ajio.com" -> segments.valueAfter("p")
            "amazon.in" -> segments.valueAfter("dp") ?: segments.valueAfter("product")
            "flipkart.com", "shopsy.in" -> parseQuery(uri.rawQuery)["pid"]
            "myntra.com" -> segments.lastOrNull { segment -> segment.all(Char::isDigit) }
            else -> null
        }?.takeIf(String::isNotBlank) ?: "url-${canonicalUrl.sha256().take(24)}"
        return ProductAddress(store, retailerId, canonicalUrl)
    }

    fun canonicalUrl(value: String): String {
        val uri = URI(value)
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) {
            "Product URL must use HTTPS"
        }
        val host = uri.host.lowercase().removePrefix("www.")
        val publicHost = "www.$host"
        if (host != "flipkart.com" && host != "shopsy.in") {
            return URI("https", publicHost, uri.path.ifBlank { "/" }, null, null).toASCIIString()
        }

        val query = parseQuery(uri.rawQuery)
        val productId = query["pid"] ?: return URI("https", publicHost, uri.path, null, null).toASCIIString()
        val marketplace = query["marketplace"]?.uppercase()
        val stable = buildList {
            add("pid" to productId)
            if (marketplace == "HYPERLOCAL") {
                add("marketplace" to "HYPERLOCAL")
                query["shopId"]?.let { add("shopId" to it) }
            } else {
                add("marketplace" to "FLIPKART")
            }
        }.joinToString("&") { (key, item) -> "${encode(key)}=${encode(item)}" }
        return URI("https", publicHost, uri.path.ifBlank { "/" }, stable, null).toASCIIString()
    }

    private fun parseQuery(query: String?): Map<String, String> = query.orEmpty()
        .split('&')
        .mapNotNull { part ->
            if (part.isBlank()) return@mapNotNull null
            val pieces = part.split('=', limit = 2)
            decode(pieces[0]) to decode(pieces.getOrElse(1) { "" })
        }
        .toMap()

    private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun List<String>.valueAfter(marker: String): String? {
        val index = indexOfFirst { it.equals(marker, ignoreCase = true) }
        return if (index >= 0) getOrNull(index + 1) else null
    }

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}