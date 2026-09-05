package com.aurum.intelligence.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProductIdentityTest {
    @Test
    fun derivesSupportedRetailerIds() {
        assertEquals(
            ProductAddress(
                store = "amazon.in",
                retailerId = "B0CSNFCVPX",
                canonicalUrl = "https://www.amazon.in/dp/B0CSNFCVPX",
            ),
            ProductIdentity.derive("https://amazon.in/dp/B0CSNFCVPX?tag=noise"),
        )
        assertEquals(
            "6005834780_multi",
            ProductIdentity.derive("https://www.ajio.com/example/p/6005834780_multi").retailerId,
        )
        assertEquals(
            "35319675",
            ProductIdentity.derive("https://www.myntra.com/gold-coins/example/35319675/buy").retailerId,
        )
        assertEquals(
            "ABC123",
            ProductIdentity.derive("https://www.flipkart.com/item?pid=ABC123&iid=noise").retailerId,
        )
        assertEquals(
            "SHP123",
            ProductIdentity.derive("https://www.shopsy.in/item?pid=SHP123&iid=noise").retailerId,
        )
    }

    @Test
    fun rejectsNonHttpsAndUnsupportedStores() {
        assertThrows(IllegalArgumentException::class.java) {
            ProductIdentity.derive("http://www.amazon.in/dp/B0CSNFCVPX")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProductIdentity.derive("https://example.com/product/1")
        }
    }

    @Test
    fun fallbackRetailerIdIsStableAndUrlSpecific() {
        val first = ProductIdentity.derive("https://www.ajio.com/s/gold-coins")
        val same = ProductIdentity.derive("https://ajio.com/s/gold-coins")
        val other = ProductIdentity.derive("https://www.ajio.com/s/gold-bars")

        assertEquals(first.retailerId, same.retailerId)
        assertNotEquals(first.retailerId, other.retailerId)
    }

    @Test
    fun canonicalizesRegularFlipkartByPid() {
        assertEquals(
            "https://www.flipkart.com/item?pid=ABC123&marketplace=FLIPKART",
            ProductIdentity.canonicalUrl("https://www.flipkart.com/item?pid=ABC123&iid=noise&ssid=other"),
        )
    }

    @Test
    fun preservesHyperlocalShopIdentity() {
        assertEquals(
            "https://www.flipkart.com/item?pid=ABC123&marketplace=HYPERLOCAL&shopId=SHOP9",
            ProductIdentity.canonicalUrl("https://www.flipkart.com/item?marketplace=HYPERLOCAL&pid=ABC123&shopId=SHOP9&iid=noise"),
        )
    }
}