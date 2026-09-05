package com.aurum.intelligence.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurum.intelligence.data.BullionBenchmark
import com.aurum.intelligence.data.BullionSourceEntity
import com.aurum.intelligence.data.ProductEntity
import com.aurum.intelligence.data.RefreshActivityLogEntity
import com.aurum.intelligence.data.RefreshRequest
import com.aurum.intelligence.ui.theme.AurumGreen
import com.aurum.intelligence.ui.theme.AurumLine
import com.aurum.intelligence.ui.theme.AurumRed
import kotlinx.coroutines.delay
import java.text.NumberFormat
import java.util.Locale
import androidx.compose.ui.platform.LocalUriHandler
import kotlin.math.abs

@Composable
fun CompactBottomNavigation(
    marketSelected: Boolean,
    browserSelected: Boolean,
    productCount: Int,
    onMarket: () -> Unit,
    onWatchlist: () -> Unit,
    onBrowser: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(AurumLine))
        Row(Modifier.fillMaxWidth().height(62.dp).padding(horizontal = 16.dp, vertical = 5.dp)) {
            BottomNavItem(
                selected = marketSelected,
                label = "Market",
                icon = { Icon(Icons.Filled.Home, contentDescription = "Market", modifier = Modifier.size(21.dp)) },
                onClick = onMarket,
            )
            BottomNavItem(
                selected = !marketSelected && !browserSelected,
                label = "Watchlist",
                badge = productCount,
                icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Watchlist", modifier = Modifier.size(21.dp)) },
                onClick = onWatchlist,
            )
            BottomNavItem(
                selected = browserSelected,
                label = "Browser",
                icon = { Icon(Icons.Outlined.Refresh, contentDescription = "Browser", modifier = Modifier.size(21.dp)) },
                onClick = onBrowser,
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.BottomNavItem(
    selected: Boolean,
    label: String,
    badge: Int? = null,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides color) { icon() }
            badge?.let {
                Text(
                    text = if (it > 999) "999+" else it.toString(),
                    modifier = Modifier.align(Alignment.TopEnd).padding(start = 14.dp).clip(RoundedCornerShape(50)).background(AurumGreen).padding(horizontal = 4.dp),
                    color = Color(0xFF04130C),
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }
        Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
fun ProductWatchlistScreen(
    products: List<ProductEntity>,
    sources: List<BullionSourceEntity>,
    productMessage: String?,
    refreshActivity: List<RefreshActivityLogEntity>,
    model: AurumViewModel,
    modifier: Modifier = Modifier,
    onRefresh: (RefreshRequest) -> Unit,
    onClearRefreshActivity: () -> Unit,
) {
    var addOpen by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var purity by rememberSaveable { mutableStateOf(PurityFilter.K24) }
    var search by rememberSaveable { mutableStateOf("") }
    var minimumGrams by rememberSaveable { mutableStateOf("") }
    var maximumGrams by rememberSaveable { mutableStateOf("") }
    var quickFilter by rememberSaveable { mutableStateOf(QuickFilter.All) }
    var sort by rememberSaveable { mutableStateOf(ProductSort.PricePerGram) }
    var direction by rememberSaveable { mutableStateOf(SortDirection.Ascending) }
    var selectedStores by remember { mutableStateOf(emptySet<String>()) }
    val stores = products.map(ProductEntity::store).distinct().sorted()
    LaunchedEffect(stores) {
        selectedStores = if (selectedStores.isEmpty()) stores.toSet() else selectedStores.intersect(stores.toSet())
    }
    LaunchedEffect(pendingDeleteId) {
        if (pendingDeleteId != null) {
            delay(4_000)
            pendingDeleteId = null
        }
    }
    val benchmark24 = BullionBenchmark.blend(BullionBenchmark.cleanRates(sources.mapNotNull { it.price24 }))
    val benchmark22 = BullionBenchmark.blend(BullionBenchmark.cleanRates(sources.mapNotNull { it.price22 }))
    val query = WatchlistQuery(
        purity = purity,
        search = search,
        minimumGrams = minimumGrams.toDoubleOrNull(),
        maximumGrams = maximumGrams.toDoubleOrNull(),
        stores = selectedStores,
        quickFilter = quickFilter,
        sort = sort,
        direction = direction,
    )
    val base = ProductCalculations.baseFiltered(products, query)
    val counts = ProductCalculations.quickCounts(products, query, benchmark24, benchmark22)
    val visible = ProductCalculations.filteredAndSorted(products, query, benchmark24, benchmark22)
    val editingProduct = products.firstOrNull { it.id == editingId }

    if (addOpen) {
        AddProductDialog(
            error = productMessage,
            onDismiss = { model.clearProductMessage(); addOpen = false },
            onAdd = { url, name ->
                model.addProduct(url, name) { product -> addOpen = false; onRefresh(RefreshRequest.storeRetry(product)) }
            },
        )
    }
    editingProduct?.let { product ->
        EditProductDialog(
            product = product,
            error = productMessage,
            onDismiss = { model.clearProductMessage(); editingId = null },
            onSave = { edits -> model.editProduct(product.id, edits) { editingId = null } },
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("WATCHLIST", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text("Tracked products", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
                IconButton(modifier = Modifier.size(48.dp), onClick = { model.clearProductMessage(); addOpen = true }) {
                    Icon(Icons.Outlined.Add, contentDescription = "Add product")
                }
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Button(onClick = { onRefresh(RefreshRequest.all()) }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("All")
                    }
                }
                item {
                    OutlinedButton(
                        onClick = { onRefresh(RefreshRequest.selection(visible)) },
                        enabled = visible.isNotEmpty(),
                    ) { Text("Selection (${visible.size})") }
                }
                item {
                    val staleCount = visible.count { it.status.lowercase() in setOf("stale", "unverified", "failed", "unavailable") }
                    OutlinedButton(
                        onClick = { onRefresh(RefreshRequest.staleOnly(visible)) },
                        enabled = staleCount > 0,
                    ) { Text("Stale only ($staleCount)") }
                }
            }
        }
        item {
            RefreshActivityPanel(
                logs = refreshActivity,
                onClear = onClearRefreshActivity,
                initiallyExpanded = false,
            )
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                items(PurityFilter.entries) { option ->
                    val count = base.count { product ->
                        when (option) {
                            PurityFilter.K24 -> ProductCalculations.productKarat(product) == 24
                            PurityFilter.K22 -> ProductCalculations.productKarat(product) == 22
                            PurityFilter.Other -> ProductCalculations.productKarat(product) !in setOf(24, 22)
                        }
                    }
                    FilterChip(selected = purity == option, onClick = { purity = option }, label = { Text("${option.label}  $count", maxLines = 1) })
                }
            }
        }
        item {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                label = { Text("Search products") },
                singleLine = true,
            )
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GramField("Min grams", minimumGrams, { minimumGrams = it }, Modifier.weight(1f))
                GramField("Max grams", maximumGrams, { maximumGrams = it }, Modifier.weight(1f))
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                item {
                    FilterChip(
                        selected = selectedStores == stores.toSet(),
                        onClick = { selectedStores = stores.toSet() },
                        label = { Text("All  ${products.size}", maxLines = 1) },
                    )
                }
                items(stores) { store ->
                    FilterChip(
                        selected = store in selectedStores,
                        onClick = {
                            selectedStores = RetailerSelection.toggle(selectedStores, store, stores.toSet())
                        },
                        label = { Text("${storeLabel(store)}  ${products.count { it.store == store }}", maxLines = 1) },
                    )
                }
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                items(QuickFilter.entries) { option ->
                    FilterChip(
                        selected = quickFilter == option,
                        onClick = { quickFilter = option },
                        label = { Text("${option.label}  ${counts.getValue(option)}", maxLines = 1) },
                    )
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                LazyRow(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(ProductSort.entries) { option ->
                        FilterChip(selected = sort == option, onClick = { sort = option }, label = { Text(option.label, maxLines = 1) })
                    }
                }
                IconButton(
                    modifier = Modifier.size(48.dp),
                    onClick = { direction = if (direction == SortDirection.Ascending) SortDirection.Descending else SortDirection.Ascending },
                ) {
                    Text(
                        text = if (direction == SortDirection.Ascending) "↑" else "↓",
                        modifier = Modifier.semantics {
                            contentDescription = if (direction == SortDirection.Ascending) "Sort ascending" else "Sort descending"
                        },
                        fontSize = 24.sp,
                    )
                }
            }
        }
        val availableProducts = visible.filterNot(ProductCalculations::isUnavailable)
        val unavailableProducts = visible.filter(ProductCalculations::isUnavailable)
        items(availableProducts, key = ProductEntity::id) { product ->
            MobileProductCard(
                product = product,
                benchmark24 = benchmark24,
                benchmark22 = benchmark22,
                deletePending = pendingDeleteId == product.id,
                onEdit = { model.clearProductMessage(); editingId = product.id },
                onRetry = { onRefresh(RefreshRequest.storeRetry(product)) },
                onCancelDelete = { pendingDeleteId = null },
                onDelete = {
                    if (pendingDeleteId == product.id) {
                        model.deleteProduct(product.id)
                        pendingDeleteId = null
                    } else {
                        pendingDeleteId = product.id
                    }
                },
            )
        }
        if (quickFilter == QuickFilter.All && unavailableProducts.isNotEmpty()) {
            item { Text("UNAVAILABLE / NOT DELIVERABLE", color = AurumRed, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
        }
        items(unavailableProducts, key = ProductEntity::id) { product ->
            MobileProductCard(
                product = product,
                benchmark24 = benchmark24,
                benchmark22 = benchmark22,
                deletePending = pendingDeleteId == product.id,
                onEdit = { model.clearProductMessage(); editingId = product.id },
                onRetry = { onRefresh(RefreshRequest.storeRetry(product)) },
                onCancelDelete = { pendingDeleteId = null },
                onDelete = {
                    if (pendingDeleteId == product.id) {
                        model.deleteProduct(product.id)
                        pendingDeleteId = null
                    } else pendingDeleteId = product.id
                },
            )
        }
        item {
            Text(
                "${visible.size} of ${products.size} products",
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun GramField(label: String, value: String, onValueChange: (String) -> Unit, modifier: Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
    )
}

@Composable
private fun MobileProductCard(
    product: ProductEntity,
    benchmark24: Double?,
    benchmark22: Double?,
    deletePending: Boolean,
    onEdit: () -> Unit,
    onRetry: () -> Unit,
    onCancelDelete: () -> Unit,
    onDelete: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val effectiveTotal = ProductCalculations.effectiveTotal(product)
    val effectivePerGram = ProductCalculations.effectivePerGram(product)
    val delta = ProductCalculations.benchmarkPercentDelta(product, benchmark24, benchmark22)
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, AurumLine),
    ) {
        Column(Modifier.fillMaxWidth().padding(13.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        ProductCalculations.displayName(product),
                        modifier = Modifier.clickable { uriHandler.openUri(product.canonicalUrl) },
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline,
                        fontSize = 16.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(storeLabel(product.store), product.grams?.let(::formatGrams), ProductCalculations.productKarat(product)?.let { "${it}K" }).joinToString(" | "),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                StatusBadge(product.store, if (ProductCalculations.isUnavailable(product)) "unavailable" else product.status)
            }
            Spacer(Modifier.height(11.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(AurumLine))
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text("EFFECTIVE / GRAM", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp)
                    Text(formatMoney(effectivePerGram), fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
                delta?.let {
                    Text(
                        text = String.format(Locale.US, "%+.2f%%", it),
                        color = if (it < 0) AurumGreen else AurumRed,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(AurumLine))
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProductStat("Effective", formatMoney(effectiveTotal), Modifier.weight(1f))
                ProductStat("Coupon", formatMoney(product.couponPrice), Modifier.weight(1f))
                ProductStat("Price", formatMoney(product.price), Modifier.weight(1f))
            }
            if (deletePending) {
                Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(modifier = Modifier.weight(1f).fillMaxHeight(), onClick = onDelete) {
                        Text("Confirm delete", maxLines = 1, softWrap = false)
                    }
                    TextButton(modifier = Modifier.fillMaxHeight(), onClick = onCancelDelete) {
                        Text("Cancel", maxLines = 1, softWrap = false)
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    IconButton(modifier = Modifier.size(48.dp), onClick = onEdit) { Icon(Icons.Outlined.Edit, contentDescription = "Edit ${product.name}") }
                    TextButton(modifier = Modifier.height(48.dp), onClick = onRetry) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Retry store")
                    }
                    IconButton(modifier = Modifier.size(48.dp), onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = "Delete ${product.name}", tint = AurumRed) }
                }
            }
        }
    }
}

@Composable
private fun ProductStat(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label.uppercase(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 8.sp, maxLines = 1)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun StatusBadge(store: String, status: String) {
    val color = when (status) {
        "live" -> AurumGreen
        "failed", "unavailable" -> AurumRed
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        "${storeLabel(store)} | ${statusLabel(status)}",
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 4.dp),
        color = color,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
    )
}

private fun statusLabel(status: String): String = when (status) {
    "unavailable" -> "NOT DELIVERABLE"
    "unverified" -> "NEEDS VERIFICATION"
    "failed" -> "REFRESH FAILED"
    else -> status.uppercase()
}

@Composable
fun DealRadarPanel(
    products: List<ProductEntity>,
    sources: List<BullionSourceEntity>,
    mode: DealMode,
    percentThreshold: Double,
    rupeesThreshold: Double,
    onModeChange: (DealMode) -> Unit,
    onThresholdChange: (DealMode, Double) -> Unit,
) {
    val configuredThreshold = if (mode == DealMode.Percent) percentThreshold else rupeesThreshold
    var thresholdText by rememberSaveable(mode) { mutableStateOf(configuredThreshold.toString()) }
    val benchmark24 = BullionBenchmark.blend(BullionBenchmark.cleanRates(sources.mapNotNull { it.price24 }))
    val benchmark22 = BullionBenchmark.blend(BullionBenchmark.cleanRates(sources.mapNotNull { it.price22 }))
    val threshold = thresholdText.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
    val deals = ProductCalculations.deals(products, benchmark24, benchmark22, mode, threshold)
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, AurumLine),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text("DEAL RADAR", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("Closest to bullion", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                DealMode.entries.forEach { option ->
                    FilterChip(selected = mode == option, onClick = {
                        onModeChange(option)
                    }, label = { Text(option.label, maxLines = 1) })
                }
                OutlinedTextField(
                    value = thresholdText,
                    onValueChange = { value ->
                        thresholdText = value
                        value.toDoubleOrNull()?.takeIf { it >= 0 }?.let { onThresholdChange(mode, it) }
                    },
                    modifier = Modifier.weight(1f),
                    label = { Text("Threshold") },
                    suffix = { Text(mode.label) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                )
            }
            Text("${deals.size} ${if (deals.size == 1) "deal" else "deals"}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
            if (deals.isEmpty()) {
                Text("No live products match this threshold.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(deals, key = { it.product.id }) { deal -> DealCard(deal, mode) }
                }
            }
        }
    }
}

@Composable
private fun DealCard(deal: DealCandidate, mode: DealMode) {
    val accent = if (deal.isSteal) AurumGreen else MaterialTheme.colorScheme.primary
    Surface(
        modifier = Modifier.width(250.dp),
        shape = RoundedCornerShape(8.dp),
        color = if (deal.isSteal) AurumGreen.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (deal.isSteal) AurumGreen else AurumLine),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(if (deal.isSteal) "STEAL DEAL" else "CLOSE MATCH", color = accent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text(deal.product.name, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "${storeLabel(deal.product.store)} | ${formatGrams(deal.product.grams ?: 0.0)} | ${ProductCalculations.productKarat(deal.product)}K",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                maxLines = 1,
            )
            Spacer(Modifier.height(8.dp))
            Text("${formatMoney(deal.effectivePerGram)} / g", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            val delta = if (mode == DealMode.Percent) {
                String.format(Locale.US, "%+.2f%% vs benchmark", deal.percentDelta)
            } else {
                "${if (deal.rupeesDelta >= 0) "+" else "-"}${formatMoney(abs(deal.rupeesDelta))}/g vs benchmark"
            }
            Text(delta, color = if (deal.isSteal) AurumGreen else AurumRed, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

private fun storeLabel(store: String): String = when (store) {
    "ajio.com" -> "AJIO"
    "amazon.in" -> "Amazon"
    "flipkart.com" -> "Flipkart"
    "myntra.com" -> "Myntra"
    "shopsy.in" -> "Shopsy"
    else -> store.substringBefore('.').replaceFirstChar(Char::uppercase)
}

private fun formatMoney(value: Double?): String = value?.takeIf { it.isFinite() }?.let {
    "Rs ${NumberFormat.getNumberInstance(Locale.forLanguageTag("en-IN")).format(it)}"
} ?: "--"

private fun formatGrams(value: Double): String = "${NumberFormat.getNumberInstance(Locale.US).format(value)}g"