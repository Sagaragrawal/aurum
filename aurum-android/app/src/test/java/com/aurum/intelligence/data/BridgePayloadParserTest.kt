package com.aurum.intelligence.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgePayloadParserTest {
    @Test
    fun parsesAmazonSnapshot() {
        val payload = BridgePayloadParser.parse(
            """{"store":"amazon.in","records":[{"asin":"B012345678","url":"https://www.amazon.in/dp/B012345678","name":"One Gram 24K Gold","brand":"Aurum","price":10000,"metal":"gold","grams":1,"karat":24,"purity":999}]}""",
        )

        assertEquals("amazon.in", payload.store)
        assertEquals("B012345678", payload.records.single().retailerId)
        assertEquals(10000.0, requireNotNull(payload.records.single().price), 0.0)
    }

    @Test
    fun parsesMyntraAliases() {
        val payload = BridgePayloadParser.parse(
            """{"store":"myntra.com","records":[{"productId":"123","landingPageUrl":"https://www.myntra.com/gold/123","productName":"Gold Coin","price":5000,"metal":"gold"}]}""",
        )

        assertEquals("123", payload.records.single().retailerId)
        assertEquals("Gold Coin", payload.records.single().name)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownStore() {
        BridgePayloadParser.parse("""{"store":"unknown.example","records":[]}""")
    }

    @Test
    fun candidateRejectsNonGoldAndMissingPrice() {
        val nonGold = BridgeRecord(retailerId = "1", url = "https://www.ajio.com/p/1", price = 10.0, metal = "silver")
        val noPrice = BridgeRecord(retailerId = "2", url = "https://www.ajio.com/p/2", metal = "gold")

        assertEquals("non_gold", (nonGold.toProductCandidate("ajio.com") as CandidateParseResult.Rejected).reason)
        assertEquals("invalid_price", (noPrice.toProductCandidate("ajio.com") as CandidateParseResult.Rejected).reason)
    }

    @Test
    fun candidateRejectsInvalidCouponAndKaratValues() {
        val result = BridgeRecord(
            retailerId = "1",
            url = "https://www.ajio.com/p/1",
            price = 10_000.0,
            couponPrice = 12_000.0,
            grams = 1.0,
            karat = 999.0,
            metal = "gold",
        ).toProductCandidate("ajio.com")

        assertTrue(result is CandidateParseResult.Rejected)
        assertEquals("invalid_coupon", (result as CandidateParseResult.Rejected).reason)
    }

    @Test
    fun candidateRejectsUrlOutsideClaimedRetailer() {
        val result = BridgeRecord(
            retailerId = "B012345678",
            url = "https://untrusted.example/dp/B012345678",
            price = 10_000.0,
            metal = "gold",
        ).toProductCandidate("amazon.in")

        assertEquals("invalid_retailer_url", (result as CandidateParseResult.Rejected).reason)
    }

    @Test
    fun candidateRetainsAbsentOptionalMetadataAsAbsent() {
        val result = BridgeRecord(
            retailerId = "1",
            url = "https://www.ajio.com/p/1",
            price = 10_000.0,
            metal = "gold",
        ).toProductCandidate("ajio.com")

        val candidate = (result as CandidateParseResult.Valid).candidate
        assertNull(candidate.grams)
        assertNull(candidate.karat)
        assertNull(candidate.couponPrice)
    }

    @Test
    fun candidateSeparatesNotDeliverableAvailabilityFromDisplayName() {
        val result = BridgeRecord(
            retailerId = "1",
            url = "https://www.ajio.com/p/1",
            name = "Mia Lotus Coin - Not Deliverable",
            price = 10_000.0,
            grams = 1.0,
            karat = 24.0,
            metal = "gold",
        ).toProductCandidate("ajio.com")

        val candidate = (result as CandidateParseResult.Valid).candidate
        assertTrue(candidate.unavailable)
        assertEquals("Mia Lotus Coin", candidate.name)
    }

    @Test
    fun notDeliverableMatchingIsCaseInsensitiveAndRobustToExtraSpacing() {
        val result = BridgeRecord(
            retailerId = "1",
            url = "https://www.ajio.com/p/1",
            name = "Mia Lotus   COIN   -   NOT    deliverable",
            price = 10_000.0,
            grams = 1.0,
            karat = 24.0,
            metal = "gold",
        ).toProductCandidate("ajio.com")

        val candidate = (result as CandidateParseResult.Valid).candidate
        assertTrue(candidate.unavailable)
        assertTrue(candidate.name?.contains("Mia Lotus") == true)
        assertTrue(candidate.name?.lowercase()?.contains("deliverable") == false)
    }

    @Test
    fun unrelatedDeliveryWordingIsNotMisclassifiedAsUnavailable() {
        val result = BridgeRecord(
            retailerId = "1",
            url = "https://www.ajio.com/p/1",
            name = "Mia Lotus Coin with Free Home Delivery",
            price = 10_000.0,
            grams = 1.0,
            karat = 24.0,
            metal = "gold",
        ).toProductCandidate("ajio.com")

        val candidate = (result as CandidateParseResult.Valid).candidate
        assertTrue(!candidate.unavailable)
        assertEquals("Mia Lotus Coin with Free Home Delivery", candidate.name)
    }
}