package com.rawbridge.backend.platform.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.mtp.MtpDevice
import com.rawbridge.backend.config.UsbModePreference

internal data class UsbBrowseProbeResult(
    val isBrowsable: Boolean,
    val resolvedMode: UsbModePreference? = null,
    val modeLabel: String = "MTP/PTP",
    val errorMessage: String? = null,
)

internal fun UsbManager.selectRawBridgeCameraDevice(
    requirePermission: Boolean = false,
): UsbDevice? {
    return deviceList.values
        .sortedBy { it.deviceName }
        .firstOrNull { device ->
            (!requirePermission || hasPermission(device)) && device.looksLikeCameraDevice()
        }
}

internal fun UsbManager.probeBrowseSupport(
    device: UsbDevice,
    preferredMode: UsbModePreference,
): UsbBrowseProbeResult {
    if (!hasPermission(device)) {
        return UsbBrowseProbeResult(
            isBrowsable = false,
            modeLabel = preferredMode.name,
            errorMessage = "\u76f8\u673a\u5df2\u8fde\u63a5\uff0c\u4f46\u8fd8\u6ca1\u6709 USB \u8bbf\u95ee\u6743\u9650\u3002",
        )
    }

    val declaredModes = device.declaredUsbModes()
    val resolvedMode = when {
        declaredModes.size == 1 -> declaredModes.first()
        preferredMode in declaredModes -> preferredMode
        else -> null
    }
    val modeLabel = when {
        declaredModes.isEmpty() || declaredModes.size > 1 -> "MTP/PTP"
        else -> resolvedMode?.name ?: "MTP/PTP"
    }

    return runCatching {
        openUsbBrowseSession(
            usbManager = this,
            device = device,
            modeLabel = modeLabel,
        ) { }
    }.fold(
        onSuccess = {
            UsbBrowseProbeResult(
                isBrowsable = true,
                resolvedMode = resolvedMode,
                modeLabel = modeLabel,
            )
        },
        onFailure = {
            UsbBrowseProbeResult(
                isBrowsable = false,
                resolvedMode = resolvedMode,
                modeLabel = modeLabel,
                errorMessage = buildBrowseUnavailableReason(preferredMode, modeLabel),
            )
        },
    )
}

internal fun <T> openUsbBrowseSession(
    usbManager: UsbManager,
    device: UsbDevice,
    modeLabel: String,
    block: (MtpDevice) -> T,
): T {
    val connection = usbManager.openDevice(device)
        ?: error("\u65e0\u6cd5\u6253\u5f00 USB \u8bbe\u5907\u8fde\u63a5\u3002")
    val mtpDevice = MtpDevice(device)
    try {
        if (!mtpDevice.open(connection)) {
            error("\u65e0\u6cd5\u5efa\u7acb $modeLabel \u6d4f\u89c8\u4f1a\u8bdd\u3002")
        }
        return block(mtpDevice)
    } finally {
        runCatching { mtpDevice.close() }
        runCatching { connection.close() }
    }
}

private fun UsbDevice.looksLikeCameraDevice(): Boolean {
    if (deviceClass == UsbConstants.USB_CLASS_STILL_IMAGE) {
        return true
    }
    if ((0 until interfaceCount).any { index ->
            getInterface(index).interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE
        }
    ) {
        return true
    }

    val text = buildDescriptorText()
    return text.contains("sony", ignoreCase = true) ||
        text.contains("camera", ignoreCase = true) ||
        text.contains("mtp", ignoreCase = true) ||
        text.contains("ptp", ignoreCase = true)
}

private fun UsbDevice.declaredUsbModes(): Set<UsbModePreference> {
    val text = buildDescriptorText().lowercase()
    val hasExplicitMtp = text.contains("mtp")
    val hasExplicitPtp = text.contains("ptp")

    return when {
        hasExplicitMtp && !hasExplicitPtp -> setOf(UsbModePreference.MTP)
        hasExplicitPtp && !hasExplicitMtp -> setOf(UsbModePreference.PTP)
        else -> setOf(UsbModePreference.MTP, UsbModePreference.PTP)
    }
}

private fun UsbDevice.buildDescriptorText(): String {
    return buildString {
        append(manufacturerName.orEmpty())
        append(' ')
        append(productName.orEmpty())
        append(' ')
        append(deviceName)
        append(' ')
        repeat(interfaceCount) { index ->
            append(getInterface(index).name.orEmpty())
            append(' ')
        }
    }.trim()
}

private fun buildBrowseUnavailableReason(
    preferredMode: UsbModePreference,
    modeLabel: String,
): String {
    val label = if (modeLabel.isBlank()) preferredMode.name else modeLabel
    return "\u5f53\u524d USB \u6a21\u5f0f\u65e0\u6cd5\u5efa\u7acb $label \u6d4f\u89c8\u4f1a\u8bdd\uff0c\u8bf7\u5728\u76f8\u673a\u4e2d\u5207\u6362\u5230\u53d7\u652f\u6301\u7684 MTP/PTP \u6a21\u5f0f\u540e\u91cd\u65b0\u626b\u63cf\u3002"
}
