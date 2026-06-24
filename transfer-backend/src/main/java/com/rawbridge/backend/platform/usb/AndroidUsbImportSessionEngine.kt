package com.rawbridge.backend.platform.usb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.mtp.MtpConstants
import android.mtp.MtpDevice
import android.mtp.MtpObjectInfo
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.rawbridge.backend.platform.UsbConnectionMonitor
import com.rawbridge.backend.storage.StoredFileType
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

internal class AndroidUsbImportSessionEngine(
    context: Context,
    private val usbConnectionMonitor: UsbConnectionMonitor,
) : UsbImportSessionEngine {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _state = MutableStateFlow<UsbImportSessionState>(UsbImportSessionState.Stopped)
    override val state: StateFlow<UsbImportSessionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<UsbImportSessionEvent>(extraBufferCapacity = 32)
    override val events: SharedFlow<UsbImportSessionEvent> = _events.asSharedFlow()

    private val _catalog = MutableStateFlow<List<UsbCameraCatalogItem>>(emptyList())
    override val catalog: StateFlow<List<UsbCameraCatalogItem>> = _catalog.asStateFlow()

    private var activeRequest: UsbImportSessionStartRequest? = null
    private var activeDeviceName: String? = null
    private var sessionCacheDir: File? = null
    private var sessionGeneration: Long = 0L

    @Volatile
    private var stopRequested: Boolean = false

    override suspend fun start(request: UsbImportSessionStartRequest) {
        val generation = beginNewSession(request)
        _state.value = UsbImportSessionState.Starting(request)

        val snapshot = usbConnectionMonitor.refresh(request.settings.usbModePreference)
        if (!snapshot.isConnected) {
            _state.value = UsbImportSessionState.Error(
                reason = snapshot.unavailableReason ?: "\u672a\u68c0\u6d4b\u5230\u53ef\u7528\u76f8\u673a\u3002",
                request = request,
            )
            return
        }
        if (!snapshot.hasPermission) {
            _state.value = UsbImportSessionState.Error(
                reason = snapshot.unavailableReason
                    ?: "\u8fd8\u6ca1\u6709 USB \u8bbf\u95ee\u6743\u9650\u3002",
                request = request,
            )
            return
        }
        if (!snapshot.isBrowsable) {
            _state.value = UsbImportSessionState.Error(
                reason = snapshot.unavailableReason
                    ?: "\u5f53\u524d USB \u6a21\u5f0f\u6682\u65f6\u4e0d\u53ef\u6d4f\u89c8\u3002",
                request = request,
            )
            return
        }

        activeDeviceName = snapshot.deviceName ?: "USB Camera"
        sessionCacheDir = ensureSessionCacheDirectory()
        refreshCatalog()
        if (!isSessionActive(generation)) return

        _state.value = UsbImportSessionState.Ready(
            deviceName = activeDeviceName.orEmpty(),
            usbModeLabel = snapshot.usbModeLabel ?: request.settings.usbModePreference.name,
        )
    }

    override suspend fun refreshCatalog() {
        val request = activeRequest ?: return
        val generation = sessionGeneration
        val snapshot = usbConnectionMonitor.refresh(request.settings.usbModePreference)
        if (!snapshot.isReadyToBrowse) {
            _catalog.value = emptyList()
            return
        }

        val device = selectCameraDevice()
        if (device == null) {
            _catalog.value = emptyList()
            return
        }

        val modeLabel = snapshot.usbModeLabel ?: request.settings.usbModePreference.name
        val publishIncrementally = _catalog.value.isEmpty()
        val items = withContext(Dispatchers.IO) {
            enumerateCatalog(
                device = device,
                modeLabel = modeLabel,
                generation = generation,
                publishIncrementally = publishIncrementally,
            )
        }
        if (!isSessionActive(generation)) return

        if (!publishIncrementally || _catalog.value != items) {
            _catalog.value = items
        }
        if (_state.value !is UsbImportSessionState.Importing) {
            _state.value = UsbImportSessionState.Ready(
                deviceName = snapshot.deviceName ?: activeDeviceName.orEmpty(),
                usbModeLabel = modeLabel,
            )
        }
    }

    override suspend fun importSelected(captureIds: List<String>) {
        val request = activeRequest ?: return
        val generation = sessionGeneration
        val snapshot = usbConnectionMonitor.refresh(request.settings.usbModePreference)
        if (!snapshot.isReadyToBrowse) {
            _state.value = UsbImportSessionState.Error(
                reason = snapshot.unavailableReason
                    ?: "USB \u4f1a\u8bdd\u4e0d\u53ef\u7528\uff0c\u65e0\u6cd5\u5f00\u59cb\u5bfc\u5165\u3002",
                request = request,
            )
            return
        }

        val targets = _catalog.value.filter { it.id in captureIds }
        if (targets.isEmpty()) return

        val device = selectCameraDevice()
        if (device == null) {
            _state.value = UsbImportSessionState.Error(
                reason = "\u672a\u627e\u5230\u53ef\u7528\u4e8e\u5bfc\u5165\u7684\u76f8\u673a\u8bbe\u5907\u3002",
                request = request,
            )
            return
        }

        val deviceName = activeDeviceName.orEmpty()
        val modeLabel = snapshot.usbModeLabel ?: request.settings.usbModePreference.name
        withContext(Dispatchers.IO) {
            openMtpSession(
                device = device,
                modeLabel = modeLabel,
            ) { mtpDevice ->
                for ((index, item) in targets.withIndex()) {
                    if (!isSessionActive(generation)) {
                        break
                    }

                    _state.value = UsbImportSessionState.Importing(
                        deviceName = deviceName,
                        usbModeLabel = modeLabel,
                        captureId = item.id,
                        currentFileName = item.fileName,
                        currentIndex = index + 1,
                        totalCount = targets.size,
                    )

                    val stagingFile = File.createTempFile(
                        "rawbridge-import-",
                        ".part",
                        appContext.cacheDir,
                    )
                    try {
                        val success = ParcelFileDescriptor.open(
                            stagingFile,
                            ParcelFileDescriptor.MODE_READ_WRITE or
                                ParcelFileDescriptor.MODE_CREATE or
                                ParcelFileDescriptor.MODE_TRUNCATE,
                        ).use { descriptor ->
                            mtpDevice.importFile(item.objectHandle, descriptor)
                        }
                        if (!success) {
                            _events.tryEmit(
                                UsbImportSessionEvent.FileImportFailed(
                                    captureId = item.id,
                                    fileName = item.fileName,
                                    reason = "\u76f8\u673a\u8fd4\u56de\u5bfc\u5165\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5 USB \u6a21\u5f0f\u6216\u91cd\u65b0\u8fde\u63a5\u3002",
                                ),
                            )
                            stagingFile.delete()
                            continue
                        }

                        if (!isSessionActive(generation)) {
                            stagingFile.delete()
                            break
                        }

                        _events.tryEmit(
                            UsbImportSessionEvent.FileImported(
                                captureId = item.id,
                                fileName = item.fileName,
                                stagingPath = stagingFile.absolutePath,
                                sizeBytes = item.sizeBytes,
                            ),
                        )
                    } catch (error: Throwable) {
                        stagingFile.delete()
                        _events.tryEmit(
                            UsbImportSessionEvent.FileImportFailed(
                                captureId = item.id,
                                fileName = item.fileName,
                                reason = error.message
                                    ?: "\u5bfc\u5165\u8fc7\u7a0b\u4e2d\u53d1\u751f\u672a\u77e5\u9519\u8bef\u3002",
                            ),
                        )
                    }
                }
            }
        }
        if (!isSessionActive(generation)) return

        _state.value = UsbImportSessionState.Ready(
            deviceName = deviceName,
            usbModeLabel = modeLabel,
        )
    }

    override suspend fun stop() {
        stopRequested = true
        sessionGeneration += 1L
        activeRequest = null
        activeDeviceName = null
        _catalog.value = emptyList()
        clearSessionCache()
        _state.value = UsbImportSessionState.Stopped
    }

    private fun enumerateCatalog(
        device: UsbDevice,
        modeLabel: String,
        generation: Long,
        publishIncrementally: Boolean,
    ): List<UsbCameraCatalogItem> {
        return openMtpSession(device, modeLabel) { mtpDevice ->
            val cacheDir = ensureSessionCacheDirectory()
            val previewByKey = linkedMapOf<String, String>()
            val rows = mutableListOf<CatalogRow>()
            val publisher = CatalogProgressPublisher(
                generation = generation,
                enabled = publishIncrementally,
                rows = rows,
                previewByKey = previewByKey,
            )

            mtpDevice.storageIds?.forEach { storageId ->
                collectObjects(
                    mtpDevice = mtpDevice,
                    storageId = storageId,
                    parentHandle = 0,
                    generation = generation,
                    rows = rows,
                    previewByKey = previewByKey,
                    cacheDir = cacheDir,
                    publisher = publisher,
                )
            }

            publisher.publish(force = true)
            buildCatalogItems(rows, previewByKey)
        }
    }

    private fun collectObjects(
        mtpDevice: MtpDevice,
        storageId: Int,
        parentHandle: Int,
        generation: Long,
        rows: MutableList<CatalogRow>,
        previewByKey: MutableMap<String, String>,
        cacheDir: File,
        publisher: CatalogProgressPublisher,
    ) {
        if (!isSessionActive(generation)) {
            return
        }
        val handles = mtpDevice.getObjectHandles(storageId, 0, parentHandle) ?: return
        for (handle in handles) {
            if (!isSessionActive(generation)) {
                return
            }
            val info = mtpDevice.getObjectInfo(handle) ?: continue
            if (isAssociation(info)) {
                collectObjects(
                    mtpDevice = mtpDevice,
                    storageId = storageId,
                    parentHandle = handle,
                    generation = generation,
                    rows = rows,
                    previewByKey = previewByKey,
                    cacheDir = cacheDir,
                    publisher = publisher,
                )
                continue
            }

            val fileName = info.name
            if (fileName.isBlank()) {
                continue
            }
            val fileType = detectFileType(fileName) ?: continue
            val row = CatalogRow(
                id = "$storageId:$handle",
                objectHandle = handle,
                storageId = storageId,
                fileName = info.name,
                fileType = fileType,
                sizeBytes = info.compressedSize.toLong(),
                capturedAtEpochMillis = info.dateCreated.takeIf { it > 0L }
                    ?.times(1000L)
                    ?: System.currentTimeMillis(),
            )
            insertRowDescending(rows, row)
            publisher.onRowDiscovered()

            if (!isSessionActive(generation)) {
                return
            }

            val thumbnailUri = writeThumbnailFile(
                mtpDevice = mtpDevice,
                objectHandle = handle,
                fileName = fileName,
                fileType = fileType,
                cacheDir = cacheDir,
            )
            row.thumbnailUri = thumbnailUri
            if (fileType == StoredFileType.JPEG && thumbnailUri != null) {
                previewByKey[row.previewKey] = thumbnailUri
            }
            publisher.onThumbnailResolved()
        }
    }

    private fun writeThumbnailFile(
        mtpDevice: MtpDevice,
        objectHandle: Int,
        fileName: String,
        fileType: StoredFileType,
        cacheDir: File,
    ): String? {
        val bytes = runCatching { mtpDevice.getThumbnail(objectHandle) }.getOrNull()
            ?: return null
        val extension = when (fileType) {
            StoredFileType.JPEG -> "jpg"
            StoredFileType.RAW -> "jpg"
            StoredFileType.OTHER -> "bin"
        }
        val target = File(cacheDir, "${objectHandle}_thumb.$extension")
        return runCatching {
            cacheDir.mkdirs()
            target.writeBytes(bytes)
            Uri.fromFile(target).toString()
        }.getOrNull()
    }

    private fun buildCatalogItems(
        rows: List<CatalogRow>,
        previewByKey: Map<String, String>,
    ): List<UsbCameraCatalogItem> {
        return rows.map { row ->
            val previewUri = if (row.fileType == StoredFileType.RAW) {
                previewByKey[row.previewKey] ?: row.thumbnailUri
            } else {
                row.thumbnailUri
            }
            UsbCameraCatalogItem(
                id = row.id,
                objectHandle = row.objectHandle,
                storageId = row.storageId,
                fileName = row.fileName,
                fileType = row.fileType,
                sizeBytes = row.sizeBytes,
                capturedAtEpochMillis = row.capturedAtEpochMillis,
                thumbnailUri = previewUri,
                previewSourceLabel = when {
                    row.fileType == StoredFileType.RAW && previewUri == row.thumbnailUri ->
                        "RAW \u5185\u5d4c\u9884\u89c8"
                    row.fileType == StoredFileType.RAW && previewUri != null ->
                        "\u914d\u5957 JPEG \u9884\u89c8"
                    previewUri != null -> "JPEG \u7f29\u7565\u56fe"
                    else -> "\u65e0\u9884\u89c8"
                },
            )
        }
    }

    private fun insertRowDescending(
        rows: MutableList<CatalogRow>,
        row: CatalogRow,
    ) {
        val index = rows.binarySearch { existing ->
            when {
                row.capturedAtEpochMillis > existing.capturedAtEpochMillis -> -1
                row.capturedAtEpochMillis < existing.capturedAtEpochMillis -> 1
                else -> row.id.compareTo(existing.id)
            }
        }.let { result ->
            if (result >= 0) result else -result - 1
        }
        rows.add(index, row)
    }

    private fun detectFileType(fileName: String): StoredFileType? {
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> StoredFileType.JPEG
            "arw", "raw", "dng" -> StoredFileType.RAW
            else -> null
        }
    }

    private fun isAssociation(info: MtpObjectInfo): Boolean {
        return info.format == MtpConstants.FORMAT_ASSOCIATION
    }

    private fun selectCameraDevice(): UsbDevice? {
        return usbManager.selectRawBridgeCameraDevice(requirePermission = true)
    }

    private fun <T> openMtpSession(
        device: UsbDevice,
        modeLabel: String,
        block: (MtpDevice) -> T,
    ): T {
        return openUsbBrowseSession(
            usbManager = usbManager,
            device = device,
            modeLabel = modeLabel,
            block = block,
        )
    }

    private fun ensureSessionCacheDirectory(): File {
        val existing = sessionCacheDir
        if (existing != null && existing.exists()) {
            return File(existing, "thumbnails").apply { mkdirs() }
        }

        val sessionId = System.currentTimeMillis().toString()
        val root = File(appContext.cacheDir, "rawbridge/session-$sessionId")
        val thumbnails = File(root, "thumbnails")
        thumbnails.mkdirs()
        sessionCacheDir = root
        return thumbnails
    }

    private fun clearSessionCache() {
        sessionCacheDir?.deleteRecursively()
        sessionCacheDir = null
    }

    private fun beginNewSession(request: UsbImportSessionStartRequest): Long {
        sessionGeneration += 1L
        stopRequested = false
        activeRequest = request
        return sessionGeneration
    }

    private fun isSessionActive(generation: Long): Boolean {
        return !stopRequested &&
            generation == sessionGeneration &&
            activeRequest != null
    }

    private inner class CatalogProgressPublisher(
        private val generation: Long,
        private val enabled: Boolean,
        private val rows: List<CatalogRow>,
        private val previewByKey: Map<String, String>,
    ) {
        private var lastPublishedAtMillis: Long = 0L
        private var lastPublishedCount: Int = 0
        private var pendingThumbnailUpdates: Int = 0

        fun onRowDiscovered() {
            maybePublish(force = rows.size <= FirstScreenPublishCount)
        }

        fun onThumbnailResolved() {
            pendingThumbnailUpdates += 1
            maybePublish(force = rows.size <= FirstScreenPublishCount)
        }

        fun publish(force: Boolean = false) {
            if (!enabled && !force) {
                return
            }
            if (!isSessionActive(generation)) {
                return
            }
            _catalog.value = buildCatalogItems(rows, previewByKey)
            lastPublishedAtMillis = System.currentTimeMillis()
            lastPublishedCount = rows.size
            pendingThumbnailUpdates = 0
        }

        private fun maybePublish(force: Boolean = false) {
            if (!enabled) {
                return
            }
            val now = System.currentTimeMillis()
            val discoveredSinceLastPublish = rows.size - lastPublishedCount
            val shouldPublish = force ||
                discoveredSinceLastPublish >= IncrementalPublishBatchSize ||
                (pendingThumbnailUpdates >= ThumbnailPublishBatchSize &&
                    now - lastPublishedAtMillis >= IncrementalPublishIntervalMillis) ||
                ((discoveredSinceLastPublish > 0 || pendingThumbnailUpdates > 0) &&
                    now - lastPublishedAtMillis >= IncrementalPublishIntervalMillis)
            if (shouldPublish) {
                publish(force = true)
            }
        }
    }

    private data class CatalogRow(
        val id: String,
        val objectHandle: Int,
        val storageId: Int,
        val fileName: String,
        val fileType: StoredFileType,
        val sizeBytes: Long,
        val capturedAtEpochMillis: Long,
        var thumbnailUri: String? = null,
    ) {
        val previewKey: String
            get() = fileName.substringBeforeLast('.', fileName).lowercase()
    }

    private companion object {
        const val FirstScreenPublishCount = 8
        const val IncrementalPublishBatchSize = 12
        const val ThumbnailPublishBatchSize = 4
        const val IncrementalPublishIntervalMillis = 160L
    }
}
