package com.rawbridge.backend

import android.content.Context
import com.rawbridge.backend.config.TransferSettingsRepository
import com.rawbridge.backend.history.TransferHistoryRepository
import com.rawbridge.backend.platform.UsbConnectionMonitor
import com.rawbridge.backend.platform.usb.UsbImportSessionEngine
import com.rawbridge.backend.runtime.TransferRuntimeBus
import com.rawbridge.backend.storage.IncomingFileStore
import kotlinx.coroutines.flow.StateFlow

class TransferBackend internal constructor(
    val settingsRepository: TransferSettingsRepository,
    val historyRepository: TransferHistoryRepository,
    val runtimeController: TransferRuntimeController,
    val usbConnectionMonitor: UsbConnectionMonitor,
    val usbImportSessionEngine: UsbImportSessionEngine,
    val incomingFileStore: IncomingFileStore,
) {
    val runtimeState: StateFlow<com.rawbridge.backend.runtime.ReceiverRuntimeState>
        get() = TransferRuntimeBus.state

    companion object {
        @Volatile
        private var instance: TransferBackend? = null

        fun from(context: Context): TransferBackend {
            return instance ?: synchronized(this) {
                instance ?: TransferBackendGraph.from(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
}
