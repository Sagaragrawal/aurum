package com.aurum.intelligence.data

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ArchiveManifest(
    val format: String = AurumArchiveCodec.FORMAT,
    val version: Int = AurumArchiveCodec.VERSION,
    val exportedAt: Long,
    val productCount: Int = 0,
    val historyCount: Int = 0,
    val rawBridgePayloadCount: Int = 0,
    val bullionSourceCount: Int = 0,
    val bullionHistoryCount: Int = 0,
)

@Serializable
data class ArchiveProduct(
    val id: String,
    val store: String,
    val retailerId: String,
    val canonicalUrl: String,
    val name: String,
    val brand: String?,
    val grams: Double?,
    val karat: Double?,
    val purity: String?,
    val price: Double,
    val couponPrice: Double?,
    val status: String,
    val refreshMethod: String,
    val checkedAt: Long,
    val lastLiveAt: Long,
    val manuallyEditedAt: Long?,
)

@Serializable
data class ArchiveProductHistory(
    val productId: String,
    val price: Double,
    val couponPrice: Double?,
    val checkedAt: Long,
)

@Serializable
data class ArchiveRawBridgePayload(
    val id: String,
    val store: String,
    val receivedAt: Long,
    val json: String,
)

@Serializable
data class ArchiveBullionSource(
    val id: String,
    val source: String,
    val label: String,
    val url: String,
    val price24: Double?,
    val price22: Double?,
    val price22Derived: Boolean,
    val status: String,
    val transport: String,
    val fetchedAt: Long?,
    val lastLiveAt: Long?,
    val lastAttemptAt: Long?,
    val error: String?,
)

@Serializable
data class ArchiveBullionHistory(
    val sourceId: String,
    val price24: Double,
    val price22: Double,
    val price22Derived: Boolean,
    val fetchedAt: Long,
)

data class AurumArchive(
    val manifest: ArchiveManifest,
    val products: List<ArchiveProduct>,
    val history: List<ArchiveProductHistory>,
    val rawBridgePayloads: List<ArchiveRawBridgePayload>,
    val bullionSources: List<ArchiveBullionSource> = emptyList(),
    val bullionHistory: List<ArchiveBullionHistory> = emptyList(),
)

object AurumArchiveCodec {
    const val FORMAT = "aurum-android-export"
    const val VERSION = 2
    private const val MAX_ENTRY_BYTES = 32 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 64 * 1024 * 1024
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = false
    }
    private val requiredEntries = setOf(
        "manifest.json",
        "products.json",
        "product-history.json",
        "raw-bridge-payloads.json",
        "bullion-sources.json",
        "bullion-history.json",
    )

    fun encode(archive: AurumArchive, output: OutputStream) {
        val manifest = archive.manifest.copy(
            productCount = archive.products.size,
            historyCount = archive.history.size,
            rawBridgePayloadCount = archive.rawBridgePayloads.size,
            bullionSourceCount = archive.bullionSources.size,
            bullionHistoryCount = archive.bullionHistory.size,
        )
        ZipOutputStream(output).use { zip ->
            zip.writeEntry("manifest.json", json.encodeToString(manifest))
            zip.writeEntry("products.json", json.encodeToString(archive.products))
            zip.writeEntry("product-history.json", json.encodeToString(archive.history))
            zip.writeEntry("raw-bridge-payloads.json", json.encodeToString(archive.rawBridgePayloads))
            zip.writeEntry("bullion-sources.json", json.encodeToString(archive.bullionSources))
            zip.writeEntry("bullion-history.json", json.encodeToString(archive.bullionHistory))
        }
    }

    fun decode(input: InputStream): AurumArchive {
        val entries = mutableMapOf<String, String>()
        var totalBytes = 0
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory && entry.name in requiredEntries) {
                    "Unexpected archive entry: ${entry.name}"
                }
                val content = zip.readBoundedEntry(MAX_TOTAL_BYTES - totalBytes)
                totalBytes += content.second
                require(entries.put(entry.name, content.first) == null) {
                    "Duplicate archive entry: ${entry.name}"
                }
                zip.closeEntry()
            }
        }
        val missing = requiredEntries - entries.keys
        require(missing.isEmpty()) { "Archive is missing: ${missing.sorted().joinToString()}" }

        val manifest = json.decodeFromString<ArchiveManifest>(entries.getValue("manifest.json"))
        require(manifest.format == FORMAT) { "Unsupported archive format: ${manifest.format}" }
        require(manifest.version == VERSION) { "Unsupported archive version: ${manifest.version}" }
        val products = json.decodeFromString<List<ArchiveProduct>>(entries.getValue("products.json"))
        val history = json.decodeFromString<List<ArchiveProductHistory>>(entries.getValue("product-history.json"))
        val rawPayloads = json.decodeFromString<List<ArchiveRawBridgePayload>>(entries.getValue("raw-bridge-payloads.json"))
        val bullionSources = json.decodeFromString<List<ArchiveBullionSource>>(entries.getValue("bullion-sources.json"))
        val bullionHistory = json.decodeFromString<List<ArchiveBullionHistory>>(entries.getValue("bullion-history.json"))
        products.forEach(::validateProduct)
        history.forEach(::validateProductHistory)
        bullionSources.forEach(::validateBullionSource)
        bullionHistory.forEach(::validateBullionHistory)
        require(manifest.productCount == products.size) { "Product count does not match manifest" }
        require(manifest.historyCount == history.size) { "History count does not match manifest" }
        require(manifest.rawBridgePayloadCount == rawPayloads.size) { "Raw payload count does not match manifest" }
        require(manifest.bullionSourceCount == bullionSources.size) { "Bullion source count does not match manifest" }
        require(manifest.bullionHistoryCount == bullionHistory.size) { "Bullion history count does not match manifest" }
        return AurumArchive(manifest, products, history, rawPayloads, bullionSources, bullionHistory)
    }

    private fun ZipOutputStream.writeEntry(name: String, value: String) {
        putNextEntry(ZipEntry(name))
        write(value.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun ZipInputStream.readBoundedEntry(remainingBudget: Int): Pair<String, Int> {
        require(remainingBudget > 0) { "Archive exceeds total size limit" }
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            require(total <= MAX_ENTRY_BYTES) { "Archive entry exceeds size limit" }
            require(total <= remainingBudget) { "Archive exceeds total size limit" }
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name()) to total
    }

    private fun validateProduct(product: ArchiveProduct) {
        require(product.store in setOf("ajio.com", "amazon.in", "flipkart.com", "myntra.com")) {
            "Unsupported product store: ${product.store}"
        }
        require(product.retailerId.isNotBlank()) { "Product retailer identity is missing" }
        require(RetailerUrlPolicy.isAllowedProductUrl(product.store, product.canonicalUrl)) {
            "Product URL must be an HTTPS URL owned by ${product.store}"
        }
        require(product.price > 0 && product.price.isFinite()) { "Product price is invalid" }
        require(product.couponPrice == null || (product.couponPrice.isFinite() && product.couponPrice > 0 && product.couponPrice < product.price)) {
            "Product coupon price is invalid"
        }
        require(product.grams == null || (product.grams.isFinite() && product.grams > 0)) { "Product weight is invalid" }
        require(product.karat == null || (product.karat.isFinite() && product.karat in 1.0..24.0)) { "Product karat is invalid" }
        require(product.status in setOf("live", "stale", "unverified", "unavailable", "failed")) {
            "Product status is invalid"
        }
        require(product.checkedAt >= 0 && product.lastLiveAt >= 0) { "Product timestamp is invalid" }
        require(product.manuallyEditedAt == null || product.manuallyEditedAt >= 0) { "Product edit timestamp is invalid" }
    }

    private fun validateBullionSource(source: ArchiveBullionSource) {
        require(source.id in BULLION_SOURCE_IDS) { "Unsupported bullion source: ${source.id}" }
        require(source.status in setOf("live", "stale", "checking", "unavailable")) { "Bullion source status is invalid" }
        require(source.transport in setOf("direct_http", "browser_required")) { "Bullion source transport is invalid" }
        require(source.url.startsWith("https://")) { "Bullion source URL must use HTTPS" }
        require(source.fetchedAt == null || source.fetchedAt >= 0) { "Bullion source timestamp is invalid" }
        require(source.lastLiveAt == null || source.lastLiveAt >= 0) { "Bullion source timestamp is invalid" }
        require(source.lastAttemptAt == null || source.lastAttemptAt >= 0) { "Bullion source timestamp is invalid" }
        if (source.price24 != null) require(BullionRatePolicy.isPlausible24(source.price24)) { "Bullion 24K rate is invalid" }
        if (source.price22 != null) require(BullionRatePolicy.isPlausible22(source.price22, source.price24)) { "Bullion 22K rate is invalid" }
    }

    private fun validateProductHistory(history: ArchiveProductHistory) {
        require(history.productId.isNotBlank()) { "Product history identity is missing" }
        require(history.price.isFinite() && history.price > 0) { "Product history price is invalid" }
        require(history.couponPrice == null || (history.couponPrice.isFinite() && history.couponPrice > 0 && history.couponPrice < history.price)) {
            "Product history coupon price is invalid"
        }
        require(history.checkedAt >= 0) { "Product history timestamp is invalid" }
    }

    private fun validateBullionHistory(history: ArchiveBullionHistory) {
        require(history.sourceId in BULLION_SOURCE_IDS) { "Unsupported bullion history source: ${history.sourceId}" }
        require(history.fetchedAt >= 0) { "Bullion history timestamp is invalid" }
        require(BullionRatePolicy.isPlausible24(history.price24)) { "Bullion history 24K rate is invalid" }
        require(BullionRatePolicy.isPlausible22(history.price22, history.price24)) { "Bullion history 22K rate is invalid" }
    }

    private val BULLION_SOURCE_IDS = setOf("tan", "malabar", "mmtc", "kalyan")
}