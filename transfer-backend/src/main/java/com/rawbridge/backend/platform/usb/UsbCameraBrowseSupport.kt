package com.rawbridge.backend.platform.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.rawbridge.backend.config.UsbModePreference

internal fun UsbManager.selectRawBridgeCameraDevice(
    requirePermission: Boolean = false,
): UsbDevice? {
    return deviceList.values
        .sortedBy { it.deviceName }
        .firstOrNull { device ->
            (!requirePermission || hasPermission(device)) && device.looksLikeCameraDevice()
        }
}

internal fun UsbManager.buildModeLabel(
    device: UsbDevice,
    preferredMode: UsbModePreference,
): String {
    return UsbModePreference.MTP.name
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
        text.contains("mtp", ignoreCase = true)
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
