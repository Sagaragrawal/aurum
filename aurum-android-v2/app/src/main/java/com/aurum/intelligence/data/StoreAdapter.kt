package com.aurum.intelligence.data

import java.util.concurrent.ConcurrentHashMap

enum class StoreId {
    Ajio,
    Amazon,
    Flipkart,
    Myntra,
    Shopsy,
}

data class StoreCapabilities(
    val supportsDirectHttpApi: Boolean,
    val requiresWebViewDom: Boolean,
    val supportsBlinkDeals: Boolean,
    val supportsPincodeCookies: Boolean,
)

data class BlinkDealInfo(
    val title: String,
    val originalPrice: Double,
    val dealPrice: Double,
    val endTimeMillis: Long? = null,
)

data class PdpExtractionResult(
    val price: Double?,
    val available: Boolean
)

interface StoreAdapter {
    val storeId: StoreId
    val storeName: String
    val displayName: String
    val canonicalHost: String
    val capabilities: StoreCapabilities

    fun getSearchUrls(pincode: String): List<String>
    fun getProductApiEndpoint(retailerId: String): String?
    fun configurePincodeCookies(pincode: String): String
    fun isDeliverable(body: String, pincode: String): Boolean
    fun extractBlinkDeal(body: String): BlinkDealInfo?
    fun getPdpJsExtractor(): String?
}

abstract class BaseStoreAdapter : StoreAdapter {
    override fun configurePincodeCookies(pincode: String): String = """
        (function() {
            try {
                document.cookie = "pincode=$pincode; path=/; max-age=31536000; SameSite=Lax";
                document.cookie = "ajio_pincode=$pincode; path=/; max-age=31536000; SameSite=Lax";
                document.cookie = "mynt-ulc=pincode:$pincode; path=/; Secure; SameSite=Lax";
                document.cookie = "fk_pincode=$pincode; path=/; max-age=31536000; SameSite=Lax";
                document.querySelectorAll('.ic-close, [data-testid="close-button"], .close-button, .modal-close, button.close, #pge-close-x, .pincode-modal-close').forEach(function(el) { el.click(); });
            } catch (_) {}
        })();
    """.trimIndent()

    override fun isDeliverable(body: String, pincode: String): Boolean {
        if (body.isBlank()) return true
        val unserviceableTerms = listOf(
            "not deliverable",
            "currently unavailable",
            "out of stock",
            "pincode not serviceable",
            "cannot be delivered",
            "unserviceable",
            "isAvailable\":false",
            "purchasable\":false",
            "stockLevelStatus\":\"outOfStock\"",
        )
        return unserviceableTerms.none { body.contains(it, ignoreCase = true) }
    }

    override fun extractBlinkDeal(body: String): BlinkDealInfo? = null
    override fun getPdpJsExtractor(): String? = null
}

class AjioAdapter : BaseStoreAdapter() {
    override val storeId = StoreId.Ajio
    override val storeName = "ajio.com"
    override val displayName = "AJIO"
    override val canonicalHost = "www.ajio.com"
    override val capabilities = StoreCapabilities(
        supportsDirectHttpApi = true,
        requiresWebViewDom = true,
        supportsBlinkDeals = false,
        supportsPincodeCookies = true,
    )

    override fun getSearchUrls(pincode: String): List<String> = listOf(
        "https://www.ajio.com/women/c/8303?query=%3Arelevance%3Averticalmetalpurity%3A24+Karat%3Averticalmetalpurity%3A24+Karat+%28995%29%3Averticalmetalpurity%3A24+Karat+%28999%29%3Averticalmetalpurity%3A24+Kt%3Averticalmetalpurity%3A24+Kt+%28995%29%3Averticalmetalpurity%3A24+Kt+%28999%29%3Averticalmetalpurity%3A24+Kt+%28999.9%29%3Averticalmetalpurity%3A999%3Averticalmetalpurity%3A22+Kt+%28916%29",
        "https://www.ajio.com/s/jewellery-176606?query=%3Arelevance%3Averticalmetalpurity%3A24+Karat%3Averticalmetalpurity%3A24+Karat+%28995%29%3Averticalmetalpurity%3A24+Karat+%28999%29%3Averticalmetalpurity%3A24+Kt%3Averticalmetalpurity%3A24+Kt+%28995%29%3Averticalmetalpurity%3A24+Kt+%28999%29%3Averticalmetalpurity%3A24+Kt+%28999.9%29%3Averticalmetalpurity%3A999%3Averticalmetalpurity%3A22+Kt",
    )

    override fun getProductApiEndpoint(retailerId: String): String = "https://www.ajio.com/api/p/$retailerId"

    override fun isDeliverable(body: String, pincode: String): Boolean {
        if (!body.contains("deliveryDetails")) return false
        return !body.contains("Not deliverable", ignoreCase = true) && !body.contains("Sorry! We do not deliver", ignoreCase = true)
    }

    override fun getPdpJsExtractor(): String = """
        (function() {
            var priceEl = document.querySelector('.prod-sp');
            var price = priceEl ? parseFloat(priceEl.textContent.replace(/[^0-9.]/g, '')) : null;
            var unavailable = document.querySelector('.out-of-stock') != null || document.body.innerText.includes('Not deliverable');
            return JSON.stringify({ price: price, available: !unavailable });
        })()
    """.trimIndent()
}

class AmazonAdapter : BaseStoreAdapter() {
    override val storeId = StoreId.Amazon
    override val storeName = "amazon.in"
    override val displayName = "Amazon"
    override val canonicalHost = "www.amazon.in"
    override val capabilities = StoreCapabilities(
        supportsDirectHttpApi = false,
        requiresWebViewDom = true,
        supportsBlinkDeals = false,
        supportsPincodeCookies = true,
    )

    override fun getSearchUrls(pincode: String): List<String> = listOf(
        "https://www.amazon.in/s?i=jewelry&rh=n%3A2908910031%2Cp_n_material_two_browse-bin%3A2160347031&s=popularity-rank&dc&fs=true&rnid=2160329031&xpid=ZrCUqOwcv7FyR&ref=sr_pg_1",
    )

    override fun getProductApiEndpoint(retailerId: String): String? = null

    override fun isDeliverable(body: String, pincode: String): Boolean {
        if (body.contains("Currently unavailable", ignoreCase = true)) return false
        if (body.contains("cannot be delivered", ignoreCase = true)) return false
        return true
    }

    override fun getPdpJsExtractor(): String = """
        (function() {
            var priceEl = document.querySelector('.a-price-whole');
            var price = priceEl ? parseFloat(priceEl.textContent.replace(/[^0-9.]/g, '')) : null;
            var unavailable = document.querySelector('#outOfStock') != null || document.body.innerText.includes('Currently unavailable');
            return JSON.stringify({ price: price, available: !unavailable });
        })()
    """.trimIndent()
}

class FlipkartAdapter : BaseStoreAdapter() {
    override val storeId = StoreId.Flipkart
    override val storeName = "flipkart.com"
    override val displayName = "Flipkart"
    override val canonicalHost = "www.flipkart.com"
    override val capabilities = StoreCapabilities(
        supportsDirectHttpApi = false,
        requiresWebViewDom = true,
        supportsBlinkDeals = false,
        supportsPincodeCookies = true,
    )

    override fun getSearchUrls(pincode: String): List<String> = listOf(
        "https://www.flipkart.com/gold-silver-coins/pr?sid=mcr%2C73x%2Cydh&marketplace=FLIPKART&p%5B%5D=facets.material%255B%255D%3DYellow%2BGold&p%5B%5D=facets.material%255B%255D%3DGold",
    )

    override fun getProductApiEndpoint(retailerId: String): String? = null

    override fun isDeliverable(body: String, pincode: String): Boolean {
        if (body.contains("Currently Unavailable", ignoreCase = true)) return false
        if (body.contains("Not deliverable", ignoreCase = true)) return false
        if (body.contains("Out of stock", ignoreCase = true)) return false
        return true
    }

    override fun getPdpJsExtractor(): String = """
        (function() {
            var priceEl = document.querySelector('div._30jeq3, div.Nx9bqj');
            var price = priceEl ? parseFloat(priceEl.textContent.replace(/[^0-9.]/g, '')) : null;
            var unavailable = document.body.innerText.includes('Currently Unavailable') || document.body.innerText.includes('Out of stock') || document.body.innerText.includes('Not deliverable');
            return JSON.stringify({ price: price, available: !unavailable });
        })()
    """.trimIndent()
}

class MyntraAdapter : BaseStoreAdapter() {
    override val storeId = StoreId.Myntra
    override val storeName = "myntra.com"
    override val displayName = "Myntra"
    override val canonicalHost = "www.myntra.com"
    override val capabilities = StoreCapabilities(
        supportsDirectHttpApi = true,
        requiresWebViewDom = true,
        supportsBlinkDeals = true,
        supportsPincodeCookies = true,
    )

    override fun getSearchUrls(pincode: String): List<String> = listOf("https://www.myntra.com/gold-coin")

    override fun getProductApiEndpoint(retailerId: String): String = "https://www.myntra.com/gateway/v2/product/$retailerId"

    override fun extractBlinkDeal(body: String): BlinkDealInfo? {
        if (!body.contains("blink", ignoreCase = true) && !body.contains("flash", ignoreCase = true)) return null
        val match = Regex("\"blinkDeal Price\"\\s*:\\s*([\\d.]+)", RegexOption.IGNORE_CASE).find(body)
            ?: Regex("\"specialPrice\"\\s*:\\s*([\\d.]+)", RegexOption.IGNORE_CASE).find(body)
            ?: return null
        val dealPrice = match.groupValues[1].toDoubleOrNull() ?: return null
        val mrpMatch = Regex("\"mrp\"\\s*:\\s*([\\d.]+)", RegexOption.IGNORE_CASE).find(body)
        val mrp = mrpMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: dealPrice
        
        val couponMatch = Regex("\"couponDiscount\"\\s*:\\s*([\\d.]+)", RegexOption.IGNORE_CASE).find(body)
        val couponDiscount = couponMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        val finalDealPrice = (dealPrice - couponDiscount).coerceAtLeast(0.0)
        
        return BlinkDealInfo("Myntra Blink Deal", mrp, finalDealPrice)
    }

    override fun isDeliverable(body: String, pincode: String): Boolean {
        if (body.contains("\"isBuyable\":false")) return false
        if (body.contains("\"outOfStock\":true")) return false
        if (body.contains("\"serviceability\":{\"serviceable\":false")) return false
        return true
    }

    override fun getPdpJsExtractor(): String = """
        (function() {
            var priceEl = document.querySelector('.pdp-price');
            var price = priceEl ? parseFloat(priceEl.textContent.replace(/[^0-9.]/g, '')) : null;
            var unavailable = document.querySelector('.pdp-out-of-stock-title') != null || document.body.innerText.includes('Out of Stock');
            return JSON.stringify({ price: price, available: !unavailable });
        })()
    """.trimIndent()
}

class ShopsyAdapter : BaseStoreAdapter() {
    override val storeId = StoreId.Shopsy
    override val storeName = "shopsy.in"
    override val displayName = "Shopsy"
    override val canonicalHost = "www.shopsy.in"
    override val capabilities = StoreCapabilities(
        supportsDirectHttpApi = false,
        requiresWebViewDom = true,
        supportsBlinkDeals = false,
        supportsPincodeCookies = true,
    )

    override fun getSearchUrls(pincode: String): List<String> = listOf(
        "https://www.shopsy.in/gold-silver-coins/pr?sid=mcr,73x&marketplace=FLIPKART&p[]=facets.material[]=Gold&p[]=facets.material[]=Yellow+Gold&p[]=facets.gold_purity%5B%5D=24+%28999%29+K&p%5B%5D=facets.gold_purity%255B%255D%3D24%2B%25289999%2529%2BK",
    )

    override fun getProductApiEndpoint(retailerId: String): String? = null

    override fun isDeliverable(body: String, pincode: String): Boolean {
        if (body.contains("Currently Unavailable", ignoreCase = true)) return false
        if (body.contains("Not deliverable", ignoreCase = true)) return false
        if (body.contains("Out of stock", ignoreCase = true)) return false
        return true
    }

    override fun getPdpJsExtractor(): String = """
        (function() {
            var priceEl = document.querySelector('div._30jeq3, div.Nx9bqj, div[class*="price"]');
            var price = priceEl ? parseFloat(priceEl.textContent.replace(/[^0-9.]/g, '')) : null;
            var unavailable = document.body.innerText.includes('Currently Unavailable') || document.body.innerText.includes('Out of stock') || document.body.innerText.includes('Not deliverable');
            return JSON.stringify({ price: price, available: !unavailable });
        })()
    """.trimIndent()
}

object StoreRegistry {
    private val adapters = ConcurrentHashMap<String, StoreAdapter>()

    init {
        register(AjioAdapter())
        register(AmazonAdapter())
        register(FlipkartAdapter())
        register(MyntraAdapter())
        register(ShopsyAdapter())
    }

    fun register(adapter: StoreAdapter) {
        adapters[adapter.storeName.lowercase()] = adapter
        adapters[adapter.storeId.name.lowercase()] = adapter
    }

    fun get(storeNameOrId: String): StoreAdapter? = adapters[storeNameOrId.lowercase()]

    fun getAll(): List<StoreAdapter> = listOf(
        adapters["ajio.com"]!!,
        adapters["amazon.in"]!!,
        adapters["flipkart.com"]!!,
        adapters["myntra.com"]!!,
        adapters["shopsy.in"]!!,
    )
}
