package com.rawbridge.backend.platform.usb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.rawbridge.backend.debug.UsbDebugLogger
import com.rawbridge.backend.platform.UsbConnectionMonitor
import com.rawbridge.backend.storage.StoredFileType
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class AndroidUsbImportSessionEngine(
    context: Context,
    private val usbConnectionMonitor: UsbConnectionMonitor,
    private val browseClient: CameraBrowseClient,
) : UsbImportSessionEngine {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _state = MutableStateFlow<UsbImportSessionState>(UsbImportSessionState.Stopped)
    override val state: StateFlow<UsbImportSessionState> = _state.asStateFlow()

    // A single receiver service persists files. Unlike SharedFlow, this queue keeps events
    // produced before its collector has started, so a completed USB read reaches storage.
    private val eventQueue = Channel<UsbImportSessionEvent>(
        capacity = EventQueueCapacity,
    )
    override val events: Flow<UsbImportSessionEvent> = eventQueue.receiveAsFlow()

    private val _catalog = MutableStateFlow<List<UsbCameraCatalogItem>>(emptyList())
    override val catalog: StateFlow<List<UsbCameraCatalogItem>> = _catalog.asStateFlow()

    private val sessionMutex = Mutex()
    private val catalogScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var activeRequest: UsbImportSessionStartRequest? = null
    private var activeDeviceName: String? = null
    private var activeDevice: UsbDevice? = null
    private var activeSession: CameraBrowseSession? = null
    private var sessionCacheDir: File? = null
    private var sessionGeneration: Long = 0L
    private var catalogRefreshJob: Job? = null
    private var catalogScanToken: Long = 0L

    @Volatile
    private var stopRequested: Boolean = false

    override suspend fun start(request: UsbImportSessionStartRequest) {
        cancelCatalogRefresh()
        sessionMutex.withLock {
            val generation = beginNewSession(request)
            _state.value = UsbImportSessionState.Starting(request)
            UsbDebugLogger.d(
                DebugTag,
                "engine start requested mode=${request.settings.usbModePreference.name} generation=$generation",
            )

            runCatching {
                startInternal(request, generation)
            }.onFailure { error ->
                rethrowImportCancellation(error)
                UsbDebugLogger.e(DebugTag, "engine start failed generation=$generation", error)
                failActiveSession(
                    request = request,
                    reason = error.message ?: "无法建立 USB 浏览会话。",
                    generation = generation,
                    clearCatalog = true,
                )
            }
        }
    }

    override suspend fun refreshCatalog() {
        if (catalogRefreshJob?.isActive == true) {
            UsbDebugLogger.d(DebugTag, "refreshCatalog skipped reason=scan-already-running")
            return
        }
        cancelCatalogRefresh()
        sessionMutex.withLock {
            val request = activeRequest ?: return
            val generation = sessionGeneration
            if (!isSessionActive(generation)) return

            UsbDebugLogger.d(DebugTag, "refreshCatalog requested generation=$generation")
            launchCatalogRefresh(request, generation)
        }
    }

    override suspend fun importSelected(captureIds: List<String>) {
        // PTP/MTP permits only one active transaction. Prefer the user's file read over a
        // background catalog scan, then hold the shared session while importing.
        cancelCatalogRefresh()
        sessionMutex.withLock {
            val request = activeRequest ?: return
            val generation = sessionGeneration
            if (!isSessionActive(generation)) return

            val targets = _catalog.value.filter { it.id in captureIds }
            if (targets.isEmpty()) {
                UsbDebugLogger.w(DebugTag, "import skipped reason=no-targets selected=${captureIds.size}")
                return
            }

            val deviceName = activeDeviceName.orEmpty()
            val modeLabel = usbConnectionMonitor.current().usbModeLabel
                ?: request.settings.usbModePreference.name

            targets.forEachIndexed { index, item ->
                if (!isSessionActive(generation)) return

                UsbDebugLogger.d(
                    DebugTag,
                    "import start id=${item.id} handle=${item.objectHandle} name=${item.fileName} expectedBytes=${item.sizeBytes}",
                )
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
                    val result = readObjectToStaging(
                        request = request,
                        generation = generation,
                        item = item,
                        stagingFile = stagingFile,
                    )
                    UsbDebugLogger.d(
                        DebugTag,
                        "import ok handle=${item.objectHandle} file=${item.fileName} bytes=${result.bytesRead} via=${result.operationLabel}",
                    )
                    if (!isSessionActive(generation)) {
                        stagingFile.delete()
                        return
                    }
                    eventQueue.send(
                        UsbImportSessionEvent.FileImported(
                            captureId = item.id,
                            fileName = item.fileName,
                            stagingPath = stagingFile.absolutePath,
                            sizeBytes = result.bytesRead,
                        ),
                    )
                } catch (error: Throwable) {
                    rethrowImportCancellation(error)
                    stagingFile.delete()
                    if (!isSessionActive(generation)) return
                    UsbDebugLogger.e(
                        DebugTag,
                        "import failed handle=${item.objectHandle} file=${item.fileName}",
                        error,
                    )
                    eventQueue.send(
                        UsbImportSessionEvent.FileImportFailed(
                            captureId = item.id,
                            fileName = item.fileName,
                            reason = error.message ?: "导入过程中发生未知错误。",
                        ),
                    )
                }
            }

            if (!isSessionActive(generation)) return
            _state.value = UsbImportSessionState.Ready(
                deviceName = deviceName,
                usbModeLabel = modeLabel,
            )
        }
    }

    override suspend fun stop() {
        stopRequested = true
        cancelCatalogRefresh()
        sessionMutex.withLock {
            sessionGeneration += 1L
            activeRequest = null
            activeDeviceName = null
            activeDevice = null
            _catalog.value = emptyList()
            closeActiveSession()
            clearSessionCache()
            _state.value = UsbImportSessionState.Stopped
        }
    }

    private suspend fun readObjectToStaging(
        request: UsbImportSessionStartRequest,
        generation: Long,
        item: UsbCameraCatalogItem,
        stagingFile: File,
    ): CameraReadResult {
        val primarySession = ensureActiveSession(request, generation) ?: error("USB 会话不可用。")
        return runCatching {
            withContext(Dispatchers.IO) {
                primarySession.readObjectToFile(
                    handle = item.objectHandle,
                    expectedSizeBytes = item.sizeBytes,
                    target = stagingFile,
                )
            }
        }.recoverCatching { error ->
            rethrowImportCancellation(error)
            requireImportRetryAllowed(isSessionActive(generation))
            UsbDebugLogger.w(
                DebugTag,
                "import retry requested handle=${item.objectHandle} file=${item.fileName} reason=${error.message.orEmpty()}",
            )
            reopenActiveSession(request, generation)
            requireImportRetryAllowed(isSessionActive(generation))
            val retrySession = ensureActiveSession(request, generation) ?: error("USB 会话重建失败。")
            withContext(Dispatchers.IO) {
                retrySession.readObjectToFile(
                    handle = item.objectHandle,
                    expectedSizeBytes = item.sizeBytes,
                    target = stagingFile,
                )
            }
        }.getOrThrow()
    }

    private suspend fun startInternal(
        request: UsbImportSessionStartRequest,
        generation: Long,
    ) {
        val snapshot = usbConnectionMonitor.current()
        if (!snapshot.isConnected) {
            _state.value = UsbImportSessionState.Error(
                reason = snapshot.unavailableReason ?: "未检测到可用相机。",
                request = request,
            )
            return
        }
        if (!snapshot.hasPermission) {
            _state.value = UsbImportSessionState.Error(
                reason = snapshot.unavailableReason ?: "还没有 USB 访问权限。",
                request = request,
            )
            return
        }
        if (!snapshot.isReadyToBrowse) {
            _state.value = UsbImportSessionState.Error(
                reason = snapshot.unavailableReason ?: "当前 USB 会话暂时不可浏览。",
                request = request,
            )
            return
        }

        val simulated = DebugMtpSimulator.isEnabled(appContext)
        val device = selectCameraDevice()
        if (!simulated && device == null) {
            _state.value = UsbImportSessionState.Error(
                reason = "未找到可用相机设备。",
                request = request,
            )
            return
        }

        val session = withContext(Dispatchers.IO) {
            if (simulated) DebugMtpSimulator.openSession()
            else browseClient.openSession(requireNotNull(device), request.settings.usbModePreference)
        }
        if (!isSessionActive(generation)) {
            session.close()
            return
        }

        activeDevice = device
        activeSession = session
        activeDeviceName = snapshot.deviceName ?: "USB Camera"
        ensureSessionCacheDirectory()
        _state.value = UsbImportSessionState.Ready(
            deviceName = activeDeviceName.orEmpty(),
            usbModeLabel = snapshot.usbModeLabel ?: request.settings.usbModePreference.name,
        )
        launchCatalogRefresh(request, generation)
    }

    private fun launchCatalogRefresh(
        request: UsbImportSessionStartRequest,
        generation: Long,
    ) {
        val scanToken = ++catalogScanToken
        catalogRefreshJob = catalogScope.launch {
            sessionMutex.withLock {
                if (!isCatalogScanActive(generation, scanToken)) return@withLock
                runCatching {
                    refreshCatalogInternal(request, generation, scanToken)
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    UsbDebugLogger.e(
                        DebugTag,
                        "background refreshCatalog failed generation=$generation; session remains ready for import",
                        error,
                    )
                }
            }
        }
    }

    private fun cancelCatalogRefresh() {
        catalogScanToken += 1L
        val job = catalogRefreshJob ?: return
        catalogRefreshJob = null
        job.cancel()
    }

    private suspend fun refreshCatalogInternal(
        request: UsbImportSessionStartRequest,
        generation: Long,
        scanToken: Long? = null,
    ) {
        val session = ensureActiveSession(request, generation) ?: return
        val modeLabel = usbConnectionMonitor.current().usbModeLabel ?: request.settings.usbModePreference.name
        val publishIncrementally = _catalog.value.isEmpty()
        val items = withContext(Dispatchers.IO) {
            enumerateCatalog(
                session = session,
                device = activeDevice,
                modeLabel = modeLabel,
                generation = generation,
                publishIncrementally = publishIncrementally,
                shouldContinue = { scanToken == null || isCatalogScanActive(generation, scanToken) },
            )
        }
        if (scanToken != null && !isCatalogScanActive(generation, scanToken)) return
        if (!isSessionActive(generation)) return

        if (!publishIncrementally || _catalog.value != items) {
            _catalog.value = items
        }
        UsbDebugLogger.d(
            DebugTag,
            "refreshCatalog published count=${items.size} incremental=$publishIncrementally generation=$generation",
        )
        if (_state.value !is UsbImportSessionState.Importing) {
            _state.value = UsbImportSessionState.Ready(
                deviceName = activeDeviceName.orEmpty(),
                usbModeLabel = modeLabel,
            )
        }
    }

    private suspend fun ensureActiveSession(
        request: UsbImportSessionStartRequest,
        generation: Long,
    ): CameraBrowseSession? {
        if (!isSessionActive(generation)) return null
        val snapshot = usbConnectionMonitor.current()
        if (!snapshot.isReadyToBrowse) {
            _catalog.value = emptyList()
            _state.value = UsbImportSessionState.Error(
                reason = snapshot.unavailableReason ?: "USB 会话不可用。",
                request = request,
            )
            closeActiveSession()
            return null
        }

        val currentSession = activeSession
        if (currentSession != null) return currentSession

        val simulated = DebugMtpSimulator.isEnabled(appContext)
        val device = selectCameraDevice()
        if (!simulated && device == null) {
            _state.value = UsbImportSessionState.Error(
                reason = "未找到可用相机设备。",
                request = request,
            )
            return null
        }

        return runCatching {
            val session = withContext(Dispatchers.IO) {
                if (simulated) DebugMtpSimulator.openSession()
                else browseClient.openSession(requireNotNull(device), request.settings.usbModePreference)
            }
            if (!isSessionActive(generation)) {
                session.close()
                return null
            }
            activeDevice = device
            activeSession = session
            activeDeviceName = snapshot.deviceName ?: activeDeviceName ?: "USB Camera"
            UsbDebugLogger.d(
                DebugTag,
                "session reopened for active engine device=${device?.deviceName ?: "simulated"}",
            )
            session
        }.getOrElse { error ->
            rethrowImportCancellation(error)
            UsbDebugLogger.e(
                DebugTag,
                "ensureActiveSession failed device=${device?.deviceName ?: "simulated"} mode=${request.settings.usbModePreference.name}",
                error,
            )
            _state.value = UsbImportSessionState.Error(
                reason = error.message ?: "重新建立 USB 会话失败。",
                request = request,
            )
            null
        }
    }

    private suspend fun reopenActiveSession(
        request: UsbImportSessionStartRequest,
        generation: Long,
    ) {
        if (!isSessionActive(generation)) return
        closeActiveSession()
        val simulated = DebugMtpSimulator.isEnabled(appContext)
        val device = selectCameraDevice()
        if (!simulated && device == null) error("未找到可用相机设备。")
        val session = withContext(Dispatchers.IO) {
            if (simulated) DebugMtpSimulator.openSession()
            else browseClient.openSession(requireNotNull(device), request.settings.usbModePreference)
        }
        if (!isSessionActive(generation)) {
            session.close()
            return
        }
        activeDevice = device
        activeSession = session
        if (activeDeviceName.isNullOrBlank()) {
            activeDeviceName = usbConnectionMonitor.current().deviceName ?: "USB Camera"
        }
    }

    private fun enumerateCatalog(
        session: CameraBrowseSession,
        device: UsbDevice?,
        modeLabel: String,
        generation: Long,
        publishIncrementally: Boolean,
        shouldContinue: () -> Boolean,
    ): List<UsbCameraCatalogItem> {
        UsbDebugLogger.d(
            DebugTag,
            "enumerateCatalog start device=${device?.deviceName ?: "unknown"} mode=$modeLabel generation=$generation incremental=$publishIncrementally",
        )
        val cacheDir = ensureSessionCacheDirectory()
        val previewByKey = linkedMapOf<String, String>()
        val rows = mutableListOf<CatalogRow>()
        val visitedHandles = mutableSetOf<Int>()
        val publisher = CatalogProgressPublisher(
            generation = generation,
            enabled = publishIncrementally,
            rows = rows,
            previewByKey = previewByKey,
        )

        logDeviceCapabilities(device, modeLabel, session)
        if (session.storageIds.isEmpty()) {
            UsbDebugLogger.w(
                DebugTag,
                "enumerateCatalog storageIds-empty mode=$modeLabel device=${device?.deviceName ?: "unknown"}",
            )
        }
        var successfulStorageCount = 0
        var firstStorageFailure: Throwable? = null
        session.storageIds.forEach { storageId ->
            runCatching {
                UsbDebugLogger.d(
                    DebugTag,
                    "enumerateCatalog storageId=$storageId (${storageId.toHexString()}) rootParent=${UsbBulkMtpProtocol.RootParentHandle}",
                )
                logStorageInfo(session.getStorageInfo(storageId))
                collectObjects(
                    session = session,
                    storageId = storageId,
                    parentHandle = UsbBulkMtpProtocol.RootParentHandle,
                    generation = generation,
                    rows = rows,
                    previewByKey = previewByKey,
                    cacheDir = cacheDir,
                    publisher = publisher,
                    visitedHandles = visitedHandles,
                    shouldContinue = shouldContinue,
                )
                successfulStorageCount += 1
            }.onFailure { error ->
                if (firstStorageFailure == null) {
                    firstStorageFailure = error
                }
                UsbDebugLogger.e(
                    DebugTag,
                    "enumerateCatalog storage failed storageId=$storageId generation=$generation",
                    error,
                )
            }
        }
        requireCatalogEnumerationSucceeded(
            storageCount = session.storageIds.size,
            successfulStorageCount = successfulStorageCount,
            firstFailure = firstStorageFailure,
        )

        publisher.publish(force = true)
        if (rows.isEmpty()) {
            UsbDebugLogger.w(
                DebugTag,
                "enumerateCatalog empty-result storageCount=${session.storageIds.size} device=${device?.deviceName ?: "unknown"} mode=$modeLabel",
            )
        }
        UsbDebugLogger.d(
            DebugTag,
            "enumerateCatalog done rows=${rows.size} previews=${previewByKey.size} storageCount=${session.storageIds.size}",
        )
        return buildCatalogItems(rows, previewByKey)
    }

    private fun collectObjects(
        session: CameraBrowseSession,
        storageId: Int,
        parentHandle: Int,
        generation: Long,
        rows: MutableList<CatalogRow>,
        previewByKey: MutableMap<String, String>,
        cacheDir: File,
        publisher: CatalogProgressPublisher,
        visitedHandles: MutableSet<Int>,
        shouldContinue: () -> Boolean,
    ) {
        if (!shouldContinue()) return

        val handles = session.getObjectHandles(storageId, parentHandle)
        UsbDebugLogger.d(
            DebugTag,
            "collectObjects handles=${handles.size} storageId=$storageId (${storageId.toHexString()}) parentHandle=$parentHandle",
        )
        // Sony allocates increasing object handles for newer captures. Read in reverse so
        // the first incremental catalog updates contain the newest photos.
        handles.reversedArray().forEach { handle ->
            if (!shouldContinue()) return
            if (!visitedHandles.add(handle)) return@forEach

            val info = session.getObjectInfo(handle)
            if (info == null) {
                UsbDebugLogger.w(
                    DebugTag,
                    "object skip handle=$handle storageId=$storageId parentHandle=$parentHandle reason=objectInfo-null",
                )
                return@forEach
            }
            // Sony A7 III returns descendants even when GetObjectHandles requests the root.
            // The parent field is metadata, not a reliable filter for this catalog walk.
            if (!matchesExpectedParent(info.parentHandle, parentHandle)) {
                UsbDebugLogger.d(
                    DebugTag,
                    "object handle=$handle storageId=$storageId requestedParent=$parentHandle actualParent=${info.parentHandle} action=accept-descendant",
                )
            }
            logObjectInfo(storageId, parentHandle, handle, info)

            if (isAssociation(info)) {
                collectObjects(
                    session = session,
                    storageId = storageId,
                    parentHandle = handle,
                    generation = generation,
                    rows = rows,
                    previewByKey = previewByKey,
                    cacheDir = cacheDir,
                    publisher = publisher,
                    visitedHandles = visitedHandles,
                    shouldContinue = shouldContinue,
                )
                return@forEach
            }

            val fileName = info.fileName
            if (fileName.isBlank()) {
                UsbDebugLogger.w(
                    DebugTag,
                    "object skip handle=$handle storageId=$storageId reason=blank-name format=${info.formatCode.toFormatLabel()}",
                )
                return@forEach
            }

            val fileType = detectFileType(info)
            if (fileType == null) {
                UsbDebugLogger.w(
                    DebugTag,
                    "object skip handle=$handle storageId=$storageId name=$fileName format=${info.formatCode.toFormatLabel()} reason=unsupported-format",
                )
                return@forEach
            }

            val row = CatalogRow(
                id = "$storageId:$handle",
                objectHandle = handle,
                storageId = storageId,
                fileName = fileName,
                fileType = fileType,
                sizeBytes = info.compressedSizeBytes,
                capturedAtEpochMillis = ((info.dateCreatedEpochSeconds ?: info.dateModifiedEpochSeconds)
                    ?.times(1000L)) ?: System.currentTimeMillis(),
            )
            insertRowDescending(rows, row)
            publisher.onRowDiscovered()

            val thumbnailUri = writeThumbnailFile(
                session = session,
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
        session: CameraBrowseSession,
        objectHandle: Int,
        fileName: String,
        fileType: StoredFileType,
        cacheDir: File,
    ): String? {
        val result = session.getThumbnail(objectHandle) ?: return null
        val extension = when (fileType) {
            StoredFileType.JPEG,
            StoredFileType.RAW,
            -> "jpg"
            StoredFileType.OTHER -> "bin"
        }
        val safeName = fileName.substringBeforeLast('.', fileName)
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        val target = File(cacheDir, "${objectHandle}_${safeName}_thumb.$extension")
        return runCatching {
            cacheDir.mkdirs()
            target.writeBytes(result.bytes)
            target.toUriString()
        }.getOrElse { error ->
            UsbDebugLogger.w(
                DebugTag,
                "thumbnail write failed handle=$objectHandle file=$fileName error=${error.message.orEmpty()}",
            )
            null
        }
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
                    row.fileType == StoredFileType.RAW && previewUri == row.thumbnailUri && previewUri != null ->
                        "RAW 内嵌预览"
                    row.fileType == StoredFileType.RAW && previewUri != null ->
                        "配套 JPEG 预览"
                    previewUri != null -> "JPEG 缩略图"
                    else -> "无预览"
                },
            )
        }
    }

    private fun insertRowDescending(
        rows: MutableList<CatalogRow>,
        row: CatalogRow,
    ) {
        rows += row
        rows.sortWith(
            compareByDescending<CatalogRow> { it.capturedAtEpochMillis }
                .thenByDescending { it.objectHandle },
        )
    }

    private fun detectFileType(info: CameraObjectDescriptor): StoredFileType? {
        return when (info.formatCode) {
            UsbBulkMtpProtocol.ObjectFormatExifJpeg,
            UsbBulkMtpProtocol.ObjectFormatJfif,
            UsbBulkMtpProtocol.ObjectFormatPng,
            -> StoredFileType.JPEG
            UsbBulkMtpProtocol.ObjectFormatDng,
            UsbBulkMtpProtocol.ObjectFormatSonyRaw,
            -> StoredFileType.RAW
            else -> detectFileTypeFromExtension(info.fileName)
        }
    }

    private fun detectFileTypeFromExtension(fileName: String): StoredFileType? {
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg", "png" -> StoredFileType.JPEG
            "arw", "raw", "dng" -> StoredFileType.RAW
            else -> null
        }
    }

    private fun logObjectInfo(
        storageId: Int,
        parentHandle: Int,
        handle: Int,
        info: CameraObjectDescriptor,
    ) {
        UsbDebugLogger.d(
            DebugTag,
            "object info storageId=$storageId (${storageId.toHexString()}) parentHandle=$parentHandle handle=$handle name=${info.fileName} format=${info.formatCode.toFormatLabel()} size=${info.compressedSizeBytes} thumbFormat=${info.thumbFormatCode.toFormatLabel()} thumbSize=${info.thumbCompressedSizeBytes} sequence=${info.sequenceNumber}",
        )
    }

    private fun logDeviceCapabilities(
        device: UsbDevice?,
        modeLabel: String,
        session: CameraBrowseSession,
    ) {
        val info = session.deviceDescriptor
        UsbDebugLogger.d(
            DebugTag,
            "device info mode=$modeLabel deviceName=${device?.deviceName ?: "unknown"} manufacturer=${device?.manufacturerName.orEmpty()} product=${device?.productName.orEmpty()} interfaces=${device?.interfaceCount ?: 0} backend=${session.backendLabel} storageIds=${session.storageIds.joinToString(prefix = "[", postfix = "]")}",
        )
        UsbDebugLogger.d(
            DebugTag,
            "device capabilities manufacturer=${info.manufacturer} model=${info.model} version=${info.version} serial=${info.serialNumber} operations=${info.operationsSupported.size} events=${info.eventsSupported.size}",
        )
    }

    private fun logStorageInfo(info: CameraStorageDescriptor?) {
        if (info == null) {
            UsbDebugLogger.w(DebugTag, "storage info unavailable")
            return
        }
        UsbDebugLogger.d(
            DebugTag,
            "storage info id=${info.storageId} (${info.storageId.toHexString()}) description=${info.description} volume=${info.volumeIdentifier} freeSpace=${info.freeSpaceBytes} maxCapacity=${info.maxCapacityBytes}",
        )
    }

    private fun isAssociation(info: CameraObjectDescriptor): Boolean {
        return info.formatCode == UsbBulkMtpProtocol.ObjectFormatAssociation
    }

    private fun matchesExpectedParent(
        objectParentHandle: Int,
        requestedParentHandle: Int,
    ): Boolean {
        return if (requestedParentHandle == UsbBulkMtpProtocol.RootParentHandle) {
            objectParentHandle == 0 || objectParentHandle == UsbBulkMtpProtocol.RootParentHandle
        } else {
            objectParentHandle == requestedParentHandle
        }
    }

    private fun selectCameraDevice(): UsbDevice? {
        return usbManager.selectRawBridgeCameraDevice(requirePermission = true)
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

    private fun closeActiveSession() {
        runCatching { activeSession?.close() }
        activeSession = null
    }

    private fun beginNewSession(request: UsbImportSessionStartRequest): Long {
        closeActiveSession()
        sessionGeneration += 1L
        stopRequested = false
        activeRequest = request
        _catalog.value = emptyList()
        return sessionGeneration
    }

    private fun failActiveSession(
        request: UsbImportSessionStartRequest,
        reason: String,
        generation: Long,
        clearCatalog: Boolean,
    ) {
        if (!isSessionActive(generation)) return
        if (clearCatalog) {
            _catalog.value = emptyList()
        }
        closeActiveSession()
        _state.value = UsbImportSessionState.Error(
            reason = reason,
            request = request,
        )
    }

    private fun isSessionActive(generation: Long): Boolean {
        return !stopRequested &&
            generation == sessionGeneration &&
            activeRequest != null
    }

    private fun isCatalogScanActive(
        generation: Long,
        scanToken: Long,
    ): Boolean {
        return isSessionActive(generation) && scanToken == catalogScanToken
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
            if (!enabled && !force) return
            if (!isSessionActive(generation)) return
            _catalog.value = buildCatalogItems(rows, previewByKey)
            lastPublishedAtMillis = System.currentTimeMillis()
            lastPublishedCount = rows.size
            pendingThumbnailUpdates = 0
        }

        private fun maybePublish(force: Boolean = false) {
            if (!enabled) return
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
        const val DebugTag = "RawBridgeUsbDebug"
        const val EventQueueCapacity = 32
        const val FirstScreenPublishCount = 8
        const val IncrementalPublishBatchSize = 12
        const val ThumbnailPublishBatchSize = 4
        const val IncrementalPublishIntervalMillis = 160L
    }
}

private fun Int.toHexString(): String = "0x${toString(16).uppercase()}"

private fun Int.toFormatLabel(): String = "$this (${toHexString()})"

internal fun requireCatalogEnumerationSucceeded(
    storageCount: Int,
    successfulStorageCount: Int,
    firstFailure: Throwable?,
) {
    if (storageCount > 0 && successfulStorageCount == 0 && firstFailure != null) {
        throw IllegalStateException("无法读取相机存储目录。", firstFailure)
    }
}

internal fun requireImportRetryAllowed(isSessionActive: Boolean) {
    check(isSessionActive) { "USB 导入会话已停止，不能重试。" }
}

internal fun rethrowImportCancellation(error: Throwable) {
    if (error is CancellationException) throw error
}
