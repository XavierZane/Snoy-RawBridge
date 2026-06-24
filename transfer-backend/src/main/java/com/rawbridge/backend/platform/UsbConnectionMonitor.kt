package com.rawbridge.backend.platform

import com.rawbridge.backend.config.UsbModePreference
import kotlinx.coroutines.flow.StateFlow

data class UsbConnectionSnapshot(
    val isConnected: Boolean,
    val deviceName: String? = null,
    val usbModeLabel: String? = null,
    val hasPermission: Boolean = false,
    val isBrowsable: Boolean = false,
    val unavailableReason: String? = null,
) {
    val isReadyToBrowse: Boolean
        get() = isConnected && hasPermission && isBrowsable
}

interface UsbConnectionMonitor {
    val snapshots: StateFlow<UsbConnectionSnapshot>

    fun current(): UsbConnectionSnapshot

    fun refresh(
        preferredMode: UsbModePreference = UsbModePreference.MTP,
    ): UsbConnectionSnapshot
}
