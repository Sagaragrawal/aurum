package com.aurum.intelligence.browser

object MyntraReadiness {
    private val itemCount = Regex("\\b[1-9]\\d*\\s+items\\b", RegexOption.IGNORE_CASE)

    fun isReady(host: String, path: String, readyState: String, bodyText: String): Boolean =
        (host == "myntra.com" || host.endsWith(".myntra.com")) &&
            Regex("^/gold-coin/?$").matches(path) &&
            readyState != "loading" &&
            bodyText.contains("gold coin", ignoreCase = true) &&
            itemCount.containsMatchIn(bodyText)
}