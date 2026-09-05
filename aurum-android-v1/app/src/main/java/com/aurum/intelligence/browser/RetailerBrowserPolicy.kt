package com.aurum.intelligence.browser

object RetailerBrowserPolicy {
    fun usesNativeUserAgent(retailer: Retailer?): Boolean = retailer == Retailer.Ajio
}