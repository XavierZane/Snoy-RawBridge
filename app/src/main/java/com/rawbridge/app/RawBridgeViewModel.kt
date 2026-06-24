package com.rawbridge.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rawbridge.backend.TransferBackend
import com.rawbridge.backend.config.ReceiverSettings
import com.rawbridge.backend.config.UsbModePreference
import com.rawbridge.backend.history.SessionLifecycleStatus
import com.rawbridge.backend.history.TransferFileRecord
import com.rawbridge.backend.history.TransferRecordStatus
import com.rawbridge.backend.history.TransferSession
import com.rawbridge.backend.platform.UsbConnectionSnapshot
import com.rawbridge.backend.platform.usb.UsbCameraCatalogItem
import com.rawbridge.backend.runtime.ReceiverRuntimeState
import com.rawbridge.backend.runtime.ReceiverServiceStatus
import com.rawbridge.backend.storage.StoredFileType
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class RawBridgeViewModel(
    private val transferBackend: TransferBackend,
    private val uiPreferencesRepository: RawBridgeUiPreferencesRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(initialBackendUiState())
    val uiState: StateFlow<RawBridgeUiState> = _uiState.asStateFlow()

    private var latestSettings: ReceiverSettings = ReceiverSettings()
    private var latestUiPreferences: RawBridgeUiPreferences = RawBridgeUiPreferences()
    private var latestRuntimeState: ReceiverRuntimeState = ReceiverRuntimeState()
    private var latestHistoryRecords: List<TransferFileRecord> = emptyList()
    private var latestSessions: List<TransferSession> = emptyList()
    private var latestCatalog: List<UsbCameraCatalogItem> = emptyList()
    private var pendingImportSelection: List<String>? = null
    private var lastObservedConnected: Boolean? = null
    private var lastObservedReadyToBrowse: Boolean? = null
    private var connectionNoticeCounter: Long = 0

    init {
        observeUiPreferences()
        observeUsbSnapshots()
        observeSettings()
        observeRuntimeState()
        observeHistoryRecords()
        observeSessions()
        observeCatalog()
    }

    fun selectScreen(screen: RawBridgeScreen) {
        _uiState.update {
            it.copy(
                currentScreen = screen,
                setupDestination = SetupDestination.Main,
            )
        }
    }

    fun openSetupAbout() {
        _uiState.update {
            it.copy(
                currentScreen = RawBridgeScreen.Setup,
                setupDestination = SetupDestination.About,
            )
        }
    }

    fun closeSetupAbout() {
        _uiState.update { it.copy(setupDestination = SetupDestination.Main) }
    }

    fun setUsbModePreference(mode: UsbModePreference) {
        _uiState.update { it.copy(config = it.config.copy(usbModePreference = mode)) }
    }

    fun updateDirectory(value: String) {
        _uiState.update { it.copy(config = it.config.copy(saveDirectory = value)) }
    }

    fun setThemeMode(themeMode: ThemeMode) {
        _uiState.update { it.copy(themeMode = themeMode) }
        persistUiPreferencesFromState()
    }

    fun setAutoDateFolder(enabled: Boolean) {
        _uiState.update { it.copy(config = it.config.copy(autoCreateDateFolder = enabled)) }
    }

    fun setSplitStorage(enabled: Boolean) {
        _uiState.update { it.copy(config = it.config.copy(splitRawAndJpeg = enabled)) }
    }

    fun setClearPreviewCacheOnDisconnect(enabled: Boolean) {
        _uiState.update { it.copy(config = it.config.copy(clearPreviewCacheOnDisconnect = enabled)) }
    }

    fun saveConfiguration() {
        val settings = _uiState.value.config.toReceiverSettings()
        viewModelScope.launch {
            transferBackend.settingsRepository.save(settings)
            latestSettings = settings
            persistUiPreferences(
                latestUiPreferences.copy(hasCompletedOnboarding = true),
            )
            val snapshot = transferBackend.usbConnectionMonitor.refresh(settings.usbModePreference)
            maybeAutoManageUsbSession(snapshot)
            syncUiFromBackend(
                snackbarMessage = "配置已保存，可以返回主页开始导入。",
                forceScreen = RawBridgeScreen.Home,
                onboardingCompleted = true,
            )
        }
    }

    fun runConnectionCheck() {
        _uiState.update { it.copy(connectionCheckState = ConnectionCheckState.Checking) }
        viewModelScope.launch {
            val snapshot = transferBackend.usbConnectionMonitor.refresh(
                _uiState.value.config.usbModePreference,
            )
            val result = buildConnectionResult(snapshot)
            maybeAutoManageUsbSession(snapshot)

            _uiState.update { state ->
                state.copy(
                    config = state.config.copy(
                        connectedDeviceName = snapshot.deviceName ?: state.config.connectedDeviceName,
                        usbModeLabel = snapshot.usbModeLabel ?: state.config.usbModeLabel,
                    ),
                    connectionCheckState = result,
                )
            }
        }
    }

    fun refreshCatalog() {
        val state = _uiState.value
        if (state.serviceState == ReceiverServiceState.Receiving) {
            _uiState.update {
                it.copy(snackbarMessage = "当前正在导入，请等待本轮完成后再重新扫描。")
            }
            return
        }

        _uiState.update {
            it.copy(
                connectionCheckState = ConnectionCheckState.Checking,
                snackbarMessage = "正在重新扫描相机文件…",
            )
        }

        if (latestRuntimeState.status == ReceiverServiceStatus.Stopped ||
            latestRuntimeState.status == ReceiverServiceStatus.Error
        ) {
            startReceiver()
        } else {
            transferBackend.runtimeController.refreshCatalog()
        }
    }

    fun startReceiver() {
        val state = _uiState.value
        if (!state.isConfigured) {
            _uiState.update {
                it.copy(
                    currentScreen = RawBridgeScreen.Setup,
                    snackbarMessage = "请先保存 USB 导入参数，再启动会话。",
                )
            }
            return
        }
        if (latestRuntimeState.status == ReceiverServiceStatus.Starting ||
            state.serviceState == ReceiverServiceState.Ready ||
            state.serviceState == ReceiverServiceState.Receiving
        ) {
            return
        }

        transferBackend.runtimeController.startReceiver()
        _uiState.update {
            it.copy(
                currentScreen = RawBridgeScreen.Home,
                snackbarMessage = "正在启动 USB 会话，请稍候…",
            )
        }
    }

    fun stopReceiver() {
        pendingImportSelection = null
        transferBackend.runtimeController.stopReceiver()
        _uiState.update {
            it.copy(
                currentScreen = RawBridgeScreen.Home,
                snackbarMessage = "正在停止 USB 会话。",
            )
        }
    }

    fun selectCaptureFilter(filter: CapturePickerFilter) {
        _uiState.update { state ->
            val nextFilter = if (
                filter == CapturePickerFilter.Selected &&
                !state.isMultiSelectMode
            ) {
                CapturePickerFilter.All
            } else {
                filter
            }
            val nextFiltered = state.captureLibrary.filteredBy(nextFilter, state.selectedCaptureIds)
            val nextFocusedId = state.focusedCaptureId?.takeIf { focusedId ->
                nextFiltered.any { it.id == focusedId }
            } ?: nextFiltered.firstOrNull()?.id ?: state.focusedCaptureId

            state.copy(
                captureFilter = nextFilter,
                focusedCaptureId = nextFocusedId,
            )
        }
        persistUiPreferencesFromState()
    }

    fun focusCapture(captureId: String) {
        _uiState.update { state ->
            if (state.captureLibrary.none { it.id == captureId }) {
                state
            } else {
                state.copy(focusedCaptureId = captureId)
            }
        }
    }

    fun toggleMultiSelectMode() {
        _uiState.update { state ->
            if (state.activeTransfer != null) {
                state
            } else {
                val leavingMultiSelect = state.isMultiSelectMode
                val nextFilter = if (
                    leavingMultiSelect &&
                    state.captureFilter == CapturePickerFilter.Selected
                ) {
                    CapturePickerFilter.All
                } else {
                    state.captureFilter
                }
                val nextFiltered = state.captureLibrary.filteredBy(nextFilter, state.selectedCaptureIds)
                val nextFocusedId = state.focusedCaptureId?.takeIf { focusedId ->
                    nextFiltered.any { it.id == focusedId }
                } ?: nextFiltered.firstOrNull()?.id ?: state.focusedCaptureId

                state.copy(
                    isMultiSelectMode = !leavingMultiSelect,
                    captureFilter = nextFilter,
                    focusedCaptureId = nextFocusedId,
                )
            }
        }
        persistUiPreferencesFromState()
    }

    fun toggleCaptureSelection(captureId: String) {
        _uiState.update { state ->
            if (state.activeTransfer != null || state.captureLibrary.none { it.id == captureId }) {
                state
            } else {
                val nextSelection = state.selectedCaptureIds.toMutableSet().apply {
                    if (!add(captureId)) {
                        remove(captureId)
                    }
                }
                state.copy(
                    isMultiSelectMode = true,
                    selectedCaptureIds = nextSelection,
                    focusedCaptureId = captureId,
                )
            }
        }
        persistUiPreferencesFromState()
    }

    fun selectAllVisibleCaptures() {
        _uiState.update { state ->
            if (state.activeTransfer != null) {
                state
            } else {
                val visibleIds = state.filteredCaptureLibrary.map { it.id }.toSet()
                if (visibleIds.isEmpty()) {
                    return@update state
                }
                val nextSelection = if (visibleIds.all { it in state.selectedCaptureIds }) {
                    state.selectedCaptureIds - visibleIds
                } else {
                    state.selectedCaptureIds + visibleIds
                }
                state.copy(
                    isMultiSelectMode = true,
                    selectedCaptureIds = nextSelection,
                )
            }
        }
        persistUiPreferencesFromState()
    }

    fun clearSelectedCaptures() {
        _uiState.update { state ->
            if (state.activeTransfer != null) {
                state
            } else {
                state.copy(
                    captureFilter = CapturePickerFilter.All,
                    isMultiSelectMode = false,
                    selectedCaptureIds = emptySet(),
                )
            }
        }
        persistUiPreferencesFromState()
    }

    fun runImportSelected() {
        val state = _uiState.value
        if (state.selectedCaptureIds.isEmpty()) {
            _uiState.update {
                it.copy(snackbarMessage = "先从图库中选中要导入的 RAW 或 JPEG。")
            }
            return
        }

        when (state.serviceState) {
            ReceiverServiceState.Stopped -> {
                pendingImportSelection = state.selectedCaptureIds.toList()
                startReceiver()
                _uiState.update {
                    it.copy(snackbarMessage = "正在建立 USB 会话，就绪后会自动开始导入。")
                }
            }

            ReceiverServiceState.Ready -> {
                pendingImportSelection = null
                transferBackend.runtimeController.importSelected(state.selectedCaptureIds.toList())
            }

            ReceiverServiceState.Receiving -> _uiState.update {
                it.copy(snackbarMessage = "当前已有导入进行中，请等待本轮完成。")
            }
        }
    }

    fun selectHistoryFilter(filter: HistoryFilter) {
        _uiState.update { it.copy(historyFilter = filter) }
    }

    fun toggleRecordExpanded(recordId: String) {
        _uiState.update {
            it.copy(expandedRecordId = if (it.expandedRecordId == recordId) null else recordId)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            transferBackend.historyRepository.clearAll()
            latestHistoryRecords = emptyList()
            latestSessions = emptyList()
            syncUiFromBackend(snackbarMessage = "导入历史已清空。")
            _uiState.update { it.copy(expandedRecordId = null) }
        }
    }

    fun consumeSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    fun consumeConnectionNotice() {
        _uiState.update { it.copy(connectionNotice = null) }
    }

    private fun observeUiPreferences() {
        viewModelScope.launch {
            uiPreferencesRepository.preferences.collect { preferences ->
                latestUiPreferences = preferences
                syncUiFromBackend()
            }
        }
    }

    private fun observeUsbSnapshots() {
        viewModelScope.launch {
            transferBackend.usbConnectionMonitor.snapshots.collect { snapshot ->
                maybeEmitConnectionNotice(snapshot)
                if (!snapshot.isReadyToBrowse) {
                    pendingImportSelection = null
                    if (latestUiPreferences.selectedCaptureIds.isNotEmpty() || latestUiPreferences.isMultiSelectMode) {
                        persistUiPreferences(
                            latestUiPreferences.copy(
                                isMultiSelectMode = false,
                                selectedCaptureIds = emptySet(),
                            ),
                        )
                    }
                }
                maybeAutoManageUsbSession(snapshot)
                syncUiFromBackend()
            }
        }
    }

    private fun maybeEmitConnectionNotice(snapshot: UsbConnectionSnapshot) {
        val previousConnected = lastObservedConnected
        val previousReadyToBrowse = lastObservedReadyToBrowse

        if (previousConnected == null || previousReadyToBrowse == null) {
            lastObservedConnected = snapshot.isConnected
            lastObservedReadyToBrowse = snapshot.isReadyToBrowse
            return
        }

        when {
            previousConnected && !snapshot.isConnected -> {
                showConnectionNotice(
                    kind = ConnectionNoticeKind.Disconnected,
                    message = "\u5df2\u65ad\u5f00",
                )
            }

            !previousReadyToBrowse && snapshot.isReadyToBrowse -> {
                showConnectionNotice(
                    kind = ConnectionNoticeKind.Connected,
                    message = "\u5df2\u8fde\u63a5",
                )
            }
        }

        lastObservedConnected = snapshot.isConnected
        lastObservedReadyToBrowse = snapshot.isReadyToBrowse
    }

    private fun showConnectionNotice(
        kind: ConnectionNoticeKind,
        message: String,
    ) {
        connectionNoticeCounter += 1
        _uiState.update {
            it.copy(
                connectionNotice = ConnectionNotice(
                    id = connectionNoticeCounter,
                    kind = kind,
                    message = message,
                ),
            )
        }
    }

    private fun observeSettings() {
        viewModelScope.launch {
            transferBackend.settingsRepository.settings.collect { settings ->
                latestSettings = settings
                val snapshot = transferBackend.usbConnectionMonitor.refresh(settings.usbModePreference)
                maybeAutoManageUsbSession(snapshot)
                syncUiFromBackend()
            }
        }
    }

    private fun observeRuntimeState() {
        viewModelScope.launch {
            transferBackend.runtimeState.collect { runtimeState ->
                latestRuntimeState = runtimeState
                when (runtimeState.status) {
                    ReceiverServiceStatus.Ready -> runPendingImportIfNeeded()
                    ReceiverServiceStatus.Stopped,
                    ReceiverServiceStatus.Error,
                    -> pendingImportSelection = null
                    else -> Unit
                }
                syncUiFromBackend(
                    snackbarMessage = runtimeState.message.takeIf {
                        runtimeState.status == ReceiverServiceStatus.Error
                    },
                )
            }
        }
    }

    private fun observeHistoryRecords() {
        viewModelScope.launch {
            transferBackend.historyRepository.observeRecentRecords().collect { records ->
                latestHistoryRecords = records
                syncUiFromBackend()
            }
        }
    }

    private fun observeSessions() {
        viewModelScope.launch {
            transferBackend.historyRepository.observeRecentSessions(limit = 1).collect { sessions ->
                latestSessions = sessions
                syncUiFromBackend()
            }
        }
    }

    private fun observeCatalog() {
        viewModelScope.launch {
            transferBackend.usbImportSessionEngine.catalog.collect { catalog ->
                latestCatalog = catalog
                trimPersistedSelectionToCatalog()
                runPendingImportIfNeeded()
                syncUiFromBackend()
            }
        }
    }

    private fun syncUiFromBackend(
        snackbarMessage: String? = null,
        forceScreen: RawBridgeScreen? = null,
        onboardingCompleted: Boolean? = null,
    ) {
        val usbSnapshot = transferBackend.usbConnectionMonitor.current()
        val mostRecentSession = latestSessions.firstOrNull()

        _uiState.update { state ->
            val nextConfig = state.config
                .merge(latestSettings)
                .copy(
                    connectedDeviceName = latestRuntimeState.connectedDeviceName
                        ?: usbSnapshot.deviceName
                        ?: state.config.connectedDeviceName,
                    usbModeLabel = latestRuntimeState.usbModeLabel
                        ?: usbSnapshot.usbModeLabel
                        ?: state.config.usbModeLabel,
                )
            val nextLibrary = latestCatalog.map { it.toUiModel() }
            val validSelection = latestUiPreferences.selectedCaptureIds.filterTo(mutableSetOf()) { selectedId ->
                nextLibrary.any { it.id == selectedId }
            }
            val persistedMultiSelectMode = latestUiPreferences.isMultiSelectMode
            val nextFilter = when {
                latestUiPreferences.captureFilter == CapturePickerFilter.Selected &&
                    (validSelection.isEmpty() || !persistedMultiSelectMode) ->
                    CapturePickerFilter.All
                else -> latestUiPreferences.captureFilter
            }
            val nextFocus = state.focusedCaptureId?.takeIf { focusedId ->
                nextLibrary.any { it.id == focusedId }
            } ?: nextLibrary.firstOrNull()?.id
            val completedOnboarding = onboardingCompleted ?: latestUiPreferences.hasCompletedOnboarding

            state.copy(
                isConfigured = latestSettings.isValid(),
                hasCompletedOnboarding = completedOnboarding,
                themeMode = latestUiPreferences.themeMode,
                currentScreen = forceScreen ?: state.currentScreen,
                config = nextConfig,
                serviceState = latestRuntimeState.toUiState(),
                connectionCheckState = buildConnectionCheckState(
                    current = state.connectionCheckState,
                    snapshot = usbSnapshot,
                    hasCatalog = nextLibrary.isNotEmpty(),
                    runtimeState = latestRuntimeState,
                ),
                captureLibrary = nextLibrary,
                captureFilter = nextFilter,
                selectedCaptureIds = validSelection,
                focusedCaptureId = nextFocus,
                isMultiSelectMode = persistedMultiSelectMode,
                historyRecords = latestHistoryRecords.map { it.toUiModel() },
                recentSession = mostRecentSession?.toUiSummary(),
                activeTransfer = buildActiveTransfer(
                    runtimeState = latestRuntimeState,
                    session = mostRecentSession,
                    records = latestHistoryRecords,
                ),
                snackbarMessage = snackbarMessage ?: state.snackbarMessage,
            )
        }
    }

    private fun trimPersistedSelectionToCatalog() {
        if (latestCatalog.isEmpty() || latestRuntimeState.status == ReceiverServiceStatus.Starting) {
            return
        }
        val availableIds = latestCatalog.map { it.id }.toSet()
        val trimmedSelection = latestUiPreferences.selectedCaptureIds.filterTo(mutableSetOf()) { it in availableIds }
        val trimmedFilter = when {
            latestUiPreferences.captureFilter == CapturePickerFilter.Selected &&
                (trimmedSelection.isEmpty() || !latestUiPreferences.isMultiSelectMode) ->
                CapturePickerFilter.All
            else -> latestUiPreferences.captureFilter
        }
        if (trimmedSelection != latestUiPreferences.selectedCaptureIds || trimmedFilter != latestUiPreferences.captureFilter) {
            persistUiPreferences(
                latestUiPreferences.copy(
                    captureFilter = trimmedFilter,
                    selectedCaptureIds = trimmedSelection,
                ),
            )
        }
    }

    private fun persistUiPreferencesFromState() {
        val snapshot = _uiState.value
        persistUiPreferences(
            latestUiPreferences.copy(
                hasCompletedOnboarding = snapshot.hasCompletedOnboarding,
                themeMode = snapshot.themeMode,
                captureFilter = snapshot.captureFilter,
                isMultiSelectMode = snapshot.isMultiSelectMode,
                selectedCaptureIds = snapshot.selectedCaptureIds,
            ),
        )
    }

    private fun persistUiPreferences(preferences: RawBridgeUiPreferences) {
        latestUiPreferences = preferences
        viewModelScope.launch {
            uiPreferencesRepository.save(preferences)
        }
    }

    private fun maybeAutoManageUsbSession(snapshot: UsbConnectionSnapshot) {
        if (!latestUiPreferences.hasCompletedOnboarding || !latestSettings.isValid()) {
            return
        }
        if (!snapshot.isReadyToBrowse) {
            return
        }

        when (latestRuntimeState.status) {
            ReceiverServiceStatus.Stopped,
            ReceiverServiceStatus.Error,
            -> transferBackend.runtimeController.startReceiver()

            ReceiverServiceStatus.Ready -> {
                if (latestCatalog.isEmpty()) {
                    transferBackend.runtimeController.refreshCatalog()
                }
            }

            ReceiverServiceStatus.Starting,
            ReceiverServiceStatus.Receiving,
            -> Unit
        }
    }

    private fun runPendingImportIfNeeded() {
        val pending = pendingImportSelection ?: return
        if (latestRuntimeState.status != ReceiverServiceStatus.Ready) {
            return
        }
        if (latestCatalog.isEmpty()) {
            transferBackend.runtimeController.refreshCatalog()
            return
        }

        val availableIds = latestCatalog.map { it.id }.toSet()
        val validPending = pending.filter { it in availableIds }
        pendingImportSelection = null

        if (validPending.isEmpty()) {
            _uiState.update {
                it.copy(snackbarMessage = "相机图库已刷新，但待导入文件已经不存在。")
            }
            return
        }

        transferBackend.runtimeController.importSelected(validPending)
    }

    companion object {
        fun factory(
            transferBackend: TransferBackend,
            uiPreferencesRepository: RawBridgeUiPreferencesRepository,
        ): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    check(modelClass.isAssignableFrom(RawBridgeViewModel::class.java)) {
                        "Unsupported ViewModel class: ${modelClass.name}"
                    }
                    return RawBridgeViewModel(
                        transferBackend = transferBackend,
                        uiPreferencesRepository = uiPreferencesRepository,
                    ) as T
                }
            }
        }
    }
}

private fun initialBackendUiState(): RawBridgeUiState {
    return RawBridgeUiState(
        currentScreen = RawBridgeScreen.Home,
    )
}

private fun ConnectionConfig.merge(settings: ReceiverSettings): ConnectionConfig {
    return copy(
        usbModePreference = settings.usbModePreference,
        saveDirectory = settings.saveRoot,
        autoCreateDateFolder = settings.autoCreateDateFolder,
        splitRawAndJpeg = settings.splitRawAndJpeg,
        clearPreviewCacheOnDisconnect = settings.clearPreviewCacheOnDisconnect,
    )
}

private fun ConnectionConfig.toReceiverSettings(): ReceiverSettings {
    val defaults = ReceiverSettings()
    return ReceiverSettings(
        usbModePreference = usbModePreference,
        saveRoot = saveDirectory.ifBlank { defaults.saveRoot },
        autoCreateDateFolder = autoCreateDateFolder,
        splitRawAndJpeg = splitRawAndJpeg,
        clearPreviewCacheOnDisconnect = clearPreviewCacheOnDisconnect,
    )
}

private fun ReceiverSettings.isValid(): Boolean {
    return saveRoot.isNotBlank()
}

private fun ReceiverRuntimeState.toUiState(): ReceiverServiceState {
    return when (status) {
        ReceiverServiceStatus.Stopped -> ReceiverServiceState.Stopped
        ReceiverServiceStatus.Starting -> ReceiverServiceState.Ready
        ReceiverServiceStatus.Ready -> ReceiverServiceState.Ready
        ReceiverServiceStatus.Receiving -> ReceiverServiceState.Receiving
        ReceiverServiceStatus.Error -> ReceiverServiceState.Stopped
    }
}

private fun buildConnectionResult(snapshot: UsbConnectionSnapshot): ConnectionCheckState.Result {
    return when {
        !snapshot.isConnected -> ConnectionCheckState.Result(
            success = false,
            message = snapshot.unavailableReason ?: "未检测到相机，请检查 USB 线和 OTG。",
        )

        !snapshot.hasPermission -> ConnectionCheckState.Result(
            success = false,
            message = snapshot.unavailableReason ?: "相机已连接，但还没有 USB 访问权限。",
        )

        !snapshot.isBrowsable -> ConnectionCheckState.Result(
            success = false,
            message = snapshot.unavailableReason ?: "当前 USB 模式暂时不可浏览，请切换到支持的 MTP/PTP 模式。",
        )

        else -> ConnectionCheckState.Result(
            success = true,
            message = "USB 已就绪，当前模式 ${snapshot.usbModeLabel ?: "MTP/PTP"}。",
        )
    }
}

private fun buildConnectionCheckState(
    current: ConnectionCheckState,
    snapshot: UsbConnectionSnapshot,
    hasCatalog: Boolean,
    runtimeState: ReceiverRuntimeState,
): ConnectionCheckState {
    return when {
        current is ConnectionCheckState.Checking &&
            runtimeState.status == ReceiverServiceStatus.Starting -> ConnectionCheckState.Checking
        runtimeState.status == ReceiverServiceStatus.Error -> ConnectionCheckState.Result(
            success = false,
            message = runtimeState.message,
        )
        !snapshot.isConnected -> ConnectionCheckState.Result(
            success = false,
            message = snapshot.unavailableReason ?: "未检测到相机，请检查 USB 连接。",
        )
        snapshot.isConnected && !snapshot.hasPermission -> ConnectionCheckState.Result(
            success = false,
            message = snapshot.unavailableReason ?: "请在系统弹窗中授权 USB 访问。",
        )
        !snapshot.isBrowsable -> ConnectionCheckState.Result(
            success = false,
            message = snapshot.unavailableReason ?: "当前 USB 模式暂时不可浏览，请切换到支持的 MTP/PTP 模式。",
        )
        hasCatalog || runtimeState.status == ReceiverServiceStatus.Ready ||
            runtimeState.status == ReceiverServiceStatus.Receiving -> ConnectionCheckState.Result(
            success = true,
            message = "USB 会话已就绪，可浏览并按需导入。",
        )
        else -> current
    }
}

private fun TransferFileRecord.toUiModel(): TransferHistoryRecord {
    return TransferHistoryRecord(
        id = recordId,
        fileName = savedFileName.ifBlank { originalFileName },
        fileType = fileType.toUiFileType(),
        sizeLabel = formatBytes(sizeBytes),
        status = when (status) {
            TransferRecordStatus.Success -> TransferResultStatus.Success
            TransferRecordStatus.Failed -> TransferResultStatus.Failed
        },
        savedPath = savedPath.orEmpty(),
        transferredAt = receivedAtEpochMillis.toLocalDateTime(),
        message = errorMessage,
    )
}

private fun TransferSession.toUiSummary(): TransferSessionSummary? {
    val finishedAt = finishedAtEpochMillis ?: return null
    return TransferSessionSummary(
        finishedAt = finishedAt.toLocalDateTime(),
        successCount = successCount,
        failedCount = failedCount,
        totalSizeLabel = formatBytes(totalBytes),
        destinationLabel = targetDirectory,
    )
}

private fun buildActiveTransfer(
    runtimeState: ReceiverRuntimeState,
    session: TransferSession?,
    records: List<TransferFileRecord>,
): ActiveTransfer? {
    val runningSession = session?.takeIf { it.status == SessionLifecycleStatus.Running } ?: return null
    val targetCount = runtimeState.totalCountHint
        .takeIf { it > 0 }
        ?: runningSession.totalCount
    if (runtimeState.status != ReceiverServiceStatus.Receiving && targetCount == 0) {
        return null
    }

    val sessionRecords = records
        .filter { it.sessionId == runningSession.sessionId }
        .sortedByDescending { it.receivedAtEpochMillis }
    val currentFileName = runtimeState.currentFileNameHint()
        ?: sessionRecords.firstOrNull()?.savedFileName
        ?: "等待下一张照片"
    val completedCount = runningSession.successCount + runningSession.failedCount
    val progress = when {
        targetCount <= 0 -> 0f
        else -> completedCount.toFloat() / targetCount.toFloat()
    }

    val batchItems = buildList {
        if (runtimeState.status == ReceiverServiceStatus.Receiving) {
            add(
                TransferBatchItem(
                    captureId = runtimeState.currentCaptureId ?: "receiving:$currentFileName",
                    fileName = currentFileName,
                    fileType = currentFileName.toUiFileType(),
                    sizeLabel = "--",
                    status = TransferItemStatus.Receiving,
                ),
            )
        }
        sessionRecords.take(6).forEach { record ->
            add(
                TransferBatchItem(
                    captureId = record.recordId,
                    fileName = record.savedFileName.ifBlank { record.originalFileName },
                    fileType = record.fileType.toUiFileType(),
                    sizeLabel = formatBytes(record.sizeBytes),
                    status = when (record.status) {
                        TransferRecordStatus.Success -> TransferItemStatus.Success
                        TransferRecordStatus.Failed -> TransferItemStatus.Failed
                    },
                ),
            )
        }
    }

    return ActiveTransfer(
        currentFileName = currentFileName,
        progress = progress.coerceIn(0f, 1f),
        receivedCount = completedCount,
        successCount = runningSession.successCount,
        failedCount = runningSession.failedCount,
        totalSizeLabel = formatBytes(runningSession.totalBytes),
        targetCount = targetCount.coerceAtLeast(1),
        batchItems = batchItems,
    )
}

private fun ReceiverRuntimeState.currentFileNameHint(): String? {
    if (status != ReceiverServiceStatus.Receiving) {
        return null
    }
    val prefix = "正在导入 "
    return if (message.startsWith(prefix) && message.length > prefix.length) {
        message.removePrefix(prefix)
    } else {
        null
    }
}

private fun UsbCameraCatalogItem.toUiModel(): CapturePreviewItem {
    val capturedAt = capturedAtEpochMillis.toLocalDateTime()
    val previewMood = when (fileType) {
        StoredFileType.JPEG -> CapturePreviewMood.WarmGlass
        StoredFileType.RAW -> CapturePreviewMood.CoolSky
        StoredFileType.OTHER -> CapturePreviewMood.DeepSlate
    }
    return CapturePreviewItem(
        id = id,
        fileName = fileName,
        fileType = fileType.toUiFileType(),
        sizeLabel = formatBytes(sizeBytes),
        sizeInMegabytes = sizeBytes.toDouble() / 1024.0 / 1024.0,
        shotTitle = fileName.substringBeforeLast('.', fileName),
        shotNote = previewSourceLabel,
        lensLabel = previewSourceLabel,
        capturedAt = capturedAt,
        previewMood = previewMood,
        contentUri = null,
        thumbnailUri = thumbnailUri,
    )
}

private fun StoredFileType.toUiFileType(): CaptureFileType {
    return when (this) {
        StoredFileType.JPEG -> CaptureFileType.Jpeg
        StoredFileType.RAW -> CaptureFileType.Raw
        StoredFileType.OTHER -> CaptureFileType.Raw
    }
}

private fun String.toUiFileType(): CaptureFileType {
    return when (substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> CaptureFileType.Jpeg
        else -> CaptureFileType.Raw
    }
}

private fun Long.toLocalDateTime(): LocalDateTime {
    return LocalDateTime.ofInstant(Instant.ofEpochMilli(this), ZoneId.systemDefault())
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) {
        return "0 MB"
    }

    val megabytes = bytes.toDouble() / 1024.0 / 1024.0
    return if (megabytes >= 1.0) {
        "${formatMegabytes(megabytes)} MB"
    } else {
        val kilobytes = (bytes / 1024.0).toInt().coerceAtLeast(1)
        "$kilobytes KB"
    }
}

private fun formatMegabytes(value: Double): String {
    return when {
        value == 0.0 -> "0"
        value < 100 -> String.format("%.1f", value)
        else -> value.toInt().toString()
    }
}

private fun List<CapturePreviewItem>.filteredBy(
    filter: CapturePickerFilter,
    selectedIds: Set<String>,
): List<CapturePreviewItem> = when (filter) {
    CapturePickerFilter.All -> this
    CapturePickerFilter.Raw -> filter { it.fileType == CaptureFileType.Raw }
    CapturePickerFilter.Jpeg -> filter { it.fileType == CaptureFileType.Jpeg }
    CapturePickerFilter.Selected -> filter { it.id in selectedIds }
}
