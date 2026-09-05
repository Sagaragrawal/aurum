package com.aurum.intelligence.ui

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aurum.intelligence.data.BridgeMergeEvent
import com.aurum.intelligence.data.BridgeRepository
import com.aurum.intelligence.data.BullionRefreshProgress
import com.aurum.intelligence.data.BullionRepository
import com.aurum.intelligence.data.BullionSourceEntity
import com.aurum.intelligence.data.BullionRates
import com.aurum.intelligence.data.BullionHistoryEntity
import com.aurum.intelligence.data.AppSettings
import com.aurum.intelligence.data.AppSettingsRepository
import com.aurum.intelligence.data.ProductEntity
import com.aurum.intelligence.data.MissingCatalogueProductResult
import com.aurum.intelligence.data.ProductEdits
import com.aurum.intelligence.data.RefreshActivityLogEntity
import com.aurum.intelligence.data.RefreshActivityRepository
import com.aurum.intelligence.data.RefreshLogSeverity
import com.aurum.intelligence.data.ThemeChoice
import com.aurum.intelligence.data.WatchlistRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface ArchiveOperationState {
    data object Idle : ArchiveOperationState
    data class Running(val message: String) : ArchiveOperationState
    data class Complete(val message: String) : ArchiveOperationState
    data class Failed(val message: String) : ArchiveOperationState
}

class AurumViewModel(
    private val repository: BridgeRepository,
    private val settingsRepository: AppSettingsRepository,
    private val watchlistRepository: WatchlistRepository,
    private val bullionRepository: BullionRepository,
    private val refreshActivityRepository: RefreshActivityRepository,
) : ViewModel() {
    val products: StateFlow<List<ProductEntity>> = repository.products.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )
    val mergeEvents = repository.mergeEvents
    val bullionSources: StateFlow<List<BullionSourceEntity>> = bullionRepository.sources.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )
    val bullionProgress: StateFlow<BullionRefreshProgress> = bullionRepository.progress
    val bullionHistory: StateFlow<List<BullionHistoryEntity>> = bullionRepository.history.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )
    val refreshActivity: StateFlow<List<RefreshActivityLogEntity>> = refreshActivityRepository.logs.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )
    val settings: StateFlow<AppSettings> = settingsRepository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppSettings(),
    )
    private val mutableArchiveOperation = MutableStateFlow<ArchiveOperationState>(ArchiveOperationState.Idle)
    val archiveOperation = mutableArchiveOperation.asStateFlow()
    private val mutableProductMessage = MutableStateFlow<String?>(null)
    val productMessage = mutableProductMessage.asStateFlow()

    fun setTheme(theme: ThemeChoice) = viewModelScope.launch { settingsRepository.setTheme(theme) }

    fun setLocation(pincode: String, address: String, onComplete: (String?) -> Unit = {}) = viewModelScope.launch {
        val failure = runCatching { settingsRepository.setLocation(pincode, address) }.exceptionOrNull()
        if (failure != null) mutableProductMessage.value = failure.message ?: "Unable to save location"
        onComplete(failure?.message)
    }

    fun setRefreshBullionOnStart(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setRefreshBullionOnStart(enabled)
    }

    fun setRefreshProductsOnStart(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setRefreshProductsOnStart(enabled)
    }

    fun setDealMode(mode: DealMode) = viewModelScope.launch {
        settingsRepository.setDealMode(mode.name)
    }

    fun setDealThreshold(mode: DealMode, threshold: Double) = viewModelScope.launch {
        settingsRepository.setDealThreshold(mode.name, threshold)
    }

    fun setBackgroundRefreshEnabled(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setBackgroundRefreshEnabled(enabled)
    }

    fun setRefreshIntervalMinutes(minutes: Int) = viewModelScope.launch {
        settingsRepository.setRefreshIntervalMinutes(minutes)
    }

    fun clearBackgroundRefreshRequest() = viewModelScope.launch {
        settingsRepository.clearBackgroundRefreshRequest()
    }

    fun addProduct(url: String, name: String, onSuccess: (ProductEntity) -> Unit) = viewModelScope.launch {
        runCatching { watchlistRepository.addProduct(url, name) }
            .onSuccess { product ->
                mutableProductMessage.value = null
                onSuccess(product)
            }
            .onFailure { mutableProductMessage.value = it.message ?: "Unable to add product" }
    }

    fun editProduct(id: String, edits: ProductEdits, onSuccess: () -> Unit) = viewModelScope.launch {
        runCatching { watchlistRepository.editProduct(id, edits) }
            .onSuccess {
                mutableProductMessage.value = null
                onSuccess()
            }
            .onFailure { mutableProductMessage.value = it.message ?: "Unable to save product" }
    }

    fun deleteProduct(id: String) = viewModelScope.launch {
        runCatching { watchlistRepository.deleteProduct(id) }
            .onFailure { mutableProductMessage.value = it.message ?: "Unable to delete product" }
    }

    fun clearProductMessage() {
        mutableProductMessage.value = null
    }

    fun logRefreshActivity(severity: RefreshLogSeverity, store: String?, message: String) = viewModelScope.launch {
        refreshActivityRepository.log(severity, store, message)
    }

    fun clearRefreshActivity() = viewModelScope.launch {
        refreshActivityRepository.clear()
    }

    suspend fun beginProductRefreshSession(sessionId: String, stores: Set<String>, productIds: Set<String>) {
        repository.beginRefreshSession(sessionId, stores, productIds)
    }

    // Non-suspend and non-launched: BridgeRepository.endRefreshSession is a plain synchronized
    // in-memory removal with no suspension, so this call is synchronous all the way down. When
    // this function returns, the session is guaranteed to no longer be authorized - no dependency
    // on a coroutine scope (viewModelScope or a Compose-owned scope) that could be cancelled.
    fun endProductRefreshSession(sessionId: String) {
        repository.endRefreshSession(sessionId)
    }

    suspend fun refreshMissingCatalogueProducts(
        store: String,
        acceptedIdentityKeys: Set<String>,
        targetProductIds: Set<String>,
        fetcher: suspend (String) -> com.aurum.intelligence.data.ProductFetchResponse?,
        onProgress: suspend (Int, Int, ProductEntity) -> Unit = { _, _, _ -> },
    ): MissingCatalogueProductResult = repository.refreshMissingCatalogueProducts(store, acceptedIdentityKeys, targetProductIds, fetcher, onProgress)

    suspend fun refreshProducts(
        store: String,
        productIds: Set<String>,
        fetcher: suspend (String) -> com.aurum.intelligence.data.ProductFetchResponse?,
    ): MissingCatalogueProductResult = repository.refreshProducts(store, productIds, fetcher)

    suspend fun refreshProduct(productId: String, fetcher: suspend (String) -> com.aurum.intelligence.data.ProductFetchResponse?): com.aurum.intelligence.data.ProductLookup =
        repository.refreshProduct(productId, fetcher)

    fun refreshBullion(sourceId: String? = null) = viewModelScope.launch {
        val target = sourceId ?: "all sources"
        refreshActivityRepository.log(RefreshLogSeverity.Info, sourceId, "Bullion refresh started: $target")
        runCatching { bullionRepository.refresh(sourceId) }
            .onSuccess {
                refreshActivityRepository.log(
                    RefreshLogSeverity.Info,
                    sourceId,
                    bullionRepository.progress.value.note ?: "Bullion refresh completed: $target",
                )
            }
            .onFailure { failure ->
                refreshActivityRepository.log(
                    RefreshLogSeverity.Error,
                    sourceId,
                    "Bullion refresh failed: ${failure.message ?: "unknown error"}",
                )
            }
    }

    fun recordTanishqRate(price24: Double, price22: Double?, onComplete: () -> Unit) = viewModelScope.launch {
        val failure = runCatching { bullionRepository.recordBrowserRates("tan", BullionRates(price24, price22)) }.exceptionOrNull()
        if (failure == null) {
            refreshActivityRepository.log(RefreshLogSeverity.Info, "tanishq", "Rendered bullion rate saved")
        } else {
            mutableProductMessage.value = failure.message ?: "Unable to save Tanishq rate"
            refreshActivityRepository.log(RefreshLogSeverity.Error, "tanishq", "Rendered bullion rate was not saved: ${failure.message ?: "unknown error"}")
        }
        onComplete()
    }

    fun exportArchive(contentResolver: ContentResolver, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            mutableArchiveOperation.value = ArchiveOperationState.Running("Exporting Aurum data...")
            mutableArchiveOperation.value = runCatching {
                contentResolver.openOutputStream(uri, "wt")?.use { repository.exportArchive(it) }
                    ?: error("The selected document cannot be written")
                ArchiveOperationState.Complete("Export complete")
            }.getOrElse { ArchiveOperationState.Failed(it.message ?: "Export failed") }
        }
    }

    fun importArchive(contentResolver: ContentResolver, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            mutableArchiveOperation.value = ArchiveOperationState.Running("Importing Aurum data...")
            mutableArchiveOperation.value = runCatching {
                val result = contentResolver.openInputStream(uri)?.use { repository.importArchive(it) }
                    ?: error("The selected document cannot be read")
                ArchiveOperationState.Complete(
                    "Imported ${result.productsAdded} new and ${result.productsMerged} existing products, " +
                        "${result.bullionSourcesMerged} bullion sources and ${result.bullionHistoryAdded} rate observations",
                )
            }.getOrElse { ArchiveOperationState.Failed(it.message ?: "Import failed") }
        }
    }

    class Factory(
        private val repository: BridgeRepository,
        private val settingsRepository: AppSettingsRepository,
        private val watchlistRepository: WatchlistRepository,
        private val bullionRepository: BullionRepository,
        private val refreshActivityRepository: RefreshActivityRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(AurumViewModel::class.java))
            return AurumViewModel(
                repository,
                settingsRepository,
                watchlistRepository,
                bullionRepository,
                refreshActivityRepository,
            ) as T
        }
    }
}