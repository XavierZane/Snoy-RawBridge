package com.rawbridge.backend.platform.usb

import android.hardware.usb.UsbDevice
import com.rawbridge.backend.config.UsbModePreference
import java.io.File

internal data class CameraBrowseProbeResult(
    val protocolReady: Boolean,
    val modeLabel: String,
    val browseBackendLabel: String,
    val errorMessage: String? = null,
)

internal data class CameraDeviceDescriptor(
    val manufacturer: String,
    val model: String,
    val version: String,
    val serialNumber: String,
    val operationsSupported: IntArray,
    val eventsSupported: IntArray,
)

internal data class CameraStorageDescriptor(
    val storageId: Int,
    val description: String,
    val volumeIdentifier: String,
    val freeSpaceBytes: Long,
    val maxCapacityBytes: Long,
)

internal data class CameraObjectDescriptor(
    val storageId: Int,
    val objectHandle: Int,
    val parentHandle: Int,
    val formatCode: Int,
    val protectionStatus: Int,
    val compressedSizeBytes: Long,
    val thumbFormatCode: Int,
    val thumbCompressedSizeBytes: Long,
    val thumbWidth: Long,
    val thumbHeight: Long,
    val imageWidth: Long,
    val imageHeight: Long,
    val imageBitDepth: Long,
    val associationType: Int,
    val associationDescription: Int,
    val sequenceNumber: Long,
    val fileName: String,
    val dateCreatedEpochSeconds: Long?,
    val dateModifiedEpochSeconds: Long?,
    val keywords: String,
)

internal data class CameraThumbnailResult(
    val bytes: ByteArray,
    val operationLabel: String,
)

internal data class CameraReadResult(
    val bytesRead: Long,
    val operationLabel: String,
)

internal interface CameraBrowseSession : AutoCloseable {
    val backendLabel: String
    val deviceDescriptor: CameraDeviceDescriptor
    val storageIds: IntArray

    fun getStorageInfo(storageId: Int): CameraStorageDescriptor?

    fun getObjectHandles(
        storageId: Int,
        parentHandle: Int,
    ): IntArray

    fun getObjectInfo(handle: Int): CameraObjectDescriptor?

    fun getThumbnail(handle: Int): CameraThumbnailResult?

    fun readObjectToFile(
        handle: Int,
        expectedSizeBytes: Long?,
        target: File,
    ): CameraReadResult

    override fun close()
}

internal interface CameraBrowseClient {
    fun probe(
        device: UsbDevice,
        preferredMode: UsbModePreference,
    ): CameraBrowseProbeResult

    fun openSession(
        device: UsbDevice,
        preferredMode: UsbModePreference,
    ): CameraBrowseSession
}
