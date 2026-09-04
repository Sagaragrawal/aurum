package com.aurum.intelligence.ui

import com.aurum.intelligence.data.ProductEntity
import com.aurum.intelligence.data.ProductAvailability
import kotlin.math.abs

enum class PurityFilter(val label: String) { K24("24K"), K22("22K"), Other("Other") }

enum class QuickFilter(val label: String) {
    All("All"),
    BelowBullion("Below bullion"),
    Live("Live"),
    Stale("Stale"),
    Unverified("Unverified"),
    Failed("Failed"),
    Unavailable("Unavailable"),
    NotLive("Not live"),
}

enum class ProductSort(val label: String) {
    PricePerGram("Price/g"),
    Weight("Weight"),
    Name("Name"),
    Price("Price"),
    CouponPerGram("Coupon/g"),
    VsBullion("Vs bullion"),
    Store("Store"),
}

enum class SortDirection { Ascending, Descending }

enum class DealMode(val label: String) { Percent("%"), RupeesPerGram("Rs/g") }

data class WatchlistQuery(
    val purity: PurityFilter = PurityFilter.K24,
    val search: String = "",
    val minimumGrams: Double? = null,
    val maximumGrams: Double? = null,
    val stores: Set<String> = emptySet(),
    val quickFilter: QuickFilter = QuickFilter.All,
    val sort: ProductSort = ProductSort.PricePerGram,
    val direction: SortDirection = SortDirection.Ascending,
)

data class DealCandidate(
    val product: ProductEntity,
    val effectiveTotal: Double,
    val effectivePerGram: Double,
    val benchmarkPerGram: Double,
    val rupeesDelta: Double,
    val percentDelta: Double,
) {
    val isSteal: Boolean get() = rupeesDelta < 0
}

object ProductCalculations {
    // Single authoritative "trustworthy right now" window, shared by the Live quick filter and Deal
    // Radar so both mean the same thing: a live-priced observation older than this is not shown as
    // currently live. Chosen to match the existing Deal Radar window rather than introduce a second one.
    const val LIVE_FRESHNESS_MILLIS = 24 * 60 * 60 * 1_000L

    fun isUnavailable(product: ProductEntity): Boolean =
        product.status == "unavailable" || ProductAvailability.isUnavailableName(product.name)

    fun displayName(product: ProductEntity): String = ProductAvailability.displayName(product.name)

    // status=="live" alone does not mean recently live: it persists until the next merge. This is the
    // one place that combines status + availability + price validity + freshness into "recently live".
    fun isRecentlyLive(product: ProductEntity, nowMillis: Long = System.currentTimeMillis()): Boolean =
        product.status == "live" &&
            !isUnavailable(product) &&
            product.price.isFinite() && product.price > 0 &&
            product.lastLiveAt in (nowMillis - LIVE_FRESHNESS_MILLIS)..nowMillis

    fun effectiveTotal(product: ProductEntity): Double =
        product.couponPrice?.takeIf { it > 0 && it < product.price } ?: product.price

    fun effectivePerGram(product: ProductEntity): Double? =
        product.grams?.takeIf { it > 0 }?.let { effectiveTotal(product) / it }

    fun couponPerGram(product: ProductEntity): Double? =
        product.couponPrice?.takeIf { it > 0 }?.let { coupon ->
            product.grams?.takeIf { it > 0 }?.let { coupon / it }
        }

    fun benchmarkFor(product: ProductEntity, benchmark24: Double?, benchmark22: Double?): Double? = when (productKarat(product)) {
        24 -> benchmark24
        22 -> benchmark22
        else -> null
    }?.takeIf { it > 0 }

    fun benchmarkPercentDelta(product: ProductEntity, benchmark24: Double?, benchmark22: Double?): Double? {
        val perGram = effectivePerGram(product) ?: return null
        val benchmark = benchmarkFor(product, benchmark24, benchmark22) ?: return null
        return ((perGram - benchmark) / benchmark) * 100
    }

    fun baseFiltered(products: List<ProductEntity>, query: WatchlistQuery): List<ProductEntity> {
        val needle = query.search.trim().lowercase()
        return products.filter { product ->
            (query.stores.isEmpty() || product.store in query.stores) &&
                (needle.isEmpty() || listOfNotNull(
                    displayName(product),
                    product.brand,
                    product.store,
                    product.grams?.toString(),
                    product.purity,
                ).joinToString(" ").lowercase().contains(needle)) &&
                (query.minimumGrams == null || (product.grams ?: 0.0) >= query.minimumGrams) &&
                (query.maximumGrams == null || (product.grams ?: 0.0) <= query.maximumGrams)
        }
    }

    fun quickCounts(
        products: List<ProductEntity>,
        query: WatchlistQuery,
        benchmark24: Double?,
        benchmark22: Double?,
        nowMillis: Long = System.currentTimeMillis(),
    ): Map<QuickFilter, Int> {
        val base = baseFiltered(products, query)
        return QuickFilter.entries.associateWith { filter ->
            base.count { matchesQuickFilter(it, filter, benchmark24, benchmark22, nowMillis) }
        }
    }

    fun filteredAndSorted(
        products: List<ProductEntity>,
        query: WatchlistQuery,
        benchmark24: Double?,
        benchmark22: Double?,
        nowMillis: Long = System.currentTimeMillis(),
    ): List<ProductEntity> = baseFiltered(products, query)
        .asSequence()
        .filter { matchesQuickFilter(it, query.quickFilter, benchmark24, benchmark22, nowMillis) }
        .filter { query.quickFilter == QuickFilter.BelowBullion || matchesPurity(it, query.purity) }
        .sortedWith(productComparator(query.sort, query.direction, benchmark24, benchmark22))
        .toList()

    fun deals(
        products: List<ProductEntity>,
        benchmark24: Double?,
        benchmark22: Double?,
        mode: DealMode,
        threshold: Double,
        limit: Int = 6,
        nowMillis: Long = System.currentTimeMillis(),
    ): List<DealCandidate> = products.asSequence()
        .filter { product ->
            val benchmark = benchmarkFor(product, benchmark24, benchmark22)
            isDealEligible(product, benchmark, nowMillis)
        }
        .mapNotNull { product ->
            val effectivePerGram = effectivePerGram(product) ?: return@mapNotNull null
            val benchmark = benchmarkFor(product, benchmark24, benchmark22) ?: return@mapNotNull null
            val rupeesDelta = effectivePerGram - benchmark
            DealCandidate(
                product = product,
                effectiveTotal = effectiveTotal(product),
                effectivePerGram = effectivePerGram,
                benchmarkPerGram = benchmark,
                rupeesDelta = rupeesDelta,
                percentDelta = (rupeesDelta / benchmark) * 100,
            )
        }
        .filter { candidate ->
            val delta = if (mode == DealMode.Percent) candidate.percentDelta else candidate.rupeesDelta
            abs(delta) <= threshold.coerceAtLeast(0.0)
        }
        .sortedWith(compareBy<DealCandidate> {
            abs(if (mode == DealMode.Percent) it.percentDelta else it.rupeesDelta)
        }.thenBy { it.product.id })
        .take(limit.coerceAtLeast(0))
        .toList()

    fun isDealEligible(product: ProductEntity, benchmarkPerGram: Double?, nowMillis: Long): Boolean {
        if (!isRecentlyLive(product, nowMillis) || productKarat(product) !in setOf(24, 22)) return false
        if (benchmarkPerGram == null || !benchmarkPerGram.isFinite() || benchmarkPerGram <= 0) return false
        val perGram = effectivePerGram(product) ?: return false
        return perGram.isFinite() && perGram >= benchmarkPerGram * MIN_PLAUSIBLE_DEAL_RATIO
    }

    fun productKarat(product: ProductEntity): Int? = product.karat?.toInt()
        ?: product.purity?.let { purity ->
            when {
                Regex("(?:^|\\D)24\\s*[kK]?").containsMatchIn(purity) -> 24
                Regex("(?:^|\\D)22\\s*[kK]?").containsMatchIn(purity) -> 22
                else -> null
            }
        }

    private fun matchesPurity(product: ProductEntity, purity: PurityFilter): Boolean = when (purity) {
        PurityFilter.K24 -> productKarat(product) == 24
        PurityFilter.K22 -> productKarat(product) == 22
        PurityFilter.Other -> productKarat(product) !in setOf(24, 22)
    }

    private fun matchesQuickFilter(
        product: ProductEntity,
        filter: QuickFilter,
        benchmark24: Double?,
        benchmark22: Double?,
        nowMillis: Long,
    ): Boolean = when (filter) {
        QuickFilter.All -> true
        QuickFilter.BelowBullion -> isRecentlyLive(product, nowMillis) && benchmarkPercentDelta(product, benchmark24, benchmark22)?.let { it < 0 } == true
        QuickFilter.Live -> isRecentlyLive(product, nowMillis)
        QuickFilter.Stale -> product.status == "stale"
        QuickFilter.Unverified -> product.status == "unverified"
        QuickFilter.Failed -> product.status == "failed"
        QuickFilter.Unavailable -> isUnavailable(product)
        QuickFilter.NotLive -> !isRecentlyLive(product, nowMillis) && !isUnavailable(product)
    }

    private fun productComparator(
        sort: ProductSort,
        direction: SortDirection,
        benchmark24: Double?,
        benchmark22: Double?,
    ): Comparator<ProductEntity> = Comparator { first, second ->
        val liveComparison = liveRank(first).compareTo(liveRank(second))
        if (liveComparison != 0) return@Comparator liveComparison
        val comparison = compareSortValues(
            sortValue(first, sort, benchmark24, benchmark22),
            sortValue(second, sort, benchmark24, benchmark22),
        )
        val directed = if (direction == SortDirection.Descending) -comparison else comparison
        if (directed != 0) directed else first.id.compareTo(second.id)
    }

    private fun liveRank(product: ProductEntity): Int = when {
        isUnavailable(product) -> 2
        product.status == "live" -> 0
        else -> 1
    }

    private fun sortValue(product: ProductEntity, sort: ProductSort, benchmark24: Double?, benchmark22: Double?): Any? = when (sort) {
        ProductSort.PricePerGram -> product.grams?.takeIf { it > 0 }?.let { product.price / it }
        ProductSort.Weight -> product.grams ?: 0.0
        ProductSort.Name -> displayName(product).lowercase()
        ProductSort.Price -> product.price
        ProductSort.CouponPerGram -> couponPerGram(product)
        ProductSort.VsBullion -> benchmarkPercentDelta(product, benchmark24, benchmark22)
        ProductSort.Store -> product.store.lowercase()
    }

    private fun compareSortValues(first: Any?, second: Any?): Int {
        if (first == null && second == null) return 0
        if (first == null) return 1
        if (second == null) return -1
        return when {
            first is String && second is String -> first.compareTo(second)
            first is Number && second is Number -> first.toDouble().compareTo(second.toDouble())
            else -> first.toString().compareTo(second.toString())
        }
    }

    private const val MIN_PLAUSIBLE_DEAL_RATIO = 0.55
}