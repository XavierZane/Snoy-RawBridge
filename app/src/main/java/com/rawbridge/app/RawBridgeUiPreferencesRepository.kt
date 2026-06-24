package com.rawbridge.app

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.rawBridgeUiPreferencesDataStore by preferencesDataStore(
    name = "rawbridge_ui_preferences",
)

data class RawBridgeUiPreferences(
    val hasCompletedOnboarding: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.System,
    val captureFilter: CapturePickerFilter = CapturePickerFilter.All,
    val isMultiSelectMode: Boolean = false,
    val selectedCaptureIds: Set<String> = emptySet(),
)

interface RawBridgeUiPreferencesRepository {
    val preferences: Flow<RawBridgeUiPreferences>

    suspend fun current(): RawBridgeUiPreferences

    suspend fun save(preferences: RawBridgeUiPreferences)

    suspend fun update(transform: (RawBridgeUiPreferences) -> RawBridgeUiPreferences)

    companion object {
        @Volatile
        private var instance: RawBridgeUiPreferencesRepository? = null

        fun from(context: Context): RawBridgeUiPreferencesRepository {
            return instance ?: synchronized(this) {
                instance ?: DataStoreRawBridgeUiPreferencesRepository(
                    appContext = context.applicationContext,
                ).also { instance = it }
            }
        }
    }
}

private class DataStoreRawBridgeUiPreferencesRepository(
    private val appContext: Context,
) : RawBridgeUiPreferencesRepository {
    override val preferences: Flow<RawBridgeUiPreferences> = appContext.rawBridgeUiPreferencesDataStore.data
        .map(::mapPreferences)

    override suspend fun current(): RawBridgeUiPreferences = preferences.first()

    override suspend fun save(preferences: RawBridgeUiPreferences) {
        appContext.rawBridgeUiPreferencesDataStore.edit { prefs ->
            prefs[Keys.HasCompletedOnboarding] = preferences.hasCompletedOnboarding
            prefs[Keys.ThemeMode] = preferences.themeMode.name
            prefs[Keys.CaptureFilter] = preferences.captureFilter.name
            prefs[Keys.IsMultiSelectMode] = preferences.isMultiSelectMode
            prefs[Keys.SelectedCaptureIds] = preferences.selectedCaptureIds
        }
    }

    override suspend fun update(transform: (RawBridgeUiPreferences) -> RawBridgeUiPreferences) {
        save(transform(current()))
    }

    private fun mapPreferences(preferences: Preferences): RawBridgeUiPreferences {
        return RawBridgeUiPreferences(
            hasCompletedOnboarding = preferences[Keys.HasCompletedOnboarding] ?: false,
            themeMode = ThemeMode.fromStorage(preferences[Keys.ThemeMode]),
            captureFilter = preferences[Keys.CaptureFilter]
                ?.let { stored -> CapturePickerFilter.entries.firstOrNull { it.name == stored } }
                ?: CapturePickerFilter.All,
            isMultiSelectMode = preferences[Keys.IsMultiSelectMode] ?: false,
            selectedCaptureIds = preferences[Keys.SelectedCaptureIds].orEmpty(),
        )
    }

    private object Keys {
        val HasCompletedOnboarding = booleanPreferencesKey("has_completed_onboarding")
        val ThemeMode = stringPreferencesKey("theme_mode")
        val CaptureFilter = stringPreferencesKey("capture_filter")
        val IsMultiSelectMode = booleanPreferencesKey("is_multi_select_mode")
        val SelectedCaptureIds = stringSetPreferencesKey("selected_capture_ids")
    }
}
