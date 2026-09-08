package com.aurum.intelligence.parsers

import org.junit.Assert.*
import org.junit.Test

class MyntraNativeParserTest {

    @Test
    fun testParsePlpWithBlinkDealCouponAndDiscount() {
        val json = """
        {
          "products": [
            {
              "productId": "23250690",
              "productName": "BHIMA Floral 24K 999 Purity Gold Bar 1 gram",
              "brand": "BHIMA",
              "mrp": 17491,
              "price": 16885,
              "discount": 606,
              "couponData": {
                "couponDiscount": 1014,
                "couponDescription": {
                  "couponCode": "BLINKDEAL6",
                  "bestPrice": 15871,
                  "bestPriceText": "Best Price"
                }
              },
              "landingPageUrl": "gold-coins/bhima/bhima-floral-24k-999-purity-gold-bar-1-gram/23250690/buy"
            }
          ]
        }
        """.trimIndent()

        val result = MyntraNativeParser.parse(json)
        assertEquals(1, result.candidates.size)

        val candidate = result.candidates[0]
        assertEquals("23250690", candidate.retailerId)
        assertEquals(16885.0, candidate.price, 0.01)
        assertEquals(15871.0, candidate.couponPrice!!, 0.01)
        assertTrue(candidate.isBlinkDeal)
        assertEquals(15871.0, candidate.blinkDealPrice!!, 0.01)
    }

    @Test
    fun testParsePlpWithBlinkDealNoMainDiscount() {
        val json = """
        {
          "products": [
            {
              "productId": "30970331",
              "productName": "Mia by Tanishq 24KT Gold Lotus Coin - 0.5 gm",
              "brand": "Mia by Tanishq",
              "mrp": 8371,
              "price": 8371,
              "discount": 0,
              "couponData": {
                "couponDiscount": 503,
                "couponDescription": {
                  "couponCode": "BLINKDEAL6",
                  "bestPrice": 7868,
                  "bestPriceText": "Best Price"
                }
              },
              "landingPageUrl": "gold-coins/mia-by-tanishq/mia-by-tanishq-24kt-gold-lotus-coin---05-gm/30970331/buy"
            }
          ]
        }
        """.trimIndent()

        val result = MyntraNativeParser.parse(json)
        assertEquals(1, result.candidates.size)

        val candidate = result.candidates[0]
        assertEquals("30970331", candidate.retailerId)
        assertEquals(8371.0, candidate.price, 0.01)
        assertEquals(7868.0, candidate.couponPrice!!, 0.01)
        assertTrue(candidate.isBlinkDeal)
        assertEquals(7868.0, candidate.blinkDealPrice!!, 0.01)
    }

    @Test
    fun testIgnoreMyntra300FirstUserCoupon() {
        val json = """
        {
          "products": [
            {
              "productId": "123456",
              "productName": "Sample Gold Coin 1g 24K 999 Purity",
              "brand": "SampleBrand",
              "mrp": 2299,
              "price": 1502,
              "discount": 797,
              "couponData": {
                "couponDiscount": 300,
                "couponDescription": {
                  "couponCode": "MYNTRA300",
                  "bestPrice": 1202,
                  "bestPriceText": "Best Price"
                }
              },
              "landingPageUrl": "gold-coins/samplebrand/sample-gold-coin-1g/123456/buy"
            }
          ]
        }
        """.trimIndent()

        val result = MyntraNativeParser.parse(json)
        assertEquals(1, result.candidates.size)

        val candidate = result.candidates[0]
        assertEquals("123456", candidate.retailerId)
        assertEquals(1502.0, candidate.price, 0.01)
        assertNull("MYNTRA300 first-user coupon must be ignored", candidate.couponPrice)
        assertFalse(candidate.isBlinkDeal)
        assertNull(candidate.blinkDealPrice)
    }

    @Test
    fun testParsePlpWithoutCoupon() {
        val json = """
        {
          "products": [
            {
              "productId": "43299370",
              "productName": "BHIMA 24K 995 Gold Vedhani 5g",
              "brand": "BHIMA",
              "mrp": 85445,
              "price": 81871,
              "discount": 3574,
              "couponData": null,
              "landingPageUrl": "gold-coins/bhima/bhima-24k-995-gold-vedhani-5g/43299370/buy"
            }
          ]
        }
        """.trimIndent()

        val result = MyntraNativeParser.parse(json)
        assertEquals(1, result.candidates.size)

        val candidate = result.candidates[0]
        assertEquals("43299370", candidate.retailerId)
        assertEquals(81871.0, candidate.price, 0.01)
        assertNull(candidate.couponPrice)
        assertFalse(candidate.isBlinkDeal)
        assertNull(candidate.blinkDealPrice)
    }

    @Test
    fun testParsePdpWithBlinkDeal() {
        val json = """
        {
          "pdpData": {
            "id": "23250690",
            "name": "BHIMA Floral 24K 999 Purity Gold Bar 1 gram",
            "brand": { "name": "BHIMA" },
            "price": {
              "mrp": 17491,
              "discounted": 16885
            },
            "couponData": {
              "couponDiscount": 1014,
              "couponDescription": {
                "couponCode": "BLINKDEAL6",
                "bestPrice": 15871
              }
            },
            "landingPageUrl": "gold-coins/bhima/bhima-floral-24k-999-purity-gold-bar-1-gram/23250690/buy"
          }
        }
        """.trimIndent()

        val result = MyntraNativeParser.parse(json)
        assertEquals(1, result.candidates.size)

        val candidate = result.candidates[0]
        assertEquals("23250690", candidate.retailerId)
        assertEquals(16885.0, candidate.price, 0.01)
        assertEquals(15871.0, candidate.couponPrice!!, 0.01)
        assertTrue(candidate.isBlinkDeal)
        assertEquals(15871.0, candidate.blinkDealPrice!!, 0.01)
    }
}
