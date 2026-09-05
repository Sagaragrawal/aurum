package com.aurum.intelligence.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MissingCatalogueProductVerifierTest {
    @Test
    fun parsesMyntraProductApiPrice() {
        val result = ProductLookup.parse("myntra.com", 200, """{"style":{"name":"Gold Coin","sizes":[{"sizeSellerData":[{"discountedPrice":12345}]}]}}""")
        assertTrue(result is ProductLookup.Available && result.price == 12345.0)
    }

    @Test
    fun parsesAjioProductApiPrice() {
        val result = ProductLookup.parse("ajio.com", 200, """{"name":"Gold Coin","promoDiscountedPrice":6789}""")
        assertTrue(result is ProductLookup.Available && result.price == 6789.0)
    }

    @Test
    fun parsesAmazonAndFlipkartProductPagePrices() {
        val amazon = ProductLookup.parse("amazon.in", 200, """<div id="corePriceDisplay_desktop_feature_div"><span class="a-price-whole">12,345</span></div>""")
        val flipkart = ProductLookup.parse("flipkart.com", 200, """<h1>Gold Coin</h1><span>₹ 9,876</span>""")
        assertTrue(amazon is ProductLookup.Available && amazon.price == 12345.0)
        assertTrue(flipkart is ProductLookup.Available && flipkart.price == 9876.0)
    }

    @Test
    fun classifiesOnlyExplicitUnavailableResponses() {
        assertTrue(ProductLookup.parse("myntra.com", 200, """{"style":{"flags":{"outOfStock":true}}}""") is ProductLookup.Unavailable)
        assertTrue(ProductLookup.parse("amazon.in", 404, "Not found") is ProductLookup.Unavailable)
        val flipkart = ProductLookup.parse("flipkart.com", 200, """<span>₹81,632</span> Not deliverable at your location""")
        assertTrue(flipkart is ProductLookup.Unavailable && flipkart.price == 81632.0)
        assertFalse(ProductLookup.parse("flipkart.com", 403, "Access denied") is ProductLookup.Unavailable)
    }
}