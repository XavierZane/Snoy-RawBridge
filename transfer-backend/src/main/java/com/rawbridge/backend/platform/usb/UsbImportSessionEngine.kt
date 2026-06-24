package com.rawbridge.backend.platform.usb

import com.rawbridge.backend.config.ReceiverSettings
import com.rawbridge.backend.storage.StoredFileType
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

data class UsbImportSessionStartRequest(
    val settings: ReceiverSettings,
)

data class UsbCameraCatalogItem(
    val id: String,
    val objectHandle: Int,
    val storageId: Int,
    val fileName: String,
    val fileType: StoredFileType,
    val sizeBytes: Long,
    val capturedAtEpochMillis: Long,
    val thumbnailUri: String?,
    val previewSourceLabel: String,
)

sealed interface UsbImportSessionEvent {
    data class FileImported(
        val captureId: String,
        val fileName: String,
        val stagingPath: String,
        val sizeBytes: Long,
        val importedAtEpochMillis: Long = System.currentTimeMillis(),
    ) : UsbImportSessionEvent

    data class FileImportFailed(
        val captureId: String,
        val fileName: String?,
        val reason: String,
        val importedAtEpochMillis: Long = System.currentTimeMillis(),
    ) : UsbImportSessionEvent
}

sealed interface UsbImportSessionState {
    data object Stopped : UsbImportSessionState

    data class Starting(
        val request: UsbImportSessionStartRequest,
    ) : UsbImportSessionState

    data class Ready(
        val deviceName: String,
        val usbModeLabel: String,
    ) : UsbImportSessionState

    data class Importing(
        val deviceName: String,
        val usbModeLabel: String,
        val captureId: String,
        val currentFileName: String,
        val currentIndex: Int,
        val totalCount: Int,
    ) : UsbImportSessionState

    data class Error(
        val reason: String,
        val request: UsbImportSessionStartRequest? = null,
    ) : UsbImportSessionState
}

interface UsbImportSessionEngine {
    val state: StateFlow<UsbImportSessionState>
    val events: SharedFlow<UsbImportSessionEvent>
    val catalog: StateFlow<List<UsbCameraCatalogItem>>

    suspend fun start(request: UsbImportSessionStartRequest)

    suspend fun refreshCatalog()

    suspend fun importSelected(captureIds: List<String>)

    suspend fun stop()
}
