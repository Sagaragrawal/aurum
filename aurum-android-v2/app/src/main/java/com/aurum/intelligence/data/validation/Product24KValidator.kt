package com.aurum.intelligence.data.validation
import com.aurum.intelligence.data.db.*
import com.aurum.intelligence.data.engine.*
import com.aurum.intelligence.data.model.*
import com.aurum.intelligence.data.repository.*
import com.aurum.intelligence.data.validation.*

import java.util.Locale
import java.util.regex.Pattern

object Product24KValidator {

    data class ValidationResult(
        val isValid: Boolean,
        val rejectionReason: String? = null,
        val normalizedKarat: Double = 24.0,
        val normalizedPurity: String = "999",
        val normalizedTitle: String = "",
        val normalizedName: String = normalizedTitle,
    )

    private val NON_GOLD_PATTERN = Pattern.compile(
        "(?:silver|chandi|platinum|brass|copper|alloy|iron|steel|wooden|wood|cloth|stone|detecting|testing|dogecoin|casino|soenir|souvenir|replica|plated|imitation|base\\s*metal)",
        Pattern.CASE_INSENSITIVE
    )

    private val PACKAGING_TERMS_PATTERN = Pattern.compile(
        "\\b(velvet|pouch|box|boxes|plate|capsule|stand|frame|holder|case|organizer|organiser)\\b",
        Pattern.CASE_INSENSITIVE
    )

    private val JEWELRY_IDOL_PATTERN = Pattern.compile(
        "(?:necklace|necklaces|chain|chains|earring|earrings|bangle|bangles|bracelet|bracelets|mangalsutra|rakhi|rakhis|nose\\s*pin|kada|kadas|anklet|anklets|locket|lockets|brooch|idol|idols|statue|statues|diya|diyas|pooja\\s*thali|tanmaniya|payal|jhumka|jhumki|tie\\s*clip|clip|stand|holder|organizer|kasulaperu)",
        Pattern.CASE_INSENSITIVE
    )
    private val GENERIC_JEWELRY_PATTERN = Pattern.compile(
        "\\b(jewellery|jewelry)\\b",
        Pattern.CASE_INSENSITIVE
    )

    private val COIN_BAR_PATTERN = Pattern.compile(
        "\\b(coin|coins|bar|bars|ginni|guinea|vedhani|chip|chips|biscuit|biscuits|bullion|ingot|ingots)\\b",
        Pattern.CASE_INSENSITIVE
    )

    private val WEIGHT_PATTERN = Pattern.compile(
        "\\b\\d+(\\.\\d+)?\\s*(g|gm|gms|gram|grams|kilo|kg)\\b",
        Pattern.CASE_INSENSITIVE
    )

    private val NON_24K_INDICATOR = Pattern.compile(
        "(?:22\\s*k|22\\s*kt|22\\s*karat|22\\s*ct|22\\s*carat|22kt|22k|916|18\\s*k|18\\s*kt|18\\s*karat|18kt|18k|750|14\\s*k|14\\s*kt|14\\s*karat|14kt|14k|585|10\\s*k|10kt|10k)",
        Pattern.CASE_INSENSITIVE
    )

    private val IS_24K_INDICATOR = Pattern.compile(
        "\\b(24\\s*(k|kt|karat|carat|ct)|999\\.9|999|995|99\\.9%|99\\.5%)\\b",
        Pattern.CASE_INSENSITIVE
    )

    fun validate(
        name: String?,
        store: String = "",
        karat: Double? = null,
        purity: String? = null,
        price: Double = 0.0,
        grams: Double? = null,
        brand: String? = null,
        canonicalUrl: String? = null,
        retailerId: String? = null,
    ): ValidationResult {
        val trimmedName = (name ?: "").trim()
        if (trimmedName.isBlank()) {
            return ValidationResult(isValid = false, rejectionReason = "Missing product name")
        }

        // 1. Price check
        if (price <= 0.0) {
            return ValidationResult(isValid = false, rejectionReason = "Price <= 0 ($price)")
        }

        // 2. Grams check
        if (grams != null && grams <= 0.0) {
            return ValidationResult(isValid = false, rejectionReason = "Weight <= 0 ($grams)")
        }

        // 3. Reject non-gold and accessories
        val nonGoldRegex = ScraperConfigProvider.get().policy.nonGoldKeywords.takeIf { it.isNotEmpty() }?.let { keywords ->
            Pattern.compile("(?:${keywords.joinToString("|") { Pattern.quote(it.trim()) }})", Pattern.CASE_INSENSITIVE)
        } ?: NON_GOLD_PATTERN

        if (nonGoldRegex.matcher(trimmedName).find()) {
            return ValidationResult(isValid = false, rejectionReason = "Non-gold metal, accessory, or souvenir detected")
        }

        // Exclude false "bar" matches like tie bar, collar bar, jewelry stand, bar necklace, bar earring
        val isNonBullionBar = Pattern.compile(
            "(?:tie\\s*bar|collar\\s*bar|jewelry\\s*stand|display\\s*rack|crossbar|bar\\s*link|bar\\s*pendant|bar\\s*charm|bar\\s*necklace|bar\\s*earring)",
            Pattern.CASE_INSENSITIVE
        ).matcher(trimmedName).find()

        // Check packaging terms: reject if packaging accessory or non-bullion bar
        if (PACKAGING_TERMS_PATTERN.matcher(trimmedName).find() || isNonBullionBar) {
            val has24KOrCoin = IS_24K_INDICATOR.matcher(trimmedName).find() || (COIN_BAR_PATTERN.matcher(trimmedName).find() && !isNonBullionBar)
            if (!has24KOrCoin) {
                return ValidationResult(isValid = false, rejectionReason = "Packaging accessory or non-bullion bar without 24K/coin/bar indicator")
            }
        }

        // 4. Reject ornamental jewelry and idols, preserving only 24K pure gold coin pendants and Vedhani bullion loops
        val nameLower = trimmedName.lowercase(Locale.ROOT)
        val hasPendantWord = nameLower.contains("pendant") || nameLower.contains("pendants")
        val hasRingWord = nameLower.contains("ring") || nameLower.contains("rings")
        val isVedhaniWord = nameLower.contains("vedhani")

        val is24KOrCoin = (IS_24K_INDICATOR.matcher(trimmedName).find() || COIN_BAR_PATTERN.matcher(trimmedName).find()) && !isNonBullionBar
        val isCoinPendant = hasPendantWord && is24KOrCoin
        val isVedhaniRing = (hasRingWord || isVedhaniWord) && (is24KOrCoin || isVedhaniWord)

        val jewelryRegex = ScraperConfigProvider.get().policy.jewelryKeywords.takeIf { it.isNotEmpty() }?.let { keywords ->
            Pattern.compile("(?:${keywords.joinToString("|") { Pattern.quote(it.trim()) }})", Pattern.CASE_INSENSITIVE)
        } ?: JEWELRY_IDOL_PATTERN

        if (jewelryRegex.matcher(trimmedName).find() && !isCoinPendant && !isVedhaniRing) {
            return ValidationResult(isValid = false, rejectionReason = "Ornamental jewelry or idol detected")
        }

        // 5. Explicit non-24K rejection
        if (NON_24K_INDICATOR.matcher(trimmedName).find()) {
            return ValidationResult(isValid = false, rejectionReason = "Non-24K keyword (22K/18K/14K/916) in title")
        }
        if (karat != null && karat < 23.5) {
            return ValidationResult(isValid = false, rejectionReason = "Karat is $karat (< 24K)")
        }

        // 6. Strict Purity & Karat verification (must be >= 995 fineness or 24K)
        val normalizedP = normalizePurity(purity, trimmedName)
            ?: return ValidationResult(isValid = false, rejectionReason = "Purity is below 24K / 995 fineness")

        return ValidationResult(
            isValid = true,
            normalizedKarat = 24.0,
            normalizedPurity = normalizedP,
            normalizedTitle = DatabaseSanitizerEngine.cleanTitle(trimmedName),
        )
    }

    private fun normalizePurity(purity: String?, name: String): String? {
        if (!purity.isNullOrBlank()) {
            val clean = purity.trim().replace("%", "")
            when (clean) {
                "999.9", "9999" -> return "999.9"
                "999", "999.0", "24K", "24 Karat", "24kt", "24Kt" -> return "999"
                "995", "995.0" -> return "995"
            }
            val num = clean.toDoubleOrNull()
            if (num != null) {
                if (num >= 999.9) return "999.9"
                if (num >= 999.0) return "999"
                if (num >= 995.0) return "995"
                if (num >= 99.9) return "999"
                if (num >= 99.5) return "995"
                if (num < 99.5) return null // Below 995
            }
        }

        val nl = name.lowercase(Locale.ROOT)
        return when {
            nl.contains("999.9") || nl.contains("9999") || nl.contains("99.99") -> "999.9"
            nl.contains("999") || nl.contains("99.9") -> "999"
            nl.contains("995") || nl.contains("99.5") -> "995"
            IS_24K_INDICATOR.matcher(name).find() -> "999" // Default verified 24K
            else -> null
        }
    }
}
