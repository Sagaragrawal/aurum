package com.aurum.intelligence.data

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
        "\\b(silver|chandi|platinum|brass|copper|alloy|iron|steel|wooden|wood|velvet|pouch|cloth|stone|detecting|testing|box|boxes|plate|capsule|stand|frame|holder|case|organizer|organiser|dogecoin|casino|soenir|souvenir|replica|plated)\\b",
        Pattern.CASE_INSENSITIVE
    )

    private val JEWELRY_IDOL_PATTERN = Pattern.compile(
        "\\b(ring|rings|necklace|necklaces|chain|chains|earring|earrings|bangle|bangles|pendant|pendants|bracelet|bracelets|mangalsutra|rakhi|rakhis|nose\\s*pin|kada|kadas|anklet|anklets|locket|lockets|brooch|idol|idols|murti|murtis|statue|statues|diya|diyas|pooja\\s*thali|tanmaniya|payal|jhumka|jhumki)\\b",
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
        "\\b(22\\s*(k|kt|karat|carat|ct)|18\\s*(k|kt|karat|carat|ct)|14\\s*(k|kt|karat|carat|ct)|916|750|585)\\b",
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
        if (NON_GOLD_PATTERN.matcher(trimmedName).find()) {
            return ValidationResult(isValid = false, rejectionReason = "Non-gold metal, accessory, or souvenir detected")
        }

        // 4. Reject jewelry and idols
        if (JEWELRY_IDOL_PATTERN.matcher(trimmedName).find()) {
            return ValidationResult(isValid = false, rejectionReason = "Jewelry, ornament, or idol detected")
        }
        if (GENERIC_JEWELRY_PATTERN.matcher(trimmedName).find() && !COIN_BAR_PATTERN.matcher(trimmedName).find()) {
            return ValidationResult(isValid = false, rejectionReason = "Generic jewelry without coin/bar indicator detected")
        }

        // 5. Must be coin or bar or bullion
        val hasCoinOrBarWord = COIN_BAR_PATTERN.matcher(trimmedName).find()
        if (!hasCoinOrBarWord) {
            val hasWeight = WEIGHT_PATTERN.matcher(trimmedName).find()
            val has24K = IS_24K_INDICATOR.matcher(trimmedName).find()
            if (!hasWeight || !has24K) {
                return ValidationResult(isValid = false, rejectionReason = "Not a gold coin or bar")
            }
        }

        // 6. Non-24K explicit rejection
        if (NON_24K_INDICATOR.matcher(trimmedName).find()) {
            return ValidationResult(isValid = false, rejectionReason = "Non-24K keyword (22K/18K/14K/916) in title")
        }
        if (karat != null && karat < 24.0) {
            return ValidationResult(isValid = false, rejectionReason = "Karat is $karat (< 24K)")
        }

        // 7. Karat normalization
        val normalizedK = when {
            karat == 24.0 -> 24.0
            IS_24K_INDICATOR.matcher(trimmedName).find() -> 24.0
            else -> return ValidationResult(isValid = false, rejectionReason = "Karat is unknown/unverified")
        }

        // 8. Purity normalization & verification (must be >= 995)
        val normalizedP = normalizePurity(purity, trimmedName)
            ?: return ValidationResult(isValid = false, rejectionReason = "Purity is unknown/unverified (< 995)")

        if (normalizedP != "995" && normalizedP != "999" && normalizedP != "999.9") {
            return ValidationResult(isValid = false, rejectionReason = "Purity $normalizedP is below 995")
        }

        return ValidationResult(
            isValid = true,
            normalizedKarat = normalizedK,
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
