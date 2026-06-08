package com.arvell.simswitcher.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.arvell.simswitcher.model.SwitchConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "switch_config")

/** Persists and exposes the user's [SwitchConfig] as a reactive flow. */
class SettingsRepository(private val context: Context) {

    val config: Flow<SwitchConfig> = context.dataStore.data.map { p ->
        SwitchConfig(
            enabled = p[KEY_ENABLED] ?: false,
            minSignalLevel = p[KEY_MIN_SIGNAL] ?: 1,
            failureWindowMs = p[KEY_FAILURE_WINDOW] ?: 12_000L,
            cooldownMs = p[KEY_COOLDOWN] ?: 60_000L,
            requireValidatedInternet = p[KEY_REQUIRE_INTERNET] ?: true,
            preferPrimary = p[KEY_PREFER_PRIMARY] ?: true,
            primarySubId = p[KEY_PRIMARY_SUB] ?: -1,
        )
    }

    suspend fun update(transform: (SwitchConfig) -> SwitchConfig) {
        context.dataStore.edit { p ->
            val current = SwitchConfig(
                enabled = p[KEY_ENABLED] ?: false,
                minSignalLevel = p[KEY_MIN_SIGNAL] ?: 1,
                failureWindowMs = p[KEY_FAILURE_WINDOW] ?: 12_000L,
                cooldownMs = p[KEY_COOLDOWN] ?: 60_000L,
                requireValidatedInternet = p[KEY_REQUIRE_INTERNET] ?: true,
                preferPrimary = p[KEY_PREFER_PRIMARY] ?: true,
                primarySubId = p[KEY_PRIMARY_SUB] ?: -1,
            )
            val next = transform(current)
            p[KEY_ENABLED] = next.enabled
            p[KEY_MIN_SIGNAL] = next.minSignalLevel
            p[KEY_FAILURE_WINDOW] = next.failureWindowMs
            p[KEY_COOLDOWN] = next.cooldownMs
            p[KEY_REQUIRE_INTERNET] = next.requireValidatedInternet
            p[KEY_PREFER_PRIMARY] = next.preferPrimary
            p[KEY_PRIMARY_SUB] = next.primarySubId
        }
    }

    private companion object {
        val KEY_ENABLED = booleanPreferencesKey("enabled")
        val KEY_MIN_SIGNAL = intPreferencesKey("min_signal")
        val KEY_FAILURE_WINDOW = longPreferencesKey("failure_window")
        val KEY_COOLDOWN = longPreferencesKey("cooldown")
        val KEY_REQUIRE_INTERNET = booleanPreferencesKey("require_internet")
        val KEY_PREFER_PRIMARY = booleanPreferencesKey("prefer_primary")
        val KEY_PRIMARY_SUB = intPreferencesKey("primary_sub")
    }
}
