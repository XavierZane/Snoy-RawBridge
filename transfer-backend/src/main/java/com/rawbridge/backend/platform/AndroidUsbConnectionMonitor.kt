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
import com.rawbridge.backend.platform.usb.probeBrowseSupport
import com.rawbridge.backend.platform.usb.selectRawBridgeCameraDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class AndroidUsbConnectionMonitor(
    context: Context,
) : UsbConnectionMonitor {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val permissionIntent = PendingIntent.getBroadcast(
        appContext,
        30101,
        Intent(ActionUsbPermission),
        PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentMutabilityFlag(),
    )

    private val _snapshots = MutableStateFlow(buildSnapshot(UsbModePreference.MTP))
    override val snapshots: StateFlow<UsbConnectionSnapshot> = _snapshots.asStateFlow()

    private var preferredMode: UsbModePreference = UsbModePreference.MTP

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ActionUsbPermission,
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                UsbManager.ACTION_USB_DEVICE_DETACHED,
                -> {
                    _snapshots.value = buildSnapshot(preferredMode)
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
    }

    override fun current(): UsbConnectionSnapshot = snapshots.value

    override fun refresh(
        preferredMode: UsbModePreference,
    ): UsbConnectionSnapshot {
        this.preferredMode = preferredMode
        val snapshot = buildSnapshot(preferredMode)
        _snapshots.value = snapshot
        return snapshot
    }

    private fun buildSnapshot(
        preferredMode: UsbModePreference,
    ): UsbConnectionSnapshot {
        val device = usbManager.selectRawBridgeCameraDevice()
            ?: return UsbConnectionSnapshot(
                isConnected = false,
                usbModeLabel = preferredMode.name,
                hasPermission = false,
                isBrowsable = false,
                unavailableReason = "\u672a\u68c0\u6d4b\u5230\u901a\u8fc7 USB \u8fde\u63a5\u7684\u76f8\u673a\uff0c\u8bf7\u68c0\u67e5\u6570\u636e\u7ebf\u548c OTG\u3002",
            )

        val hasPermission = usbManager.hasPermission(device)
        if (!hasPermission) {
            usbManager.requestPermission(device, permissionIntent)
        }

        val probeResult = if (hasPermission) {
            usbManager.probeBrowseSupport(device, preferredMode)
        } else {
            null
        }

        return UsbConnectionSnapshot(
            isConnected = true,
            deviceName = device.productName ?: buildFallbackDeviceName(device),
            usbModeLabel = probeResult?.modeLabel ?: preferredMode.name,
            hasPermission = hasPermission,
            isBrowsable = probeResult?.isBrowsable == true,
            unavailableReason = when {
                !hasPermission ->
                    "\u68c0\u6d4b\u5230\u76f8\u673a\uff0c\u4f46\u8fd8\u6ca1\u6709 USB \u8bbf\u95ee\u6743\u9650\uff0c\u8bf7\u5728\u7cfb\u7edf\u5f39\u7a97\u4e2d\u6388\u6743\u3002"
                probeResult?.isBrowsable == false -> probeResult.errorMessage
                else -> null
            },
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

    private companion object {
        private const val ActionUsbPermission = "com.rawbridge.backend.USB_PERMISSION"
    }
}
