package com.rawbridge.backend

import android.content.Context
import androidx.room.Room
import com.rawbridge.backend.config.PreferencesTransferSettingsRepository
import com.rawbridge.backend.config.TransferSettingsRepository
import com.rawbridge.backend.history.RoomTransferHistoryRepository
import com.rawbridge.backend.history.TransferHistoryRepository
import com.rawbridge.backend.history.db.TransferDatabase
import com.rawbridge.backend.platform.AndroidUsbConnectionMonitor
import com.rawbridge.backend.platform.UsbConnectionMonitor
import com.rawbridge.backend.platform.usb.AndroidUsbImportSessionEngine
import com.rawbridge.backend.platform.usb.UsbImportSessionEngine
import com.rawbridge.backend.storage.IncomingFileStore
import com.rawbridge.backend.storage.MediaStoreIncomingFileStore

internal object TransferBackendGraph {
    @Volatile
    private var services: BackendServices? = null

    fun from(context: Context): TransferBackend {
        val current = services(context)
        return TransferBackend(
            settingsRepository = current.settingsRepository,
            historyRepository = current.historyRepository,
            runtimeController = current.runtimeController,
            usbConnectionMonitor = current.usbConnectionMonitor,
            usbImportSessionEngine = current.usbImportSessionEngine,
            incomingFileStore = current.incomingFileStore,
        )
    }

    fun services(context: Context): BackendServices {
        return services ?: synchronized(this) {
            services ?: buildServices(context.applicationContext).also { services = it }
        }
    }

    private fun buildServices(context: Context): BackendServices {
        val database = Room.databaseBuilder(
            context,
            TransferDatabase::class.java,
            "rawbridge-transfer.db",
        ).build()

        val settingsRepository = PreferencesTransferSettingsRepository(context)
        val historyRepository = RoomTransferHistoryRepository(database.historyDao())
        val usbConnectionMonitor = AndroidUsbConnectionMonitor(context)
        val incomingFileStore = MediaStoreIncomingFileStore(context)
        val usbImportSessionEngine = AndroidUsbImportSessionEngine(
            context = context,
            usbConnectionMonitor = usbConnectionMonitor,
        )
        val runtimeController = AndroidTransferRuntimeController(context)

        return BackendServices(
            settingsRepository = settingsRepository,
            historyRepository = historyRepository,
            usbConnectionMonitor = usbConnectionMonitor,
            incomingFileStore = incomingFileStore,
            usbImportSessionEngine = usbImportSessionEngine,
            runtimeController = runtimeController,
        )
    }
}

internal data class BackendServices(
    val settingsRepository: TransferSettingsRepository,
    val historyRepository: TransferHistoryRepository,
    val usbConnectionMonitor: UsbConnectionMonitor,
    val incomingFileStore: IncomingFileStore,
    val usbImportSessionEngine: UsbImportSessionEngine,
    val runtimeController: TransferRuntimeController,
)
