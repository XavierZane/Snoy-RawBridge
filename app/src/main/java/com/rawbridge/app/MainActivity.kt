package com.rawbridge.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rawbridge.app.ui.theme.RawBridgeTheme
import com.rawbridge.backend.TransferBackend

class MainActivity : ComponentActivity() {
    private lateinit var transferBackend: TransferBackend
    private lateinit var uiPreferencesRepository: RawBridgeUiPreferencesRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        transferBackend = TransferBackend.from(applicationContext)
        uiPreferencesRepository = RawBridgeUiPreferencesRepository.from(applicationContext)
        enableEdgeToEdge()

        setContent {
            val uiPreferences by uiPreferencesRepository.preferences.collectAsStateWithLifecycle(
                initialValue = RawBridgeUiPreferences(),
            )

            RawBridgeTheme(themeMode = uiPreferences.themeMode) {
                RawBridgeApp(
                    transferBackend = transferBackend,
                    uiPreferencesRepository = uiPreferencesRepository,
                )
            }
        }
    }
}
