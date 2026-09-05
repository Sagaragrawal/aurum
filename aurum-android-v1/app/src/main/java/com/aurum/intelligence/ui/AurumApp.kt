package com.aurum.intelligence.ui

import com.aurum.intelligence.browser.RetailerWebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aurum.intelligence.AurumApplication
import com.aurum.intelligence.data.ProductEntity
import com.aurum.intelligence.data.ProductEdits
import com.aurum.intelligence.data.RefreshRequest
import com.aurum.intelligence.data.BullionBenchmark
import com.aurum.intelligence.data.BullionRefreshProgress
import com.aurum.intelligence.data.BullionSourceEntity
import com.aurum.intelligence.data.BullionRepository
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

internal enum class AppSection { Market, Watchlist, Browser }

// One terminal action per screen for the top app-bar Refresh icon. Kept as a pure mapping (no
// Compose state) so the Market/Watchlist/Browser contract is unit testable without Compose test
// infrastructure - see TopBarRefreshActionTest.
internal enum class TopBarRefreshAction { BullionOnly, ProductsOnly, Combined }

internal fun topBarRefreshAction(section: AppSection): TopBarRefreshAction = when (section) {
    AppSection.Market -> TopBarRefreshAction.BullionOnly
    AppSection.Watchlist -> TopBarRefreshAction.ProductsOnly
    AppSection.Browser -> TopBarRefreshAction.Combined
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AurumApp(startupWarning: String? = null, onRetryStartup: () -> Unit = {}) {
    val application = LocalContext.current.applicationContext as AurumApplication
    val model: AurumViewModel = viewModel(
        factory = AurumViewModel.Factory(
            application.repository,
            application.settingsRepository,
            application.watchlistRepository,
            application.bullionRepository,
            application.refreshActivityRepository,
        ),
    )
    val products by model.products.collectAsState()
    val settings by model.settings.collectAsState()
    val archiveOperation by model.archiveOperation.collectAsState()
    val productMessage by model.productMessage.collectAsState()
    val bullionSources by model.bullionSources.collectAsState()
    val bullionProgress by model.bullionProgress.collectAsState()
    val bullionHistory by model.bullionHistory.collectAsState()
    val refreshActivity by model.refreshActivity.collectAsState()
    var section by rememberSaveable { mutableStateOf(AppSection.Market) }
    var refreshing by rememberSaveable { mutableStateOf(false) }
    var tanishqRefreshing by rememberSaveable { mutableStateOf(false) }
    var refreshRequest by androidx.compose.runtime.remember { mutableStateOf(RefreshRequest.all()) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var startupProductsPending by rememberSaveable { mutableStateOf(false) }
    var topBarHeightPx by remember { mutableIntStateOf(0) }
    var bottomBarHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val topBarHeight = with(density) { topBarHeightPx.toDp() }
    val bottomBarHeight = with(density) { bottomBarHeightPx.toDp() }
    fun refreshEverything() {
        if (refreshing || tanishqRefreshing) return
        RetailerWebView.clearBrowserData(application) {
            refreshRequest = RefreshRequest.all()
            refreshing = true
        }
        if (!bullionProgress.running && !tanishqRefreshing) {
            model.refreshBullion(null)
            tanishqRefreshing = true
        }
    }
    // Top app-bar refresh is contextual to the visible screen; only the explicit Browser command
    // combines bullion and product refresh into one multi-minute operation. Repeated taps while the
    // relevant refresh is already running are no-ops (guarded below and reflected in topBarRefreshRunning).
    fun topBarRefresh() {
        when (topBarRefreshAction(section)) {
            TopBarRefreshAction.BullionOnly -> if (!bullionProgress.running && !tanishqRefreshing) {
                model.refreshBullion(null)
                tanishqRefreshing = true
            }
            TopBarRefreshAction.ProductsOnly -> if (!refreshing) {
                refreshRequest = RefreshRequest.all()
                refreshing = true
            }
            TopBarRefreshAction.Combined -> refreshEverything()
        }
    }
    val topBarRefreshRunning = when (topBarRefreshAction(section)) {
        TopBarRefreshAction.BullionOnly -> bullionProgress.running || tanishqRefreshing
        TopBarRefreshAction.ProductsOnly -> refreshing
        TopBarRefreshAction.Combined -> refreshing || bullionProgress.running || tanishqRefreshing
    }
    LaunchedEffect(Unit) {
        val persisted = application.settingsRepository.settings.first()
        if (persisted.refreshBullionOnStart) {
            startupProductsPending = persisted.refreshProductsOnStart
            model.refreshBullion(null)
            tanishqRefreshing = true
        } else if (persisted.refreshProductsOnStart) {
            refreshing = true
        }
    }
    if (settingsOpen) {
        SettingsScreen(
            settings = settings,
            archiveOperation = archiveOperation,
            model = model,
            onBack = { settingsOpen = false },
        )
        return
    }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                modifier = Modifier.onGloballyPositioned { topBarHeightPx = it.size.height },
                title = {
                    Column {
                        Text("Aurum", fontWeight = FontWeight.Bold)
                        Text("24K INTELLIGENCE", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                    }
                },
                actions = {
                    IconButton(onClick = ::topBarRefresh, enabled = !topBarRefreshRunning) {
                        val baseDescription = when (section) {
                            AppSection.Market -> "Refresh bullion"
                            AppSection.Watchlist -> "Refresh products"
                            AppSection.Browser -> "Refresh bullion and products"
                        }
                        if (topBarRefreshRunning) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(18.dp).semantics { contentDescription = "$baseDescription: refreshing" },
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(Icons.Outlined.Refresh, contentDescription = baseDescription)
                        }
                    }
                    IconButton(onClick = { settingsOpen = true }) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            Box(Modifier.onGloballyPositioned { bottomBarHeightPx = it.size.height }) {
                CompactBottomNavigation(
                    marketSelected = section == AppSection.Market,
                    browserSelected = section == AppSection.Browser,
                    productCount = products.size,
                    onMarket = { section = AppSection.Market },
                    onWatchlist = { section = AppSection.Watchlist },
                    onBrowser = { section = AppSection.Browser },
                )
            }
        },
    ) { contentPadding ->
        Column(Modifier.padding(contentPadding)) {
            startupWarning?.let { warning ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(warning, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onRetryStartup) { Text("Retry") }
                }
            }
            settings.backgroundRefreshRequestedAt?.let {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("A scheduled refresh is waiting.", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = {
                        model.clearBackgroundRefreshRequest()
                        refreshRequest = RefreshRequest.all()
                        refreshing = true
                    }) { Text("Refresh now") }
                }
            }
            when (section) {
                AppSection.Market -> MarketScreen(
                    sources = bullionSources,
                    products = products,
                    history = bullionHistory,
                    dealMode = DealMode.valueOf(settings.dealMode),
                    dealPercentThreshold = settings.dealPercentThreshold,
                    dealRupeesThreshold = settings.dealRupeesThreshold,
                    onDealModeChange = model::setDealMode,
                    onDealThresholdChange = model::setDealThreshold,
                    progress = bullionProgress,
                    tanishqRefreshing = tanishqRefreshing,
                    onRefresh = { sourceId ->
                        if (sourceId == "tan") {
                            tanishqRefreshing = true
                        } else if (sourceId == null) {
                            model.refreshBullion(null)
                            tanishqRefreshing = true
                        } else {
                            model.refreshBullion(sourceId)
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                AppSection.Watchlist -> ProductWatchlistScreen(
                    products = products,
                    sources = bullionSources,
                    productMessage = productMessage,
                    refreshActivity = refreshActivity,
                    model = model,
                    modifier = Modifier.weight(1f),
                    onRefresh = { request ->
                        refreshRequest = request
                        refreshing = true
                    },
                    onClearRefreshActivity = model::clearRefreshActivity,
                )
                AppSection.Browser -> BrowserDashboard(
                    productRefreshRunning = refreshing,
                    bullionRefreshRunning = tanishqRefreshing || bullionProgress.running,
                    logs = refreshActivity,
                    onRefreshEverything = ::refreshEverything,
                    onClearLogs = model::clearRefreshActivity,
                    showRefreshActivity = !refreshing,
                )
            }
        }
    }
    if (refreshing) {
        Box(
            if (section == AppSection.Browser) {
                Modifier
                    .fillMaxSize()
                    .padding(top = topBarHeight, bottom = bottomBarHeight)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .background(MaterialTheme.colorScheme.background)
            } else {
                Modifier.size(1.dp)
            },
        ) {
            BrowserRefreshScreen(
                mergeEvents = model.mergeEvents,
                request = refreshRequest,
                products = products,
                showBrowser = section == AppSection.Browser,
                pincode = settings.pincode,
                latitude = settings.latitude,
                longitude = settings.longitude,
                logs = refreshActivity,
                onLog = model::logRefreshActivity,
                onClearLogs = model::clearRefreshActivity,
                onSessionStart = model::beginProductRefreshSession,
                onSessionEnd = model::endProductRefreshSession,
                onCatalogueMerged = model::refreshMissingCatalogueProducts,
                onProductRefresh = model::refreshProduct,
                onFinished = { refreshing = false },
            )
        }
    }
    if (tanishqRefreshing) {
        Box(
            if (section == AppSection.Browser && !refreshing) {
                Modifier
                    .fillMaxSize()
                    .padding(top = 64.dp, bottom = 62.dp)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .background(MaterialTheme.colorScheme.background)
            } else {
                Modifier.size(1.dp)
            },
        ) {
            TanishqBrowserScreen(
                showBrowser = section == AppSection.Browser && !refreshing,
                onLog = model::logRefreshActivity,
                onResult = { price24, price22 ->
                    model.recordTanishqRate(price24, price22) {
                        tanishqRefreshing = false
                        if (startupProductsPending) {
                            startupProductsPending = false
                            refreshing = true
                        }
                    }
                },
                onClose = {
                    tanishqRefreshing = false
                    if (startupProductsPending) {
                        startupProductsPending = false
                        refreshing = true
                    }
                },
            )
        }
    }
}

@Composable
private fun BrowserDashboard(
    productRefreshRunning: Boolean,
    bullionRefreshRunning: Boolean,
    logs: List<com.aurum.intelligence.data.RefreshActivityLogEntity>,
    onRefreshEverything: () -> Unit,
    onClearLogs: () -> Unit,
    showRefreshActivity: Boolean,
) {
    val recentStoreEvents = logs.asReversed()
        .filter { it.store in setOf("ajio.com", "amazon.in", "flipkart.com", "myntra.com", "shopsy.in", "tanishq") }
        .filter { it.message.contains("Coverage") || it.message.contains("Existing prices preserved") || it.message.contains("Rendered bullion rate saved") }
        .distinctBy { it.store }
        .take(5)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                Text("BROWSER ACTIVITY", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text("Retailer refresh", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(
                    when {
                        productRefreshRunning && bullionRefreshRunning -> "Products and bullion are refreshing in the background"
                        productRefreshRunning -> "Product collection is running in the background"
                        bullionRefreshRunning -> "Bullion collection is running in the background"
                        else -> "Ready for the next refresh"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item {
            Button(
                onClick = onRefreshEverything,
                enabled = !productRefreshRunning && !bullionRefreshRunning,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Refresh bullion and products") }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BrowserStatusTile("Products", if (productRefreshRunning) "Refreshing" else "Idle", productRefreshRunning, Modifier.weight(1f))
                BrowserStatusTile("Bullion", if (bullionRefreshRunning) "Refreshing" else "Idle", bullionRefreshRunning, Modifier.weight(1f))
            }
        }
        if (recentStoreEvents.isNotEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("LATEST STORE OUTCOMES", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                recentStoreEvents.forEach { event ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        Column(Modifier.fillMaxWidth().padding(10.dp)) {
                            Text(event.store.orEmpty().uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text(event.message, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        if (showRefreshActivity) item {
            RefreshActivityPanel(logs = logs, onClear = onClearLogs, initiallyExpanded = true)
        }
    }
}

@Composable
private fun BrowserStatusTile(label: String, value: String, running: Boolean, modifier: Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(11.dp)) {
            Text(label.uppercase(), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun MarketScreen(
    sources: List<BullionSourceEntity>,
    products: List<ProductEntity>,
    history: List<com.aurum.intelligence.data.BullionHistoryEntity>,
    dealMode: DealMode,
    dealPercentThreshold: Double,
    dealRupeesThreshold: Double,
    onDealModeChange: (DealMode) -> Unit,
    onDealThresholdChange: (DealMode, Double) -> Unit,
    progress: BullionRefreshProgress,
    tanishqRefreshing: Boolean,
    onRefresh: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text("24K MARKET", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("Gold, without the noise.", fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
                    Text("Live bullion benchmarks and product prices in one clean per-gram view.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { onRefresh(null) }, enabled = !progress.running) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "Refresh bullion sources")
                }
            }
            val combinedRefresh = tanishqRefreshing && progress.total == sources.size - 1
            val total = if (combinedRefresh) progress.total + 1 else if (tanishqRefreshing) 1 else progress.total
            val checked = progress.checked
            if (progress.running || tanishqRefreshing) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { if (total > 0) checked.toFloat() / total else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${checked}/${total} checked${progress.current?.let { " | $it" }.orEmpty()}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                progress.note?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        item { BenchmarkCard(sources) }
        item { BullionTrendCard(history) }
        items(sources, key = { it.id }) { source ->
            BullionSourceCard(source, progress.running) { onRefresh(source.id) }
        }
        item {
            DealRadarPanel(
                products = products,
                sources = sources,
                mode = dealMode,
                percentThreshold = dealPercentThreshold,
                rupeesThreshold = dealRupeesThreshold,
                onModeChange = onDealModeChange,
                onThresholdChange = onDealThresholdChange,
            )
        }
    }
}

@Composable
private fun BenchmarkCard(sources: List<BullionSourceEntity>) {
    val clean24 = BullionBenchmark.cleanRates(sources.mapNotNull { it.price24 })
    val clean22 = BullionBenchmark.cleanRates(sources.mapNotNull { it.price22 })
    val benchmark24 = BullionBenchmark.blend(clean24)
    val benchmark22 = BullionBenchmark.blend(clean22)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Text("BLENDED BENCHMARK", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text("24K  ${formatRate(benchmark24)}", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("22K  ${formatRate(benchmark22)}", fontSize = 20.sp, color = MaterialTheme.colorScheme.secondary)
            Text(benchmarkRange(clean24, "24K"), fontSize = 12.sp)
            Text(benchmarkRange(clean22, "22K"), fontSize = 12.sp)
            Text("Median-cleaned average | stale retained values remain visible", fontSize = 12.sp)
        }
    }
}

@Composable
private fun BullionSourceCard(source: BullionSourceEntity, refreshRunning: Boolean, onRefresh: () -> Unit) {
    Card(shape = RoundedCornerShape(8.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(source.source, fontWeight = FontWeight.SemiBold)
                    Text(source.status.uppercase(), fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onRefresh, enabled = !refreshRunning) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "Refresh ${source.source}")
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("24K  ${formatRate(source.price24)}")
                Text("22K  ${formatRate(source.price22)}")
            }
            Text(
                if (source.transport == BullionRepository.TRANSPORT_BROWSER_REQUIRED) "Browser rendering required" else "Direct Android HTTP/API",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (source.price22Derived) Text("22K derived from 24K", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            source.error?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
        }
    }
}

private fun formatRate(value: Double?): String = value?.let {
    val formatter = NumberFormat.getNumberInstance(Locale.forLanguageTag("en-IN")).apply {
        maximumFractionDigits = 0
        minimumFractionDigits = 0
    }
    "Rs ${formatter.format(it)} / g"
} ?: "Unavailable"

private fun benchmarkRange(values: List<Double>, karat: String): String = if (values.isEmpty()) {
    "$karat waiting for source data"
} else {
    "$karat ${formatRate(values.minOrNull())} low | ${formatRate(values.maxOrNull())} high | ${values.size} sources"
}

@Composable
fun AddProductDialog(
    error: String?,
    onDismiss: () -> Unit,
    onAdd: (String, String) -> Unit,
) {
    var url by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add product") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(url, { url = it }, label = { Text("HTTPS product URL") }, singleLine = true)
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { Button(onClick = { onAdd(url, name) }) { Text("Add and retry") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun EditProductDialog(
    product: ProductEntity,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (ProductEdits) -> Unit,
) {
    var name by rememberSaveable(product.id) { mutableStateOf(product.name) }
    var brand by rememberSaveable(product.id) { mutableStateOf(product.brand.orEmpty()) }
    var grams by rememberSaveable(product.id) { mutableStateOf(product.grams?.toString().orEmpty()) }
    var karat by rememberSaveable(product.id) { mutableStateOf(product.karat?.toString().orEmpty()) }
    var purity by rememberSaveable(product.id) { mutableStateOf(product.purity.orEmpty()) }
    var price by rememberSaveable(product.id) { mutableStateOf(product.price.toString()) }
    var couponPrice by rememberSaveable(product.id) { mutableStateOf(product.couponPrice?.toString().orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit product") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true) }
                item { OutlinedTextField(brand, { brand = it }, label = { Text("Brand") }, singleLine = true) }
                item { OutlinedTextField(grams, { grams = it }, label = { Text("Grams") }, singleLine = true) }
                item { OutlinedTextField(karat, { karat = it }, label = { Text("Karat") }, singleLine = true) }
                item { OutlinedTextField(purity, { purity = it }, label = { Text("Purity") }, singleLine = true) }
                item { OutlinedTextField(price, { price = it }, label = { Text("Price") }, singleLine = true) }
                item { OutlinedTextField(couponPrice, { couponPrice = it }, label = { Text("Coupon price") }, singleLine = true) }
                error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(
                    ProductEdits(
                        name = name,
                        brand = brand,
                        grams = grams.toDoubleOrNull(),
                        karat = karat.toDoubleOrNull(),
                        purity = purity,
                        price = price.toDoubleOrNull() ?: -1.0,
                        couponPrice = couponPrice.takeIf(String::isNotBlank)?.toDoubleOrNull() ?: if (couponPrice.isBlank()) null else -1.0,
                    ),
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SectionCard(kicker: String, title: String, content: @Composable () -> Unit) {
    Card(shape = RoundedCornerShape(8.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(kicker, color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}