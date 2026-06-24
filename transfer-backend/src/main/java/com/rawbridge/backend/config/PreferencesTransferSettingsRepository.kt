package com.rawbridge.backend.config

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.receiverSettingsDataStore by preferencesDataStore(name = "rawbridge_import_settings")

internal class PreferencesTransferSettingsRepository(
    private val appContext: Context,
) : TransferSettingsRepository {
    override val settings: Flow<ReceiverSettings> = appContext.receiverSettingsDataStore.data
        .map(::mapPreferences)

    override suspend fun current(): ReceiverSettings = settings.first()

    override suspend fun save(settings: ReceiverSettings) {
        appContext.receiverSettingsDataStore.edit { prefs ->
            prefs[Keys.UsbModePreference] = settings.usbModePreference.name
            prefs[Keys.SaveRoot] = settings.saveRoot
            prefs[Keys.AutoCreateDateFolder] = settings.autoCreateDateFolder
            prefs[Keys.SplitRawAndJpeg] = settings.splitRawAndJpeg
            prefs[Keys.ClearPreviewCacheOnDisconnect] = settings.clearPreviewCacheOnDisconnect
        }
    }

    override suspend fun update(transform: (ReceiverSettings) -> ReceiverSettings) {
        save(transform(current()))
    }

    private fun mapPreferences(preferences: Preferences): ReceiverSettings {
        val defaults = ReceiverSettings()
        return ReceiverSettings(
            usbModePreference = preferences[Keys.UsbModePreference]
                ?.let { stored -> UsbModePreference.entries.firstOrNull { it.name == stored } }
                ?: defaults.usbModePreference,
            saveRoot = preferences[Keys.SaveRoot] ?: defaults.saveRoot,
            autoCreateDateFolder = preferences[Keys.AutoCreateDateFolder] ?: defaults.autoCreateDateFolder,
            splitRawAndJpeg = preferences[Keys.SplitRawAndJpeg] ?: defaults.splitRawAndJpeg,
            clearPreviewCacheOnDisconnect = preferences[Keys.ClearPreviewCacheOnDisconnect]
                ?: defaults.clearPreviewCacheOnDisconnect,
        )
    }

    private object Keys {
        val UsbModePreference = stringPreferencesKey("usb_mode_preference")
        val SaveRoot = stringPreferencesKey("save_root")
        val AutoCreateDateFolder = booleanPreferencesKey("auto_create_date_folder")
        val SplitRawAndJpeg = booleanPreferencesKey("split_raw_and_jpeg")
        val ClearPreviewCacheOnDisconnect = booleanPreferencesKey("clear_preview_cache_on_disconnect")
    }
}
