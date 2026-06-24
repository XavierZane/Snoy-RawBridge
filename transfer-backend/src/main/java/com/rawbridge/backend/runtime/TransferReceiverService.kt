package com.rawbridge.backend.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.rawbridge.backend.TransferBackendGraph
import com.rawbridge.backend.config.ReceiverSettings
import com.rawbridge.backend.history.SessionLifecycleStatus
import com.rawbridge.backend.platform.UsbConnectionSnapshot
import com.rawbridge.backend.platform.usb.UsbImportSessionEvent
import com.rawbridge.backend.platform.usb.UsbImportSessionStartRequest
import com.rawbridge.backend.platform.usb.UsbImportSessionState
import com.rawbridge.backend.storage.SaveIncomingFileRequest
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class TransferReceiverService : Service() {
    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO.limitedParallelism(1),
    )
    private val graph by lazy { TransferBackendGraph.services(applicationContext) }

    private var stateJob: Job? = null
    private var eventJob: Job? = null
    private var usbSnapshotJob: Job? = null

    private var activeSettings: ReceiverSettings? = null
    private var sessionTracker: TransferSessionTracker? = null
    private var pendingImportCaptureIds: List<String>? = null
    private var lastObservedState: UsbImportSessionState = UsbImportSessionState.Stopped
    private var pendingStoppedRuntimeState: ReceiverRuntimeState? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        observeSessionState()
        observeSessionEvents()
        observeUsbSnapshots()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ActionStop -> stopReceiver()
            ActionStart -> startReceiver()
            ActionRefreshCatalog -> refreshCatalog()
            ActionImportSelected -> importSelected(
                intent.getStringArrayExtra(ExtraCaptureIds).orEmpty().toList(),
            )
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stateJob?.cancel()
        eventJob?.cancel()
        usbSnapshotJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun observeSessionState() {
        stateJob = serviceScope.launch {
            graph.usbImportSessionEngine.state.collectLatest { state ->
                val previousState = lastObservedState
                lastObservedState = state

                if (previousState is UsbImportSessionState.Importing &&
                    state is UsbImportSessionState.Ready
                ) {
                    finalizeActiveSession(status = SessionLifecycleStatus.Completed)
                }

                if (state is UsbImportSessionState.Error) {
                    finalizeActiveSession(
                        status = SessionLifecycleStatus.Failed,
                        lastErrorMessage = state.reason,
                    )
                    publishRuntimeState(state.toRuntimeState())
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collectLatest
                }

                val runtimeState = if (state is UsbImportSessionState.Stopped) {
                    pendingStoppedRuntimeState?.also { pendingStoppedRuntimeState = null }
                        ?: state.toRuntimeState()
                } else {
                    state.toRuntimeState()
                }
                publishRuntimeState(runtimeState)

                if (state is UsbImportSessionState.Ready) {
                    consumePendingImportIfReady()
                }
            }
        }
    }

    private fun observeSessionEvents() {
        eventJob = serviceScope.launch {
            graph.usbImportSessionEngine.events.collect { event ->
                when (event) {
                    is UsbImportSessionEvent.FileImported -> persistImportedFile(event)
                    is UsbImportSessionEvent.FileImportFailed -> persistFailedImport(event)
                }
            }
        }
    }

    private fun observeUsbSnapshots() {
        usbSnapshotJob = serviceScope.launch {
            graph.usbConnectionMonitor.snapshots.collectLatest { snapshot ->
                val currentState = graph.usbImportSessionEngine.state.value
                if (snapshot.isReadyToBrowse) {
                    if (currentState is UsbImportSessionState.Ready &&
                        graph.usbImportSessionEngine.catalog.value.isEmpty()
                    ) {
                        graph.usbImportSessionEngine.refreshCatalog()
                        consumePendingImportIfReady()
                    }
                    return@collectLatest
                }

                if (currentState is UsbImportSessionState.Stopped) {
                    return@collectLatest
                }

                pendingImportCaptureIds = null
                val hadActiveBatch = sessionTracker != null
                val terminalState = ReceiverRuntimeState(
                    status = if (hadActiveBatch) {
                        ReceiverServiceStatus.Error
                    } else {
                        ReceiverServiceStatus.Stopped
                    },
                    connectedDeviceName = snapshot.deviceName,
                    usbModeLabel = snapshot.usbModeLabel,
                    message = snapshot.unavailableMessage(),
                )

                if (hadActiveBatch) {
                    finalizeActiveSession(
                        status = SessionLifecycleStatus.Failed,
                        lastErrorMessage = terminalState.message,
                    )
                }

                pendingStoppedRuntimeState = terminalState
                graph.usbImportSessionEngine.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun startReceiver() {
        serviceScope.launch {
            ensureReceiverSessionStarted()
        }
    }

    private fun stopReceiver() {
        serviceScope.launch {
            pendingImportCaptureIds = null
            finalizeActiveSession(status = SessionLifecycleStatus.Cancelled)

            pendingStoppedRuntimeState = ReceiverRuntimeState(
                status = ReceiverServiceStatus.Stopped,
                connectedDeviceName = latestConnectedDeviceName(),
                usbModeLabel = latestUsbModeLabel(),
                message = "\u5df2\u505c\u6b62 USB \u4f1a\u8bdd\u3002",
            )

            graph.usbImportSessionEngine.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun refreshCatalog() {
        serviceScope.launch {
            when (graph.usbImportSessionEngine.state.value) {
                UsbImportSessionState.Stopped,
                is UsbImportSessionState.Error,
                -> {
                    if (ensureReceiverSessionStarted()) {
                        graph.usbImportSessionEngine.refreshCatalog()
                    }
                }

                is UsbImportSessionState.Starting -> Unit
                is UsbImportSessionState.Ready,
                is UsbImportSessionState.Importing,
                -> graph.usbImportSessionEngine.refreshCatalog()
            }
        }
    }

    private fun importSelected(captureIds: List<String>) {
        if (captureIds.isEmpty()) return

        serviceScope.launch {
            when (graph.usbImportSessionEngine.state.value) {
                UsbImportSessionState.Stopped,
                is UsbImportSessionState.Error,
                -> {
                    pendingImportCaptureIds = captureIds
                    ensureReceiverSessionStarted()
                }

                is UsbImportSessionState.Starting -> {
                    pendingImportCaptureIds = captureIds
                }

                is UsbImportSessionState.Ready -> {
                    pendingImportCaptureIds = captureIds
                    consumePendingImportIfReady()
                }

                is UsbImportSessionState.Importing -> Unit
            }
        }
    }

    private suspend fun ensureReceiverSessionStarted(): Boolean {
        when (graph.usbImportSessionEngine.state.value) {
            is UsbImportSessionState.Starting,
            is UsbImportSessionState.Ready,
            is UsbImportSessionState.Importing,
            -> return true

            UsbImportSessionState.Stopped,
            is UsbImportSessionState.Error,
            -> Unit
        }

        val settings = graph.settingsRepository.current()
        activeSettings = settings

        val startingState = ReceiverRuntimeState(
            status = ReceiverServiceStatus.Starting,
            usbModeLabel = settings.usbModePreference.name,
            message = "\u6b63\u5728\u542f\u52a8 USB \u4f1a\u8bdd\u2026",
        )
        TransferRuntimeBus.publish(startingState)
        startForeground(NotificationId, buildNotification(startingState))

        val snapshot = graph.usbConnectionMonitor.refresh(settings.usbModePreference)
        if (!snapshot.isReadyToBrowse) {
            publishRuntimeState(
                ReceiverRuntimeState(
                    status = ReceiverServiceStatus.Stopped,
                    connectedDeviceName = snapshot.deviceName,
                    usbModeLabel = snapshot.usbModeLabel,
                    message = snapshot.startFailureMessage(),
                ),
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return false
        }

        graph.usbImportSessionEngine.start(
            UsbImportSessionStartRequest(settings = settings),
        )
        return graph.usbImportSessionEngine.state.value !is UsbImportSessionState.Error
    }

    private suspend fun consumePendingImportIfReady() {
        val pendingIds = pendingImportCaptureIds ?: return
        if (graph.usbImportSessionEngine.state.value !is UsbImportSessionState.Ready) {
            return
        }

        val catalogIds = graph.usbImportSessionEngine.catalog.value
            .map { it.id }
            .toSet()
        if (catalogIds.isEmpty()) {
            graph.usbImportSessionEngine.refreshCatalog()
        }

        val importIds = pendingIds
            .distinct()
            .filter { it in graph.usbImportSessionEngine.catalog.value.map { item -> item.id }.toSet() }
        pendingImportCaptureIds = null

        if (importIds.isEmpty()) {
            return
        }

        val settings = activeSettings ?: graph.settingsRepository.current().also { activeSettings = it }
        val tracker = TransferSessionTracker(settings = settings).apply {
            startBatch(importIds.size)
        }
        sessionTracker = tracker
        graph.historyRepository.upsertSession(tracker.runningSession())
        graph.usbImportSessionEngine.importSelected(importIds)
    }

    private suspend fun persistImportedFile(event: UsbImportSessionEvent.FileImported) {
        val tracker = sessionTracker ?: return
        val stagingFile = File(event.stagingPath)

        try {
            val savedFile = graph.incomingFileStore.saveIncomingFile(
                request = SaveIncomingFileRequest(
                    settings = tracker.settings,
                    originalFileName = event.fileName,
                    mimeType = event.fileName.toMimeType(),
                    receivedAt = Instant.ofEpochMilli(event.importedAtEpochMillis),
                ),
                source = stagingFile.inputStream(),
            )

            val record = tracker.recordSuccess(savedFile)
            graph.historyRepository.appendRecord(record)
            graph.historyRepository.upsertSession(tracker.runningSession())
        } catch (error: Throwable) {
            val failedRecord = tracker.recordFailure(
                UsbImportSessionEvent.FileImportFailed(
                    captureId = event.captureId,
                    fileName = event.fileName,
                    reason = error.message
                        ?: "\u4fdd\u5b58\u5bfc\u5165\u6587\u4ef6\u5931\u8d25\u3002",
                    importedAtEpochMillis = event.importedAtEpochMillis,
                ),
            )
            graph.historyRepository.appendRecord(failedRecord)
            graph.historyRepository.upsertSession(tracker.runningSession())
        } finally {
            stagingFile.delete()
        }
    }

    private suspend fun persistFailedImport(event: UsbImportSessionEvent.FileImportFailed) {
        val tracker = sessionTracker ?: return
        val record = tracker.recordFailure(event)
        graph.historyRepository.appendRecord(record)
        graph.historyRepository.upsertSession(tracker.runningSession())
    }

    private suspend fun finalizeActiveSession(
        status: SessionLifecycleStatus,
        lastErrorMessage: String? = null,
    ) {
        val tracker = sessionTracker ?: return
        tracker.markError(lastErrorMessage)
        val finalSession = tracker.finishedSession(status)
        graph.historyRepository.finishSession(
            sessionId = finalSession.sessionId,
            status = finalSession.status,
            totalCount = finalSession.totalCount,
            successCount = finalSession.successCount,
            failedCount = finalSession.failedCount,
            totalBytes = finalSession.totalBytes,
            finishedAtEpochMillis = finalSession.finishedAtEpochMillis ?: System.currentTimeMillis(),
            lastErrorMessage = finalSession.lastErrorMessage,
        )
        sessionTracker = null
    }

    private fun publishRuntimeState(state: ReceiverRuntimeState) {
        TransferRuntimeBus.publish(state)
        if (state.status != ReceiverServiceStatus.Stopped) {
            getSystemService(NotificationManager::class.java).notify(
                NotificationId,
                buildNotification(state),
            )
        }
    }

    private fun buildNotification(state: ReceiverRuntimeState): Notification {
        val contentText = buildString {
            append(state.message)
            if (!state.connectedDeviceName.isNullOrBlank()) {
                append(" / ")
                append(state.connectedDeviceName)
            }
            if (!state.usbModeLabel.isNullOrBlank()) {
                append(" / ")
                append(state.usbModeLabel)
            }
        }

        return NotificationCompat.Builder(this, ChannelId)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("RAWBridge USB")
            .setContentText(contentText)
            .setOngoing(state.status != ReceiverServiceStatus.Stopped)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(ChannelId) != null) return

        val channel = NotificationChannel(
            ChannelId,
            "RAWBridge USB",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "\u663e\u793a USB \u6d4f\u89c8\u4e0e\u5bfc\u5165\u4f1a\u8bdd\u72b6\u6001\u3002"
        }
        manager.createNotificationChannel(channel)
    }

    private fun UsbImportSessionState.toRuntimeState(): ReceiverRuntimeState {
        val lockedTotalCount = sessionTracker?.totalCount() ?: 0
        return when (this) {
            UsbImportSessionState.Stopped -> ReceiverRuntimeState(
                status = ReceiverServiceStatus.Stopped,
                connectedDeviceName = latestConnectedDeviceName(),
                usbModeLabel = latestUsbModeLabel(),
                message = "\u672a\u542f\u52a8 USB \u4f1a\u8bdd\u3002",
            )

            is UsbImportSessionState.Starting -> ReceiverRuntimeState(
                status = ReceiverServiceStatus.Starting,
                usbModeLabel = request.settings.usbModePreference.name,
                message = "\u6b63\u5728\u5efa\u7acb ${request.settings.usbModePreference.name} \u4f1a\u8bdd\u2026",
            )

            is UsbImportSessionState.Ready -> ReceiverRuntimeState(
                status = ReceiverServiceStatus.Ready,
                connectedDeviceName = deviceName,
                usbModeLabel = usbModeLabel,
                totalCountHint = lockedTotalCount,
                message = "\u76f8\u673a\u5df2\u8fde\u63a5\uff0c\u53ef\u6d4f\u89c8\u5e76\u6309\u9700\u5bfc\u5165\u3002",
            )

            is UsbImportSessionState.Importing -> ReceiverRuntimeState(
                status = ReceiverServiceStatus.Receiving,
                connectedDeviceName = deviceName,
                usbModeLabel = usbModeLabel,
                currentCaptureId = captureId,
                currentIndex = currentIndex,
                totalCountHint = lockedTotalCount.coerceAtLeast(totalCount),
                message = "\u6b63\u5728\u5bfc\u5165 $currentFileName",
            )

            is UsbImportSessionState.Error -> ReceiverRuntimeState(
                status = ReceiverServiceStatus.Error,
                connectedDeviceName = latestConnectedDeviceName(),
                usbModeLabel = latestUsbModeLabel(),
                totalCountHint = lockedTotalCount,
                message = reason,
            )
        }
    }

    private fun latestConnectedDeviceName(): String? {
        return when (val state = graph.usbImportSessionEngine.state.value) {
            is UsbImportSessionState.Ready -> state.deviceName
            is UsbImportSessionState.Importing -> state.deviceName
            else -> graph.usbConnectionMonitor.current().deviceName
        }
    }

    private fun latestUsbModeLabel(): String? {
        return when (val state = graph.usbImportSessionEngine.state.value) {
            is UsbImportSessionState.Ready -> state.usbModeLabel
            is UsbImportSessionState.Importing -> state.usbModeLabel
            is UsbImportSessionState.Starting -> state.request.settings.usbModePreference.name
            is UsbImportSessionState.Error -> state.request?.settings?.usbModePreference?.name
            UsbImportSessionState.Stopped -> graph.usbConnectionMonitor.current().usbModeLabel
        }
    }

    private fun UsbConnectionSnapshot.startFailureMessage(): String {
        return when {
            !isConnected -> unavailableReason
                ?: "\u672a\u68c0\u6d4b\u5230\u901a\u8fc7 USB \u8fde\u63a5\u7684\u76f8\u673a\uff0c\u8bf7\u68c0\u67e5\u6570\u636e\u7ebf\u548c OTG\u3002"
            !hasPermission -> unavailableReason
                ?: "\u68c0\u6d4b\u5230\u76f8\u673a\uff0c\u4f46\u8fd8\u6ca1\u6709 USB \u8bbf\u95ee\u6743\u9650\uff0c\u8bf7\u5728\u7cfb\u7edf\u5f39\u7a97\u4e2d\u6388\u6743\u3002"
            !isBrowsable -> unavailableReason
                ?: "\u5f53\u524d USB \u6a21\u5f0f\u6682\u65f6\u4e0d\u53ef\u6d4f\u89c8\uff0c\u8bf7\u5207\u6362\u5230\u53d7\u652f\u6301\u7684 MTP/PTP \u6a21\u5f0f\u3002"
            else -> "\u5f53\u524d USB \u4f1a\u8bdd\u6682\u65f6\u4e0d\u53ef\u7528\u3002"
        }
    }

    private fun UsbConnectionSnapshot.unavailableMessage(): String {
        return when {
            !isConnected -> "\u76f8\u673a\u5df2\u65ad\u5f00\uff0cUSB \u4f1a\u8bdd\u5df2\u7ed3\u675f\u3002"
            !hasPermission -> "\u5df2\u4e22\u5931 USB \u8bbf\u95ee\u6743\u9650\uff0c\u8bf7\u91cd\u65b0\u6388\u6743\u540e\u518d\u626b\u63cf\u3002"
            !isBrowsable -> unavailableReason
                ?: "\u5f53\u524d USB \u6a21\u5f0f\u4e0d\u652f\u6301\u6d4f\u89c8\uff0c\u8bf7\u5207\u6362\u76f8\u673a\u5230 MTP/PTP \u540e\u91cd\u65b0\u626b\u63cf\u3002"
            else -> unavailableReason
                ?: "\u5f53\u524d USB \u4f1a\u8bdd\u4e0d\u53ef\u7528\u3002"
        }
    }

    private fun String.toMimeType(): String? {
        return when (substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "arw" -> "image/x-sony-arw"
            "dng" -> "image/x-adobe-dng"
            else -> null
        }
    }

    companion object {
        private const val ChannelId = "rawbridge.receiver"
        private const val NotificationId = 62001
        private const val ActionStart = "com.rawbridge.backend.action.START_RECEIVER"
        private const val ActionStop = "com.rawbridge.backend.action.STOP_RECEIVER"
        private const val ActionRefreshCatalog = "com.rawbridge.backend.action.REFRESH_CATALOG"
        private const val ActionImportSelected = "com.rawbridge.backend.action.IMPORT_SELECTED"
        private const val ExtraCaptureIds = "capture_ids"

        fun startIntent(context: Context): Intent {
            return Intent(context, TransferReceiverService::class.java).setAction(ActionStart)
        }

        fun stopIntent(context: Context): Intent {
            return Intent(context, TransferReceiverService::class.java).setAction(ActionStop)
        }

        fun refreshCatalogIntent(context: Context): Intent {
            return Intent(context, TransferReceiverService::class.java)
                .setAction(ActionRefreshCatalog)
        }

        fun importSelectedIntent(
            context: Context,
            captureIds: List<String>,
        ): Intent {
            return Intent(context, TransferReceiverService::class.java)
                .setAction(ActionImportSelected)
                .putExtra(ExtraCaptureIds, captureIds.toTypedArray())
        }
    }
}
