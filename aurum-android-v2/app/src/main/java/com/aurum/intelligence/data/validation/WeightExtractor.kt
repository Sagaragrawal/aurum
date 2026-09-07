package com.aurum.intelligence.data.validation
import com.aurum.intelligence.data.db.*
import com.aurum.intelligence.data.engine.*
import com.aurum.intelligence.data.model.*
import com.aurum.intelligence.data.repository.*
import com.aurum.intelligence.data.validation.*

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

        // STEP 2: Check multi-coin pack & addition expressions in body/PDP text first if present
        if (sanitizedBody.isNotBlank()) {
            val bodyPackWeight = parseMultiPackExpression(sanitizedBody)
            if (bodyPackWeight != null && bodyPackWeight.quantity > 1) {
                return bodyPackWeight.copy(source = WeightSource.SpecificationTable)
            }
        }

        // STEP 3: Check multi-coin pack & addition expressions in title
        val packWeight = parseMultiPackExpression(sanitizedTitle)
        if (packWeight != null) return packWeight

        // STEP 4: Check single weight patterns in title
        val singleWeight = parseSingleExpression(sanitizedTitle)
        if (singleWeight != null) return singleWeight

        // STEP 5: Check body for single weight expressions as PDP fallback
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
            .replace(Regex("\\b(?:24|22|18|14)\\s*[-_]?\\s*(?:k|kt|karat|carat|ct)?\\b|\\b10\\s*[-_]?\\s*(?:k|kt|karat|carat|ct)\\b", RegexOption.IGNORE_CASE), " ") // Strip 24KT, 22K, 18K, 14K, 10K
            .replace(Regex("\\b(?:999\\.90?|999\\.0?|9999|999|995\\.0?|995|916\\.0?|916|750|585)\\b"), " ") // Strip fineness
            .replace(Regex("\\b99\\.\\d+%?\\b"), " ") // Strip 99.9%, 99.99%, 99.5%
            .replace(Regex("\\b91\\.6%?\\b"), " ") // Strip 91.6%
            .replace(Regex("\\b(?:purity|fineness|finnes|hallmark|hallmarked|certified)\\b", RegexOption.IGNORE_CASE), " ")
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

        // Pattern 3: "10 + 10 g", "10g + 10g", "0.5 Gm + 1 Gm + 2 Gm"
        val additionRegex = Regex("(\\d+(?:\\.\\d+)?)\\s*(mg|gms|gm|grams|gram|g)?\\s*\\+\\s*(\\d+(?:\\.\\d+)?)\\s*(mg|gms|gm|grams|gram|g)?(?:\\s*\\+\\s*(\\d+(?:\\.\\d+)?)\\s*(mg|gms|gm|grams|gram|g)?)?", RegexOption.IGNORE_CASE)
        val m3 = additionRegex.find(title)
        if (m3 != null) {
            val matchedText = m3.value
            val units = Regex("(mg|gms|gm|grams|gram|g)\\b", RegexOption.IGNORE_CASE).findAll(matchedText).map { it.value.lowercase() }.toList()
            val trailUnitMatch = Regex("^[\\s)]*(mg|gms|gm|grams|gram|g)\\b", RegexOption.IGNORE_CASE).find(title.substring((m3.range.last + 1).coerceAtMost(title.length)))
            val unit = trailUnitMatch?.groupValues?.get(1)?.lowercase() ?: units.lastOrNull() ?: "g"
            val numbers = Regex("\\b\\d+(?:\\.\\d+)?").findAll(matchedText).mapNotNull { it.value.toDoubleOrNull() }.toList()
            if (numbers.size >= 2) {
                val converted = numbers.map { if (unit == "mg") it / 1000.0 else it }
                val totalGrams = converted.sum()
                val unitGrams = converted.first()
                if (totalGrams in 0.005..500.0) {
                    return ProductWeight(
                        unitWeightGrams = unitGrams,
                        quantity = numbers.size,
                        totalWeightGrams = totalGrams,
                        confidence = WeightConfidence.High,
                        source = WeightSource.TitleExpression,
                        rawMatchedText = m3.value,
                    )
                }
            }
        }

        val hasEachKeyword = Regex("\\b(?:each|per\\s*(?:coin|bar|pc|piece)|a\\s*piece)\\b", RegexOption.IGNORE_CASE).containsMatchIn(title)

        // Pattern 4: "Pack of 2 (0.5g each)" or "Set of 9 24Kt Gold Coin-1g"
        val packRegex = Regex("(?:pack|set|lot)\\s+of\\s+(\\d+)[\\s\\S]{0,200}?(\\d+(?:\\.\\d+)?)\\s*(mg|gms|gm|grams|gram|g)\\b", RegexOption.IGNORE_CASE)
        val m4 = packRegex.find(title)
        if (m4 != null) {
            val qty = m4.groupValues[1].toIntOrNull() ?: 1
            val amount = m4.groupValues[2].toDoubleOrNull() ?: 0.0
            val unit = m4.groupValues[3].lowercase()
            val weightVal = if (unit == "mg") amount / 1000.0 else amount
            val (unitGrams, totalGrams) = if (hasEachKeyword) {
                weightVal to (weightVal * qty)
            } else {
                (weightVal / qty.coerceAtLeast(1)) to weightVal
            }
            if (totalGrams in 0.005..500.0) {
                return ProductWeight(
                    unitWeightGrams = unitGrams,
                    quantity = qty,
                    totalWeightGrams = totalGrams,
                    confidence = WeightConfidence.High,
                    source = WeightSource.TitleExpression,
                    rawMatchedText = m4.groupValues[0],
                )
            }
        }

        // Pattern 5: "6Pcs ... 2 g Each", "3-Pcs 24 KT yellow gold coin ... Weight: 1 gm each"
        val pcsRegex = Regex("(\\d+)\\s*(?:-|\\s*)(?:pcs|pieces|pc)\\b[\\s\\S]{0,200}?(\\d+(?:\\.\\d+)?)\\s*(mg|gms|gm|grams|gram|g)\\b", RegexOption.IGNORE_CASE)
        val m5 = pcsRegex.find(title)
        if (m5 != null) {
            val qty = m5.groupValues[1].toIntOrNull() ?: 1
            val amount = m5.groupValues[2].toDoubleOrNull() ?: 0.0
            val unit = m5.groupValues[3].lowercase()
            val weightVal = if (unit == "mg") amount / 1000.0 else amount
            val (unitGrams, totalGrams) = if (hasEachKeyword) {
                weightVal to (weightVal * qty)
            } else {
                (weightVal / qty.coerceAtLeast(1)) to weightVal
            }
            if (totalGrams in 0.005..500.0) {
                return ProductWeight(
                    unitWeightGrams = unitGrams,
                    quantity = qty,
                    totalWeightGrams = totalGrams,
                    confidence = WeightConfidence.High,
                    source = WeightSource.TitleExpression,
                    rawMatchedText = m5.groupValues[0],
                )
            }
        }

        return null
    }

    private fun parseSingleExpression(title: String): ProductWeight? {
        // Check word numbers first: "Half Gram", "One Gram", "Ten Gram"
        val wordNumberRegex = Regex("\\b(half|one|two|three|four|five|ten|twenty|fifty|hundred)\\s*(?:-|\\s*)(mg|gms|gm|grams|gram|g)\\b", RegexOption.IGNORE_CASE)
        val wordMatch = wordNumberRegex.find(title)
        if (wordMatch != null) {
            val numStr = wordMatch.groupValues[1].lowercase()
            val unitStr = wordMatch.groupValues[2].lowercase()
            val baseVal = when (numStr) {
                "half" -> 0.5
                "one" -> 1.0
                "two" -> 2.0
                "three" -> 3.0
                "four" -> 4.0
                "five" -> 5.0
                "ten" -> 10.0
                "twenty" -> 20.0
                "fifty" -> 50.0
                "hundred" -> 100.0
                else -> null
            }
            if (baseVal != null) {
                val grams = if (unitStr == "mg") baseVal / 1000.0 else baseVal
                return ProductWeight(
                    unitWeightGrams = grams,
                    quantity = 1,
                    totalWeightGrams = grams,
                    confidence = WeightConfidence.High,
                    source = WeightSource.TitleExpression,
                    rawMatchedText = wordMatch.groupValues[0],
                )
            }
        }

        // Check fractions: "1/2 Gram", "1/4 Gram", "1/10 Gram"
        val fractionRegex = Regex("\\b(\\d+)\\s*/\\s*(\\d+)\\s*(?:-|\\s*)(mg|gms|gm|grams|gram|g)\\b", RegexOption.IGNORE_CASE)
        val fracMatch = fractionRegex.find(title)
        if (fracMatch != null) {
            val num = fracMatch.groupValues[1].toDoubleOrNull()
            val den = fracMatch.groupValues[2].toDoubleOrNull()
            val unitStr = fracMatch.groupValues[3].lowercase()
            if (num != null && den != null && den > 0) {
                val valGrams = num / den
                val grams = if (unitStr == "mg") valGrams / 1000.0 else valGrams
                if (grams in 0.005..500.0) {
                    return ProductWeight(
                        unitWeightGrams = grams,
                        quantity = 1,
                        totalWeightGrams = grams,
                        confidence = WeightConfidence.High,
                        source = WeightSource.TitleExpression,
                        rawMatchedText = fracMatch.groupValues[0],
                    )
                }
            }
        }

        val singleRegex = Regex("(\\d+(?:\\.\\d+)?)\\s*(?:-|\\s*)(mg|gms|gm|grams|gram|g)\\b", RegexOption.IGNORE_CASE)
        val matches = singleRegex.findAll(title).mapNotNull { match ->
            val amount = match.groupValues[1].toDoubleOrNull() ?: return@mapNotNull null
            val unit = match.groupValues[2].lowercase()
            val grams = if (unit == "mg") amount / 1000.0 else amount
            if (grams in 0.005..500.0) {
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
            // Prefer matches with explicit unit descriptors ('gm', 'gram', 'mg')
            val preferred = matches.lastOrNull { 
                val rawLower = it.first.lowercase()
                rawLower.contains("gm") || rawLower.contains("gram") || rawLower.contains("mg")
            } ?: matches.last()
            return ProductWeight(
                unitWeightGrams = preferred.second,
                quantity = 1,
                totalWeightGrams = preferred.second,
                confidence = WeightConfidence.High,
                source = WeightSource.TitleExpression,
                rawMatchedText = preferred.first,
            )
        }

        return null
    }
}
