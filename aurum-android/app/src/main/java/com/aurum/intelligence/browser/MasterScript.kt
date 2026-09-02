package com.aurum.intelligence.browser

enum class Retailer {
    Ajio,
    Amazon,
    Flipkart,
    Myntra,
}

data class MasterScript(
    val retailer: Retailer,
    val assetName: String,
    val urls: List<String>,
    val productBinding: String,
    val catalogueBinding: String,
    val incompleteBinding: String,
    val requiresRunnerFlag: Boolean,
    val hardTimeoutMillis: Long,
)

object MasterScripts {
    val all = listOf(
        MasterScript(
            retailer = Retailer.Ajio,
            assetName = "manual_js/ajio_gold_master.js",
            urls = listOf(
                "https://www.ajio.com/women/c/8303?query=%3Arelevance%3Averticalmetalpurity%3A24+Karat%3Averticalmetalpurity%3A24+Karat+%28995%29%3Averticalmetalpurity%3A24+Karat+%28999%29%3Averticalmetalpurity%3A24+Kt%3Averticalmetalpurity%3A24+Kt+%28995%29%3Averticalmetalpurity%3A24+Kt+%28999%29%3Averticalmetalpurity%3A24+Kt+%28999.9%29%3Averticalmetalpurity%3A999%3Averticalmetalpurity%3A22+Kt+%28916%29",
                "https://www.ajio.com/s/jewellery-176606?query=%3Arelevance%3Averticalmetalpurity%3A24+Karat%3Averticalmetalpurity%3A24+Karat+%28995%29%3Averticalmetalpurity%3A24+Karat+%28999%29%3Averticalmetalpurity%3A24+Kt%3Averticalmetalpurity%3A24+Kt+%28995%29%3Averticalmetalpurity%3A24+Kt+%28999%29%3Averticalmetalpurity%3A24+Kt+%28999.9%29%3Averticalmetalpurity%3A999%3Averticalmetalpurity%3A22+Kt",
                "https://www.ajio.com/s/girls-169379?query=%3Arelevance%3Averticalmetalpurity%3A24+Karat%3Averticalmetalpurity%3A24+Karat+%28995%29%3Averticalmetalpurity%3A24+Karat+%28999%29%3Averticalmetalpurity%3A24+Kt%3Averticalmetalpurity%3A24+Kt+%28995%29%3Averticalmetalpurity%3A24+Kt+%28999%29%3Averticalmetalpurity%3A24+Kt+%28999.9%29%3Averticalmetalpurity%3A999%3Averticalmetalpurity%3A22+Kt",
                "https://www.ajio.com/s/boys-169373?query=%3Arelevance%3Averticalmetalpurity%3A24+Karat%3Averticalmetalpurity%3A24+Karat+%28995%29%3Averticalmetalpurity%3A24+Karat+%28999%29%3Averticalmetalpurity%3A24+Kt%3Averticalmetalpurity%3A24+Kt+%28995%29%3Averticalmetalpurity%3A24+Kt+%28999%29%3Averticalmetalpurity%3A24+Kt+%28999.9%29%3Averticalmetalpurity%3A999%3Averticalmetalpurity%3A22+Kt",
            ),
            productBinding = "ajioGold",
            catalogueBinding = "ajioAllSearchResults",
            incompleteBinding = "ajioIncomplete",
            requiresRunnerFlag = false,
            hardTimeoutMillis = 4 * 60_000L,
        ),
        MasterScript(
            retailer = Retailer.Amazon,
            assetName = "manual_js/amazon_gold_master_v14_3_final.js",
            urls = listOf("https://www.amazon.in/s?i=jewelry&rh=n%3A2908910031%2Cp_n_material_two_browse-bin%3A2160347031&s=popularity-rank&dc&fs=true&rnid=2160329031&xpid=ZrCUqOwcv7FyR&ref=sr_pg_1"),
            productBinding = "amazonGold",
            catalogueBinding = "amazonCatalogue",
            incompleteBinding = "amazonIncomplete",
            requiresRunnerFlag = true,
            hardTimeoutMillis = 8 * 60_000L,
        ),
        MasterScript(
            retailer = Retailer.Flipkart,
            assetName = "manual_js/flipkart_gold_master_final.js",
            urls = listOf("https://www.flipkart.com/gold-silver-coins/pr?sid=mcr%2C73x%2Cydh&marketplace=FLIPKART&p%5B%5D=facets.material%255B%255D%3DYellow%2BGold&p%5B%5D=facets.material%255B%255D%3DGold"),
            productBinding = "flipkartGold",
            catalogueBinding = "flipkartProducts",
            incompleteBinding = "flipkartIncomplete",
            requiresRunnerFlag = true,
            hardTimeoutMillis = 8 * 60_000L,
        ),
        MasterScript(
            retailer = Retailer.Myntra,
            assetName = "manual_js/myntra_gold_master_v7_final.js",
            urls = listOf("https://www.myntra.com/gold-coin"),
            productBinding = "myntraGold",
            catalogueBinding = "myntraProducts",
            incompleteBinding = "myntraIncomplete",
            requiresRunnerFlag = true,
            hardTimeoutMillis = 4 * 60_000L,
        ),
    )
}