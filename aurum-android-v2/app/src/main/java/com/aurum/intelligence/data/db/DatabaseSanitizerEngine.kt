package com.aurum.intelligence.data.db
import com.aurum.intelligence.data.db.*
import com.aurum.intelligence.data.engine.*
import com.aurum.intelligence.data.model.*
import com.aurum.intelligence.data.repository.*
import com.aurum.intelligence.data.validation.*

object DatabaseSanitizerEngine {

    private val priceBadgeRegex = Regex("(?:₹|\\bRs\\.?|OFF|off|Only\\s+few|left|\\d+%)", RegexOption.IGNORE_CASE)
    private val trailingPriceRegex = Regex("(?:₹|\\bRs\\.?)\\s*[\\d,]+(?:\\.\\d+)?.*$", RegexOption.IGNORE_CASE)
    private val discountPercentRegex = Regex("\\b\\d{1,2}%\\s*off.*$", RegexOption.IGNORE_CASE)
    private val inventoryBadgeRegex = Regex("\\bOnly\\s+few\\s+left.*$", RegexOption.IGNORE_CASE)

    fun cleanTitle(rawName: String): String {
        if (rawName.isBlank()) return rawName

        var name = rawName.trim()

        // 1. Strip trailing price strings e.g. "₹8,413₹15,00043% offOnly few left"
        name = trailingPriceRegex.replace(name, "").trim()
        name = discountPercentRegex.replace(name, "").trim()
        name = inventoryBadgeRegex.replace(name, "").trim()

        // 2. Remove any remaining raw currency symbols or standalone "off" badges
        name = name.replace(Regex("₹\\s*[\\d,]+"), " ")
            .replace(Regex("\\bRs\\.?\\s*[\\d,]+", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\b\\d+%", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()

        return if (name.length >= 3) name else rawName.trim()
    }

    fun resolveKarat(title: String, existingKarat: Double?): Double? {
        if (existingKarat != null && existingKarat in 1.0..24.0) return existingKarat
        return when {
            Regex("\\b24\\s*[kK]?\\b|\\b999\\b|\\b9999\\b|\\b24\\s*Karat\\b", RegexOption.IGNORE_CASE).containsMatchIn(title) -> 24.0
            Regex("\\b22\\s*[kK]?\\b|\\b916\\b|\\b22\\s*Karat\\b", RegexOption.IGNORE_CASE).containsMatchIn(title) -> 22.0
            Regex("\\b18\\s*[kK]?\\b|\\b750\\b", RegexOption.IGNORE_CASE).containsMatchIn(title) -> 18.0
            else -> null
        }
    }

    fun resolvePurity(title: String, existingPurity: String?): String? {
        if (!existingPurity.isNullOrBlank()) return existingPurity
        return when {
            title.contains("999.9", ignoreCase = true) || title.contains("9999", ignoreCase = true) -> "999.9"
            title.contains("999", ignoreCase = true) -> "999"
            title.contains("995", ignoreCase = true) -> "995"
            title.contains("916", ignoreCase = true) -> "916"
            else -> null
        }
    }

    fun isMicroCoin(weightGrams: Double?): Boolean {
        val maxGrams = ScraperConfigProvider.get().policy.microCoinMaxGrams
        return weightGrams != null && weightGrams > 0 && weightGrams < maxGrams
    }

    fun isNonGold(title: String, extraText: String? = null): Boolean {
        val combined = "$title ${extraText.orEmpty()}".lowercase()

        // 1. Explicit silver keywords - if silver/chandi/sterling/silverware/silverspot is present in metal/title/description, it IS silver
        if (Regex("""\b(?:silver|chandi|sterling|silverware|silverspot)\b""", RegexOption.IGNORE_CASE).containsMatchIn(combined)) {
            return true
        }

        // 2. Explicit platinum keywords
        if (Regex("""\b(?:platinum\s*coin|platinum\s*bar|platinum\s*pendant|pt\s*950|pt950|pt\s*999|pt999|950\s*platinum|999\s*platinum|platinum)\b""", RegexOption.IGNORE_CASE).containsMatchIn(combined)) {
            if (!Regex("""\bgold\s*coin\b|\bgold\s*bar\b|\b24\s*k\s*gold\b|\b22\s*k\s*gold\b""", RegexOption.IGNORE_CASE).containsMatchIn(combined)) {
                return true
            }
        }
        // 3. Plated / imitation / base metals
        if (Regex("""\b(?:gold[- ]?plated|gold tone|gold coated|gold colour|gold color|vermeil|imitation|brass|copper|steel|alloy|base metal)\b""", RegexOption.IGNORE_CASE).containsMatchIn(combined)) {
            return true
        }
        // 4. Spec metal type
        if (Regex("""\bmetal\s*(?:type)?\s*:\s*(?:silver|platinum|brass|copper|steel)\b""", RegexOption.IGNORE_CASE).containsMatchIn(combined)) {
            return true
        }
        // 5. Idols, utensils, diyas, kalash, decorative items without coin/bar
        if (Regex("""\b(?:idol|idols|diya|diyas|kalash|utensil|utensils|vessel|vessels|acrylic\s*base)\b""", RegexOption.IGNORE_CASE).containsMatchIn(combined)) {
            if (!Regex("""\b(?:coin|bar)\b""", RegexOption.IGNORE_CASE).containsMatchIn(combined) || !Regex("""\bgold\b""", RegexOption.IGNORE_CASE).containsMatchIn(combined)) {
                return true
            }
        }
        // 6. Non-bullion jewelry (nose pins, earrings, rings, necklaces, chains, bangles, mangalsutras, bracelets)
        if (Regex("""\b(?:nose\s*pin|earring|earrings|ring|rings|necklace|necklaces|chain|chains|bangle|bangles|mangalsutra|bracelet|anklet)\b""", RegexOption.IGNORE_CASE).containsMatchIn(combined)) {
            val isCoinPendant = combined.contains("pendant") && (combined.contains("coin") || combined.contains("bar") || combined.contains("24") || combined.contains("999") || combined.contains("995"))
            val isVedhaniRing = (combined.contains("ring") || combined.contains("vedhani")) && (combined.contains("vedhani") || combined.contains("995") || combined.contains("999") || combined.contains("24"))
            if (!Regex("""\b(?:coin|bar|vedhani)\b""", RegexOption.IGNORE_CASE).containsMatchIn(combined) && !isCoinPendant && !isVedhaniRing) {
                return true
            }
        }
        // 7. If title has no mention of gold keyword at all
        if (!Regex("""\bgold\b""", RegexOption.IGNORE_CASE).containsMatchIn(combined)) {
            if (Regex("""\b(?:silver|platinum|chandi|silverspot)\b""", RegexOption.IGNORE_CASE).containsMatchIn(combined)) {
                return true
            }
        }
        return false
    }

    fun normalizeVendorWeight(grams: Double?, price: Double = 0.0): Double? {
        if (grams == null || !grams.isFinite() || grams <= 0) return null
        return grams
    }

    fun validatePricePlausibility(price: Double, weightGrams: Double? = null, karat: Double? = null, bullionRate24: Double? = null): Boolean {
        return price > 0 && price.isFinite()
    }

    suspend fun purgeNon24KGoldCoinsAndBars(database: AurumDatabase): Int {
        val allProducts = database.dao().allProducts()
        var deletedCount = 0
        for (product in allProducts) {
            val validation = Product24KValidator.validate(
                name = product.name,
                store = product.store,
                karat = product.karat,
                purity = product.purity,
                price = product.price,
                grams = product.grams,
            )
            val isManual = product.manuallyEditedAt != null
            if (!validation.isValid) {
                if (!isManual) {
                    database.dao().deleteProduct(product.id)
                    deletedCount++
                }
            } else if (!isManual && (validation.normalizedTitle != product.name || validation.normalizedKarat != product.karat || validation.normalizedPurity != product.purity)) {
                database.dao().upsertProduct(
                    product.copy(
                        name = validation.normalizedTitle,
                        karat = validation.normalizedKarat,
                        purity = validation.normalizedPurity,
                    )
                )
            }
        }
        return deletedCount
    }

    suspend fun reconcileStaleLiveProducts(database: AurumDatabase): Int {
        val allProducts = database.dao().allProducts()
        var demotedCount = 0
        val now = System.currentTimeMillis()
        val staleThreshold = now - ScraperConfigProvider.get().policy.staleThresholdMillis
        for (product in allProducts) {
            if (product.status == "live" && product.checkedAt < staleThreshold) {
                database.dao().upsertProduct(
                    product.copy(status = "stale")
                )
                demotedCount++
            }
        }
        return demotedCount
    }
}
