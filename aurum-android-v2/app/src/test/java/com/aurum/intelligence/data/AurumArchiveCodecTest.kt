package com.aurum.intelligence.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AurumArchiveCodecTest {
    @Test
    fun roundTripsAllArchiveDataWithStableEntryNames() {
        val archive = AurumArchive(
            manifest = ArchiveManifest(exportedAt = 1_750_000_000_000),
            products = listOf(
                ArchiveProduct(
                    id = "local-1",
                    store = "amazon.in",
                    retailerId = "B012345678",
                    canonicalUrl = "https://www.amazon.in/dp/B012345678",
                    name = "One Gram Gold",
                    brand = "Aurum",
                    grams = 1.0,
                    karat = 24.0,
                    purity = "999",
                    price = 9_500.0,
                    couponPrice = 9_300.0,
                    status = "live",
                    refreshMethod = "amazon.in-browser-bridge",
                    checkedAt = 1_750_000_000_000,
                    lastLiveAt = 1_750_000_000_000,
                    manuallyEditedAt = 1_749_000_000_000,
                ),
            ),
            history = listOf(
                ArchiveProductHistory("local-1", 9_500.0, 9_300.0, 1_750_000_000_000),
            ),
            rawBridgePayloads = listOf(
                ArchiveRawBridgePayload("raw-1", "amazon.in", 1_750_000_000_000, "{\"store\":\"amazon.in\"}"),
            ),
            bullionSources = listOf(
                ArchiveBullionSource("mmtc", "MMTC-PAMP", "MMTC-PAMP", "https://www.mmtcpamp.com/gold-silver-rate-today", 15_954.61, 14_625.06, false, "live", "direct_http", 1_750_000_000_000, 1_750_000_000_000, 1_750_000_000_000, null),
            ),
            bullionHistory = listOf(
                ArchiveBullionHistory("mmtc", 15_954.61, 14_625.06, false, 1_750_000_000_000),
            ),
        )
        val output = ByteArrayOutputStream()

        AurumArchiveCodec.encode(archive, output)

        val entryNames = buildList {
            ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
                while (true) add(zip.nextEntry?.name ?: break)
            }
        }
        assertEquals(
            listOf("manifest.json", "products.json", "product-history.json", "raw-bridge-payloads.json", "bullion-sources.json", "bullion-history.json"),
            entryNames,
        )
        assertEquals(
            archive.copy(manifest = archive.manifest.copy(productCount = 1, historyCount = 1, rawBridgePayloadCount = 1, bullionSourceCount = 1, bullionHistoryCount = 1)),
            AurumArchiveCodec.decode(ByteArrayInputStream(output.toByteArray())),
        )
    }

    @Test
    fun manifestCountsAreWrittenFromPayload() {
        val archive = AurumArchive(
            manifest = ArchiveManifest(exportedAt = 123),
            products = listOf(sampleProduct()),
            history = emptyList(),
            rawBridgePayloads = emptyList(),
        )
        val output = ByteArrayOutputStream()

        AurumArchiveCodec.encode(archive, output)
        val decoded = AurumArchiveCodec.decode(ByteArrayInputStream(output.toByteArray()))

        assertEquals(1, decoded.manifest.productCount)
        assertEquals(0, decoded.manifest.historyCount)
        assertEquals(0, decoded.manifest.rawBridgePayloadCount)
    }

    @Test
    fun rejectsUnsupportedArchiveVersion() {
        val output = ByteArrayOutputStream()
        AurumArchiveCodec.encode(
            AurumArchive(ArchiveManifest(version = 3, exportedAt = 123), emptyList(), emptyList(), emptyList()),
            output,
        )

        val failure = runCatching {
            AurumArchiveCodec.decode(ByteArrayInputStream(output.toByteArray()))
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("version"))
    }

    @Test
    fun rejectsImplausibleBullionHistory() {
        val archive = AurumArchive(
            manifest = ArchiveManifest(exportedAt = 123),
            products = emptyList(),
            history = emptyList(),
            rawBridgePayloads = emptyList(),
            bullionHistory = listOf(ArchiveBullionHistory("tan", 1_443_000.0, 14_000.0, false, 123)),
        )
        val output = ByteArrayOutputStream()
        AurumArchiveCodec.encode(archive, output)

        val failure = runCatching {
            AurumArchiveCodec.decode(ByteArrayInputStream(output.toByteArray()))
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("24K rate"))
    }

    @Test
    fun rejectsInvalidProductHistory() {
        val archive = AurumArchive(
            manifest = ArchiveManifest(exportedAt = 123),
            products = emptyList(),
            history = listOf(ArchiveProductHistory("product-1", 10_000.0, 12_000.0, 123)),
            rawBridgePayloads = emptyList(),
        )
        val output = ByteArrayOutputStream()
        AurumArchiveCodec.encode(archive, output)

        val failure = runCatching {
            AurumArchiveCodec.decode(ByteArrayInputStream(output.toByteArray()))
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("history coupon"))
    }

    @Test
    fun rejectsProductUrlOutsideClaimedStore() {
        val archive = AurumArchive(
            manifest = ArchiveManifest(exportedAt = 123),
            products = listOf(sampleProduct().copy(canonicalUrl = "https://untrusted.example/product")),
            history = emptyList(),
            rawBridgePayloads = emptyList(),
        )
        val output = ByteArrayOutputStream()
        AurumArchiveCodec.encode(archive, output)

        val failure = runCatching {
            AurumArchiveCodec.decode(ByteArrayInputStream(output.toByteArray()))
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    private fun sampleProduct() = ArchiveProduct(
        id = "product-1",
        store = "ajio.com",
        retailerId = "6000000000",
        canonicalUrl = "https://www.ajio.com/p/6000000000",
        name = "Gold Coin",
        brand = null,
        grams = 1.0,
        karat = 24.0,
        purity = "999",
        price = 8_000.0,
        couponPrice = null,
        status = "live",
        refreshMethod = "import",
        checkedAt = 123,
        lastLiveAt = 123,
        manuallyEditedAt = null,
    )
}