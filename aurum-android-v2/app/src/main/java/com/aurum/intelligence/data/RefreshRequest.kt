package com.aurum.intelligence.data

enum class RefreshScope {
    All,
    Selection,
    StaleOnly,
    StoreRetry,
}

data class RefreshRequest(
    val scope: RefreshScope,
    val productIds: Set<String> = emptySet(),
    val stores: Set<String> = emptySet(),
) {
    fun targetStores(availableStores: Set<String>): Set<String> = when (scope) {
        RefreshScope.All -> availableStores
        RefreshScope.Selection, RefreshScope.StaleOnly, RefreshScope.StoreRetry -> stores.intersect(availableStores)
    }

    companion object {
        private val retryableStatuses = setOf("stale", "unverified", "failed", "unavailable")

        fun all() = RefreshRequest(scope = RefreshScope.All)

        fun selection(visibleProducts: List<ProductEntity>) = fromProducts(RefreshScope.Selection, visibleProducts)

        fun staleOnly(visibleProducts: List<ProductEntity>) = fromProducts(
            RefreshScope.StaleOnly,
            visibleProducts.filter { it.status.lowercase() in retryableStatuses },
        )

        fun storeRetry(product: ProductEntity) = RefreshRequest(
            scope = RefreshScope.StoreRetry,
            productIds = setOf(product.id),
            stores = setOf(product.store),
        )

        private fun fromProducts(scope: RefreshScope, products: List<ProductEntity>) = RefreshRequest(
            scope = scope,
            productIds = products.mapTo(linkedSetOf(), ProductEntity::id),
            stores = products.mapTo(linkedSetOf(), ProductEntity::store),
        )
    }
}