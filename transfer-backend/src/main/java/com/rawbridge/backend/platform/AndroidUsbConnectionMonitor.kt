package com.rawbridge.backend.platform

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.rawbridge.backend.config.UsbModePreference
import com.rawbridge.backend.debug.UsbDebugLogger
import com.rawbridge.backend.platform.usb.CameraBrowseClient
import com.rawbridge.backend.platform.usb.DebugMtpSimulator
import com.rawbridge.backend.platform.usb.buildModeLabel
import com.rawbridge.backend.platform.usb.selectRawBridgeCameraDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class AndroidUsbConnectionMonitor(
    context: Context,
    private val browseClient: CameraBrowseClient,
) : UsbConnectionMonitor {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val permissionIntent = PendingIntent.getBroadcast(
        appContext,
        30101,
        Intent(ActionUsbPermission),
        PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentMutabilityFlag(),
    )

    private val _snapshots = MutableStateFlow(disconnectedSnapshot(UsbModePreference.MTP))
    override val snapshots: StateFlow<UsbConnectionSnapshot> = _snapshots.asStateFlow()

    private var preferredMode: UsbModePreference = UsbModePreference.MTP

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ActionUsbPermission,
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                UsbManager.ACTION_USB_DEVICE_DETACHED,
                -> {
                    val pendingResult = goAsync()
                    monitorScope.launch {
                        try {
                            _snapshots.value = safeBuildSnapshot(preferredMode)
                        } finally {
                            pendingResult.finish()
                        }
                    }
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(ActionUsbPermission)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(receiver, filter)
        }
        refreshAsync(preferredMode)
    }

    override fun current(): UsbConnectionSnapshot = snapshots.value

    override fun refresh(
        preferredMode: UsbModePreference,
    ): UsbConnectionSnapshot {
        this.preferredMode = preferredMode
        val snapshot = safeBuildSnapshot(preferredMode)
        _snapshots.value = snapshot
        return snapshot
    }

    private fun safeBuildSnapshot(
        preferredMode: UsbModePreference,
    ): UsbConnectionSnapshot {
        return runCatching {
            buildSnapshot(preferredMode)
        }.getOrElse { error ->
            UsbDebugLogger.e(DebugTag, "usb snapshot build failed", error)
            disconnectedSnapshot(
                preferredMode = preferredMode,
                unavailableReason = error.message ?: "USB 检测失败，请重新插拔数据线后再试。",
            )
        }
    }

    private fun buildSnapshot(
        preferredMode: UsbModePreference,
    ): UsbConnectionSnapshot {
        if (DebugMtpSimulator.isEnabled(appContext)) {
            UsbDebugLogger.d(DebugTag, "snapshot source=simulated-mtp")
            return UsbConnectionSnapshot(
                isConnected = true,
                deviceName = "Simulated Sony Camera",
                usbModeLabel = "MTP (simulated)",
                hasPermission = true,
                isBrowsable = true,
                protocolReady = true,
                browseBackendLabel = "Debug simulated MTP",
            )
        }
        val device = usbManager.selectRawBridgeCameraDevice()
            ?: return UsbConnectionSnapshot(
                isConnected = false,
                usbModeLabel = preferredMode.name,
                hasPermission = false,
                isBrowsable = false,
                protocolReady = false,
                browseBackendLabel = null,
                unavailableReason = "未检测到通过 USB 连接的相机，请检查数据线和 OTG。",
            )

        val hasPermission = usbManager.hasPermission(device)
        if (!hasPermission) {
            usbManager.requestPermission(device, permissionIntent)
        }

        val fallbackModeLabel = usbManager.buildModeLabel(device, preferredMode)
        val probeResult = if (hasPermission) {
            runCatching {
                browseClient.probe(device, preferredMode)
            }.onFailure { error ->
                UsbDebugLogger.e(
                    DebugTag,
                    "usb probe failed device=${device.deviceName} mode=${preferredMode.name}",
                    error,
                )
            }.getOrNull()
        } else {
            null
        }
        val protocolReady = probeResult?.protocolReady == true
        val unavailableReason = when {
            !hasPermission ->
                "检测到相机，但还没有 USB 访问权限，请在系统弹窗中授权。"
            probeResult != null && !probeResult.protocolReady ->
                probeResult.errorMessage ?: defaultBrowseFailureReason(preferredMode)
            probeResult == null && hasPermission ->
                defaultBrowseFailureReason(preferredMode)
            else -> null
        }

        UsbDebugLogger.d(
            DebugTag,
            "snapshot device=${device.deviceName} mode=${probeResult?.modeLabel ?: fallbackModeLabel} preferred=${preferredMode.name} hasPermission=$hasPermission protocolReady=$protocolReady backend=${probeResult?.browseBackendLabel ?: "none"}",
        )

        return UsbConnectionSnapshot(
            isConnected = true,
            deviceName = device.productName ?: buildFallbackDeviceName(device),
            usbModeLabel = probeResult?.modeLabel ?: fallbackModeLabel,
            hasPermission = hasPermission,
            isBrowsable = protocolReady,
            protocolReady = protocolReady,
            browseBackendLabel = probeResult?.browseBackendLabel,
            unavailableReason = unavailableReason,
        )
    }

    private fun buildFallbackDeviceName(device: UsbDevice): String {
        return listOfNotNull(device.manufacturerName, device.productName)
            .joinToString(" ")
            .ifBlank { "USB Camera ${device.vendorId}:${device.productId}" }
    }

    private fun pendingIntentMutabilityFlag(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
    }

    private fun defaultBrowseFailureReason(
        preferredMode: UsbModePreference,
    ): String {
        return "USB 已连接，但低层 MTP 探测失败，请重新检测或查看调试日志。"
    }

    private fun refreshAsync(preferredMode: UsbModePreference) {
        monitorScope.launch {
            _snapshots.value = safeBuildSnapshot(preferredMode)
        }
    }

    private fun disconnectedSnapshot(
        preferredMode: UsbModePreference,
        unavailableReason: String = "未检测到通过 USB 连接的相机，请检查数据线和 OTG。",
    ): UsbConnectionSnapshot {
        return UsbConnectionSnapshot(
            isConnected = false,
            usbModeLabel = preferredMode.name,
            hasPermission = false,
            isBrowsable = false,
            protocolReady = false,
            browseBackendLabel = null,
            unavailableReason = unavailableReason,
        )
    }

    private companion object {
        private const val DebugTag = "RawBridgeUsbDebug"
        private const val ActionUsbPermission = "com.rawbridge.backend.USB_PERMISSION"
    }
}
