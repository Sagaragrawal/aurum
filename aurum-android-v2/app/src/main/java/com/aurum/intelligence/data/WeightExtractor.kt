package com.aurum.intelligence.data

enum class WeightConfidence { High, Medium, Low, Ambiguous }

enum class WeightSource { SpecificationTable, TitleExpression, PdpFallback, ManualEdit }

data class ProductWeight(
    val unitWeightGrams: Double?,
    val quantity: Int = 1,
    val totalWeightGrams: Double?,
    val confidence: WeightConfidence,
    val source: WeightSource,
    val rawMatchedText: String? = null,
)

object WeightExtractor {

    fun parse(title: String, body: String? = null): ProductWeight {
        val sanitizedTitle = sanitizeText(title)
        val sanitizedBody = body?.let(::sanitizeText).orEmpty()

        // STEP 1: Check explicit specification key-values in body/JSON first
        val specWeight = parseSpecificationTable(sanitizedBody)
        if (specWeight != null) return specWeight

        // STEP 2: Check multi-coin pack & expression patterns in title
        val packWeight = parseMultiPackExpression(sanitizedTitle)
        if (packWeight != null) return packWeight

        // STEP 3: Check single weight patterns in title
        val singleWeight = parseSingleExpression(sanitizedTitle)
        if (singleWeight != null) return singleWeight

        // STEP 4: Check body for weight expressions as PDP fallback
        val bodyWeight = parseSingleExpression(sanitizedBody)
        if (bodyWeight != null) {
            return bodyWeight.copy(confidence = WeightConfidence.Medium, source = WeightSource.PdpFallback)
        }

        return ProductWeight(
            unitWeightGrams = null,
            quantity = 1,
            totalWeightGrams = null,
            confidence = WeightConfidence.Ambiguous,
            source = WeightSource.TitleExpression,
        )
    }

    private fun sanitizeText(text: String): String {
        return text
            .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\b(?:24|22|18|14)\\s*[kK]\\b"), " ") // Strip 24K, 22K
            .replace(Regex("\\b(?:999\\.9|999|995|916|750)\\b"), " ") // Strip purity fineness
            .replace(Regex("\\b202[0-9]\\b"), " ") // Strip years like 2024
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun parseSpecificationTable(text: String): ProductWeight? {
        if (text.isBlank()) return null
        val specRegex = Regex(
            "(?:weight|net weight|gross weight|gold weight|product weight)[\\\"\\s]*[:=]?\\s*[\\\"\\s]*(\\d+(?:\\.\\d+)?)\\s*(mg|gms|gm|grams|gram|g)\\b",
            RegexOption.IGNORE_CASE,
        )
        val match = specRegex.find(text) ?: return null
        val amount = match.groupValues[1].toDoubleOrNull() ?: return null
        val unit = match.groupValues[2].lowercase()
        val grams = if (unit == "mg") amount / 1000.0 else amount
        if (grams in 0.01..500.0) {
            return ProductWeight(
                unitWeightGrams = grams,
                quantity = 1,
                totalWeightGrams = grams,
                confidence = WeightConfidence.High,
                source = WeightSource.SpecificationTable,
                rawMatchedText = match.groupValues[0],
            )
        }
        return null
    }

    private fun parseMultiPackExpression(title: String): ProductWeight? {
        // Pattern 1: "1g x 2", "1 gram x 2", "0.5g * 2"
        val multiplierRegex = Regex("(\\d+(?:\\.\\d+)?)\\s*(mg|gms|gm|grams|gram|g)\\s*[x*×]\\s*(\\d+)\\b", RegexOption.IGNORE_CASE)
        val m1 = multiplierRegex.find(title)
        if (m1 != null) {
            val amount = m1.groupValues[1].toDoubleOrNull()
            val unit = m1.groupValues[2].lowercase()
            val qty = m1.groupValues[3].toIntOrNull() ?: 1
            if (amount != null && amount > 0) {
                val unitGrams = if (unit == "mg") amount / 1000.0 else amount
                return ProductWeight(
                    unitWeightGrams = unitGrams,
                    quantity = qty,
                    totalWeightGrams = unitGrams * qty,
                    confidence = WeightConfidence.High,
                    source = WeightSource.TitleExpression,
                    rawMatchedText = m1.groupValues[0],
                )
            }
        }

        // Pattern 2: "2 x 1g", "2 x 0.5 gram"
        val prefixMultiplierRegex = Regex("(\\d+)\\s*[x*×]\\s*(\\d+(?:\\.\\d+)?)\\s*(mg|gms|gm|grams|gram|g)\\b", RegexOption.IGNORE_CASE)
        val m2 = prefixMultiplierRegex.find(title)
        if (m2 != null) {
            val qty = m2.groupValues[1].toIntOrNull() ?: 1
            val amount = m2.groupValues[2].toDoubleOrNull()
            val unit = m2.groupValues[3].lowercase()
            if (amount != null && amount > 0) {
                val unitGrams = if (unit == "mg") amount / 1000.0 else amount
                return ProductWeight(
                    unitWeightGrams = unitGrams,
                    quantity = qty,
                    totalWeightGrams = unitGrams * qty,
                    confidence = WeightConfidence.High,
                    source = WeightSource.TitleExpression,
                    rawMatchedText = m2.groupValues[0],
                )
            }
        }

        // Pattern 3: "0.5g + 0.5g", "1 gram + 1 gram"
        val additionRegex = Regex("(\\d+(?:\\.\\d+)?)\\s*(mg|gms|gm|grams|gram|g)\\s*\\+\\s*(\\d+(?:\\.\\d+)?)\\s*(mg|gms|gm|grams|gram|g)", RegexOption.IGNORE_CASE)
        val m3 = additionRegex.find(title)
        if (m3 != null) {
            val a1 = m3.groupValues[1].toDoubleOrNull() ?: 0.0
            val u1 = if (m3.groupValues[2].lowercase() == "mg") a1 / 1000.0 else a1
            val a2 = m3.groupValues[3].toDoubleOrNull() ?: 0.0
            val u2 = if (m3.groupValues[4].lowercase() == "mg") a2 / 1000.0 else a2
            val total = u1 + u2
            if (total in 0.01..500.0) {
                return ProductWeight(
                    unitWeightGrams = u1,
                    quantity = 2,
                    totalWeightGrams = total,
                    confidence = WeightConfidence.High,
                    source = WeightSource.TitleExpression,
                    rawMatchedText = m3.groupValues[0],
                )
            }
        }

        // Pattern 4: "Pack of 2 (0.5g each)" or "Pack of 2 Gold Coin 1g"
        val packRegex = Regex("(?:pack|set|lot)\\s+of\\s+(\\d+)[\\s\\S]{0,40}?(\\d+(?:\\.\\d+)?)\\s*(mg|gms|gm|grams|gram|g)\\b", RegexOption.IGNORE_CASE)
        val m4 = packRegex.find(title)
        if (m4 != null) {
            val qty = m4.groupValues[1].toIntOrNull() ?: 1
            val amount = m4.groupValues[2].toDoubleOrNull() ?: 0.0
            val unit = m4.groupValues[3].lowercase()
            val unitGrams = if (unit == "mg") amount / 1000.0 else amount
            if (unitGrams in 0.01..500.0) {
                return ProductWeight(
                    unitWeightGrams = unitGrams,
                    quantity = qty,
                    totalWeightGrams = unitGrams * qty,
                    confidence = WeightConfidence.High,
                    source = WeightSource.TitleExpression,
                    rawMatchedText = m4.groupValues[0],
                )
            }
        }

        return null
    }

    private fun parseSingleExpression(title: String): ProductWeight? {
        val singleRegex = Regex("(\\d+(?:\\.\\d+)?)\\s*(mg|gms|gm|grams|gram|g)\\b", RegexOption.IGNORE_CASE)
        val matches = singleRegex.findAll(title).mapNotNull { match ->
            val amount = match.groupValues[1].toDoubleOrNull() ?: return@mapNotNull null
            val unit = match.groupValues[2].lowercase()
            val grams = if (unit == "mg") amount / 1000.0 else amount
            if (grams in 0.05..500.0) {
                match.groupValues[0] to grams
            } else null
        }.toList()

        if (matches.size == 1) {
            val (raw, grams) = matches.first()
            return ProductWeight(
                unitWeightGrams = grams,
                quantity = 1,
                totalWeightGrams = grams,
                confidence = WeightConfidence.High,
                source = WeightSource.TitleExpression,
                rawMatchedText = raw,
            )
        } else if (matches.size > 1) {
            // Multiple weights in title - return maximum valid weight with Medium confidence
            val maxMatch = matches.maxByOrNull { it.second }!!
            return ProductWeight(
                unitWeightGrams = maxMatch.second,
                quantity = 1,
                totalWeightGrams = maxMatch.second,
                confidence = WeightConfidence.Medium,
                source = WeightSource.TitleExpression,
                rawMatchedText = maxMatch.first,
            )
        }

        return null
    }
}
