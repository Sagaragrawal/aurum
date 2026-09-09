package com.aurum.intelligence.data.repository
import com.aurum.intelligence.data.db.*
import com.aurum.intelligence.data.engine.*
import com.aurum.intelligence.data.model.*
import com.aurum.intelligence.data.repository.*
import com.aurum.intelligence.data.validation.*

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

enum class ThemeChoice { System, Light, Dark }

data class AppSettings(
    val theme: ThemeChoice = ThemeChoice.System,
    val pincode: String = ScraperConfigProvider.get().location.defaultPincode,
    val preciseAddress: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val refreshBullionOnStart: Boolean = ScraperConfigProvider.get().appSettingsDefaults.refreshBullionOnStart,
    val refreshProductsOnStart: Boolean = ScraperConfigProvider.get().appSettingsDefaults.refreshProductsOnStart,
    val dealMode: String = ScraperConfigProvider.get().appSettingsDefaults.dealMode,
    val dealPercentThreshold: Double = ScraperConfigProvider.get().appSettingsDefaults.dealPercentThreshold,
    val dealRupeesThreshold: Double = ScraperConfigProvider.get().appSettingsDefaults.dealRupeesThreshold,
    val backgroundRefreshEnabled: Boolean = ScraperConfigProvider.get().appSettingsDefaults.backgroundRefreshEnabled,
    val refreshIntervalMinutes: Int = ScraperConfigProvider.get().appSettingsDefaults.refreshIntervalMinutes,
    val backgroundRefreshRequestedAt: Long? = null,
    val debugModeEnabled: Boolean = false,
)

private val Context.aurumSettingsDataStore by preferencesDataStore(name = "aurum_settings")

class AppSettingsRepository(private val context: Context) {
    val settings: Flow<AppSettings> = context.aurumSettingsDataStore.data
        .catch { failure ->
            if (failure is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw failure
        }
        .map { preferences ->
            val config = ScraperConfigProvider.get()
            val defaults = config.appSettingsDefaults
            val allowedIntervals = defaults.allowedRefreshIntervals.ifEmpty { listOf(15, 30, 60, 120, 240) }
            val minInterval = allowedIntervals.minOrNull() ?: 15
            val maxInterval = allowedIntervals.maxOrNull() ?: 240
            AppSettings(
                theme = preferences[themeKey]?.let { value ->
                    ThemeChoice.entries.firstOrNull { it.name == value }
                } ?: ThemeChoice.entries.firstOrNull { it.name.equals(defaults.theme, ignoreCase = true) } ?: ThemeChoice.System,
                pincode = preferences[pincodeKey]?.takeIf { it.matches(Regex("\\d{6}")) } ?: config.location.defaultPincode,
                preciseAddress = preferences[preciseAddressKey].orEmpty(),
                latitude = preferences[latitudeKey]?.toDoubleOrNull(),
                longitude = preferences[longitudeKey]?.toDoubleOrNull(),
                refreshBullionOnStart = preferences[refreshBullionOnStartKey] ?: defaults.refreshBullionOnStart,
                refreshProductsOnStart = preferences[refreshProductsOnStartKey] ?: defaults.refreshProductsOnStart,
                dealMode = preferences[dealModeKey]?.takeIf { it in setOf("Percent", "RupeesPerGram") } ?: defaults.dealMode,
                dealPercentThreshold = preferences[dealPercentThresholdKey]?.toDoubleOrNull()?.coerceAtLeast(0.0) ?: defaults.dealPercentThreshold,
                dealRupeesThreshold = preferences[dealRupeesThresholdKey]?.toDoubleOrNull()?.coerceAtLeast(0.0) ?: defaults.dealRupeesThreshold,
                backgroundRefreshEnabled = preferences[backgroundRefreshKey] ?: defaults.backgroundRefreshEnabled,
                refreshIntervalMinutes = (preferences[refreshIntervalKey] ?: defaults.refreshIntervalMinutes).coerceIn(minInterval, maxInterval),
                backgroundRefreshRequestedAt = preferences[backgroundRefreshRequestedAtKey],
                debugModeEnabled = preferences[debugModeKey] ?: false,
            )
        }

    suspend fun setTheme(theme: ThemeChoice) {
        context.aurumSettingsDataStore.edit { it[themeKey] = theme.name }
    }

    suspend fun setLocation(pincode: String, address: String) {
        require(pincode.matches(Regex("\\d{6}"))) { "Pincode must contain six digits" }
        context.aurumSettingsDataStore.edit {
            it[pincodeKey] = pincode
            it[preciseAddressKey] = address.trim()
        }
    }

    suspend fun setRefreshBullionOnStart(enabled: Boolean) {
        context.aurumSettingsDataStore.edit { it[refreshBullionOnStartKey] = enabled }
    }

    suspend fun setRefreshProductsOnStart(enabled: Boolean) {
        context.aurumSettingsDataStore.edit { it[refreshProductsOnStartKey] = enabled }
    }

    suspend fun setCoordinates(lat: Double, lng: Double) {
        context.aurumSettingsDataStore.edit {
            it[latitudeKey] = lat.toString()
            it[longitudeKey] = lng.toString()
        }
    }

    suspend fun setDealMode(mode: String) {
        require(mode in setOf("Percent", "RupeesPerGram")) { "Unsupported deal mode" }
        context.aurumSettingsDataStore.edit { it[dealModeKey] = mode }
    }

    suspend fun setDealThreshold(mode: String, threshold: Double) {
        require(threshold.isFinite() && threshold >= 0) { "Deal threshold must be positive" }
        context.aurumSettingsDataStore.edit {
            it[if (mode == "RupeesPerGram") dealRupeesThresholdKey else dealPercentThresholdKey] = threshold.toString()
        }
    }

    suspend fun setBackgroundRefreshEnabled(enabled: Boolean) {
        context.aurumSettingsDataStore.edit { it[backgroundRefreshKey] = enabled }
    }

    suspend fun setRefreshIntervalMinutes(minutes: Int) {
        context.aurumSettingsDataStore.edit { it[refreshIntervalKey] = minutes.coerceIn(15, 240) }
    }

    suspend fun setDebugModeEnabled(enabled: Boolean) {
        context.aurumSettingsDataStore.edit { it[debugModeKey] = enabled }
    }

    suspend fun markBackgroundRefreshRequested(requestedAt: Long = System.currentTimeMillis()) {
        context.aurumSettingsDataStore.edit { it[backgroundRefreshRequestedAtKey] = requestedAt }
    }

    suspend fun clearBackgroundRefreshRequest() {
        context.aurumSettingsDataStore.edit { it.remove(backgroundRefreshRequestedAtKey) }
    }

    private companion object {
        val themeKey = stringPreferencesKey("theme")
        val pincodeKey = stringPreferencesKey("pincode")
        val preciseAddressKey = stringPreferencesKey("precise_address")
        val latitudeKey = stringPreferencesKey("latitude")
        val longitudeKey = stringPreferencesKey("longitude")
        val refreshBullionOnStartKey = booleanPreferencesKey("refresh_bullion_on_start")
        val refreshProductsOnStartKey = booleanPreferencesKey("refresh_products_on_start")
        val dealModeKey = stringPreferencesKey("deal_mode")
        val dealPercentThresholdKey = stringPreferencesKey("deal_percent_threshold")
        val dealRupeesThresholdKey = stringPreferencesKey("deal_rupees_threshold")
        val backgroundRefreshKey = booleanPreferencesKey("background_refresh_enabled")
        val refreshIntervalKey = intPreferencesKey("refresh_interval_minutes")
        val backgroundRefreshRequestedAtKey = longPreferencesKey("background_refresh_requested_at")
        val debugModeKey = booleanPreferencesKey("debug_mode_enabled")
    }
}