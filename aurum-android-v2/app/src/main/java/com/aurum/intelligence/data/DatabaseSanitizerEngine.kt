package com.aurum.intelligence.data

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
        return weightGrams != null && weightGrams > 0 && weightGrams < 0.25
    }

    fun isNonGold(title: String, extraText: String? = null): Boolean {
        val combined = "$title ${extraText.orEmpty()}".lowercase()

        // 1. Explicit silver keywords
        if (Regex("\\b(?:silver\\s*coin|silver\\s*bar|silver\\s*pendant|fine\\s*silver|sterling\\s*silver|999\\s*silver|999\\.9\\s*silver|silver\\s*999|9999\\s*silver|chandi|silver\\s*biscuit|silver\\s*round)\\b", RegexOption.IGNORE_CASE).containsMatchIn(combined)) {
            if (!Regex("\\bgold\\s*coin\\b|\\bgold\\s*bar\\b|\\b24\\s*k\\s*gold\\b|\\b22\\s*k\\s*gold\\b", RegexOption.IGNORE_CASE).containsMatchIn(combined)) {
                return true
            }
        }
        // 2. Explicit platinum keywords
        if (Regex("\\b(?:platinum\\s*coin|platinum\\s*bar|platinum\\s*pendant|pt\\s*950|pt950|pt\\s*999|pt999|950\\s*platinum|999\\s*platinum)\\b", RegexOption.IGNORE_CASE).containsMatchIn(combined)) {
            if (!Regex("\\bgold\\s*coin\\b|\\bgold\\s*bar\\b|\\b24\\s*k\\s*gold\\b|\\b22\\s*k\\s*gold\\b", RegexOption.IGNORE_CASE).containsMatchIn(combined)) {
                return true
            }
        }
        // 3. Plated / imitation / base metals
        if (Regex("\\b(?:gold[- ]?plated|gold tone|gold coated|gold colour|gold color|vermeil|imitation|brass|copper|steel|alloy|base metal)\\b", RegexOption.IGNORE_CASE).containsMatchIn(combined)) {
            return true
        }
        // 4. Spec metal type
        if (Regex("\\bmetal\\s*(?:type)?\\s*:\\s*(?:silver|platinum|brass|copper|steel)\\b", RegexOption.IGNORE_CASE).containsMatchIn(combined)) {
            return true
        }
        // 5. If title has "silver", "platinum", or "chandi" but no mention of "gold" at all:
        // 5. Idols, utensils, diyas, kalash, decorative items without coin/bar
        if (Regex("\\b(?:idol|idols|diya|diyas|kalash|utensil|utensils|vessel|vessels|acrylic\\s*base)\\b", RegexOption.IGNORE_CASE).containsMatchIn(combined)) {
            if (!Regex("\\b(?:coin|bar)\\b", RegexOption.IGNORE_CASE).containsMatchIn(combined) || !Regex("\\bgold\\b", RegexOption.IGNORE_CASE).containsMatchIn(combined)) {
                return true
            }
        }
        // 6. Non-bullion jewelry (nose pins, earrings, rings, necklaces, chains, bangles, mangalsutras, bracelets)
        if (Regex("\\b(?:nose\\s*pin|earring|earrings|ring|rings|necklace|necklaces|chain|chains|bangle|bangles|mangalsutra|bracelet|anklet)\\b", RegexOption.IGNORE_CASE).containsMatchIn(combined)) {
            if (!Regex("\\b(?:coin|bar)\\b", RegexOption.IGNORE_CASE).containsMatchIn(combined)) {
                return true
            }
        }
        // 7. If title has "silver", "platinum", or "chandi" but no mention of "gold" at all:
        if (!Regex("\\bgold\\b", RegexOption.IGNORE_CASE).containsMatchIn(combined)) {
            if (Regex("\\b(?:silver|platinum|chandi|silverspot)\\b", RegexOption.IGNORE_CASE).containsMatchIn(combined)) {
                return true
            }
        }
        return false
    }

    fun normalizeVendorWeight(grams: Double?, price: Double): Double? {
        if (grams == null || !grams.isFinite() || grams <= 0) return null
        // If price / grams is < ₹3,000/g for a gold coin (current gold rate is ~₹15,000/g):
        // Check if vendor entered 500g instead of 500mg (or 100g instead of 100mg, 50g instead of 50mg)
        if (grams >= 50.0 && (price / grams) < 3000.0 && price < 100000.0) {
            return grams / 1000.0
        }
        return grams
    }

    fun validatePricePlausibility(price: Double, weightGrams: Double?, karat: Double? = 24.0, bullionRate24: Double? = null): Boolean {
        if (price <= 0 || !price.isFinite()) return false
        if (weightGrams == null || weightGrams <= 0) return true // Cannot evaluate price per gram without weight

        val pricePerGram = price / weightGrams
        val minPlausible = 3000.0 // Min ₹3,000/g
        val maxPlausible = 35000.0 // Max ₹35,000/g

        if (pricePerGram !in minPlausible..maxPlausible) return false

        if (bullionRate24 != null && bullionRate24 > 0) {
            val karatFactor = (karat ?: 24.0) / 24.0
            val benchmarkPerGram = bullionRate24 * karatFactor
            // Price per gram should not be less than 95% or more than 300% of live bullion rate
            if (pricePerGram < benchmarkPerGram * 0.95 || pricePerGram > benchmarkPerGram * 3.0) {
                return false
            }
        }

        return true
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
            if (!validation.isValid) {
                database.dao().deleteProduct(product.id)
                deletedCount++
            } else if (validation.normalizedTitle != product.name || validation.normalizedKarat != product.karat || validation.normalizedPurity != product.purity) {
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
}
