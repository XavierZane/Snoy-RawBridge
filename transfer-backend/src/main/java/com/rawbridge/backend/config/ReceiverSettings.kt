package com.rawbridge.backend.config

enum class UsbModePreference {
    MTP,
    PTP,
}

data class ReceiverSettings(
    val usbModePreference: UsbModePreference = UsbModePreference.MTP,
    val saveRoot: String = "Pictures/RAWBridge",
    val autoCreateDateFolder: Boolean = true,
    val splitRawAndJpeg: Boolean = true,
    val clearPreviewCacheOnDisconnect: Boolean = true,
)
