package com.aurum.intelligence.data

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
    val pincode: String = "560048",
    val preciseAddress: String = "",
    val refreshBullionOnStart: Boolean = false,
    val refreshProductsOnStart: Boolean = false,
    val dealMode: String = "Percent",
    val dealPercentThreshold: Double = 2.0,
    val dealRupeesThreshold: Double = 200.0,
    val backgroundRefreshEnabled: Boolean = false,
    val refreshIntervalMinutes: Int = 60,
    val backgroundRefreshRequestedAt: Long? = null,
)

private val Context.aurumSettingsDataStore by preferencesDataStore(name = "aurum_settings")

class AppSettingsRepository(private val context: Context) {
    val settings: Flow<AppSettings> = context.aurumSettingsDataStore.data
        .catch { failure ->
            if (failure is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw failure
        }
        .map { preferences ->
            AppSettings(
                theme = preferences[themeKey]?.let { value ->
                    ThemeChoice.entries.firstOrNull { it.name == value }
                } ?: ThemeChoice.System,
                pincode = preferences[pincodeKey]?.takeIf { it.matches(Regex("\\d{6}")) } ?: "560048",
                preciseAddress = preferences[preciseAddressKey].orEmpty(),
                refreshBullionOnStart = preferences[refreshBullionOnStartKey] ?: false,
                refreshProductsOnStart = preferences[refreshProductsOnStartKey] ?: false,
                dealMode = preferences[dealModeKey]?.takeIf { it in setOf("Percent", "RupeesPerGram") } ?: "Percent",
                dealPercentThreshold = preferences[dealPercentThresholdKey]?.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 2.0,
                dealRupeesThreshold = preferences[dealRupeesThresholdKey]?.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 200.0,
                backgroundRefreshEnabled = preferences[backgroundRefreshKey] ?: false,
                refreshIntervalMinutes = (preferences[refreshIntervalKey] ?: 60).coerceIn(15, 240),
                backgroundRefreshRequestedAt = preferences[backgroundRefreshRequestedAtKey],
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
        val refreshBullionOnStartKey = booleanPreferencesKey("refresh_bullion_on_start")
        val refreshProductsOnStartKey = booleanPreferencesKey("refresh_products_on_start")
        val dealModeKey = stringPreferencesKey("deal_mode")
        val dealPercentThresholdKey = stringPreferencesKey("deal_percent_threshold")
        val dealRupeesThresholdKey = stringPreferencesKey("deal_rupees_threshold")
        val backgroundRefreshKey = booleanPreferencesKey("background_refresh_enabled")
        val refreshIntervalKey = intPreferencesKey("refresh_interval_minutes")
        val backgroundRefreshRequestedAtKey = longPreferencesKey("background_refresh_requested_at")
    }
}