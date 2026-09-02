package com.aurum.intelligence.browser

sealed interface RetailerReadiness {
    data object Ready : RetailerReadiness
    data object Waiting : RetailerReadiness
    data object Timeout : RetailerReadiness
    data class Blocked(val reason: String) : RetailerReadiness
}

object AjioReadiness {
    fun classify(host: String, path: String, readyState: String, bodyText: String): RetailerReadiness {
        val blockReason = when {
            bodyText.contains("access denied", ignoreCase = true) -> "access_denied"
            bodyText.contains("request blocked", ignoreCase = true) ||
                bodyText.contains("blocked due to security reasons", ignoreCase = true) -> "security_block"
            bodyText.contains("captcha", ignoreCase = true) -> "captcha"
            bodyText.contains("you don't have permission", ignoreCase = true) -> "permission_denied"
            else -> null
        }
        if (blockReason != null) return RetailerReadiness.Blocked(blockReason)
        if (readyState != "complete" || !(host == "ajio.com" || host.endsWith(".ajio.com"))) {
            return RetailerReadiness.Waiting
        }
        return if (readyState == "complete" && bodyText.length >= 120 &&
            Regex("^/s/[^/?#]+|/c/[0-9]+").containsMatchIn(path)
        ) {
            RetailerReadiness.Ready
        } else {
            RetailerReadiness.Waiting
        }
    }
}