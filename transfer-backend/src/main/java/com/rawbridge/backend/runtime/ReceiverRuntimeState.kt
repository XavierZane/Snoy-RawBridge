package com.rawbridge.backend.runtime

enum class ReceiverServiceStatus {
    Stopped,
    Starting,
    Ready,
    Receiving,
    Error,
}

data class ReceiverRuntimeState(
    val status: ReceiverServiceStatus = ReceiverServiceStatus.Stopped,
    val connectedDeviceName: String? = null,
    val usbModeLabel: String? = null,
    val message: String = "\u672a\u542f\u52a8 USB \u4f1a\u8bdd\u3002",
    val currentCaptureId: String? = null,
    val currentIndex: Int = 0,
    val totalCountHint: Int = 0,
    val updatedAtEpochMillis: Long = System.currentTimeMillis(),
)
