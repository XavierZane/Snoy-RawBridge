package com.rawbridge.backend.platform.usb

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.rawbridge.backend.BuildConfig
import com.rawbridge.backend.storage.StoredFileType
import java.io.File

/** Debug-only MTP source enabled with `adb shell settings put global rawbridge_mock_mtp 1`. */
internal object DebugMtpSimulator {
    private const val SettingName = "rawbridge_mock_mtp"
    private const val StorageId = 0x0001_0001

    fun isEnabled(context: Context): Boolean {
        return BuildConfig.DEBUG &&
            Settings.Global.getString(context.contentResolver, SettingName) == "1"
    }

    fun openSession(): CameraBrowseSession = SimulatedSession

    private object SimulatedSession : CameraBrowseSession {
        private val rawBytes = "RAWBRIDGE-SIMULATED-ARW\n".encodeToByteArray()
        private val jpegBytes = "RAWBRIDGE-SIMULATED-JPEG\n".encodeToByteArray()
        private val handles = intArrayOf(1001, 1002, 1003)

        override val backendLabel: String = "Debug simulated MTP"
        override val deviceDescriptor = CameraDeviceDescriptor(
            manufacturer = "RAWBridge",
            model = "Simulated Sony Camera",
            version = "debug",
            serialNumber = "SIMULATED-MTP",
            operationsSupported = intArrayOf(
                UsbBulkMtpProtocol.OperationGetObject,
                UsbBulkMtpProtocol.OperationGetThumb,
            ),
            eventsSupported = intArrayOf(),
        )
        override val storageIds: IntArray = intArrayOf(StorageId)

        override fun getStorageInfo(storageId: Int): CameraStorageDescriptor? =
            CameraStorageDescriptor(storageId, "Simulated storage", "RAWBridge", 8_000_000_000, 16_000_000_000)

        override fun getObjectHandles(storageId: Int, parentHandle: Int): IntArray =
            if (storageId == StorageId && parentHandle in intArrayOf(0, UsbBulkMtpProtocol.RootParentHandle)) handles else intArrayOf()

        override fun getObjectInfo(handle: Int): CameraObjectDescriptor? = when (handle) {
            1003 -> objectInfo(handle, "DSC_SIM_0003.ARW", StoredFileType.RAW, rawBytes.size.toLong(), 1_726_000_800L)
            1002 -> objectInfo(handle, "DSC_SIM_0002.JPG", StoredFileType.JPEG, jpegBytes.size.toLong(), 1_725_997_200L)
            1001 -> objectInfo(handle, "DSC_SIM_0001.ARW", StoredFileType.RAW, rawBytes.size.toLong(), 1_725_993_600L)
            else -> null
        }

        override fun getThumbnail(handle: Int): CameraThumbnailResult? = null

        override fun readObjectToFile(handle: Int, expectedSizeBytes: Long?, target: File): CameraReadResult {
            val bytes = if (handle == 1002) jpegBytes else rawBytes
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
            return CameraReadResult(bytes.size.toLong(), "Simulated GetObject")
        }

        override fun close() = Unit

        private fun objectInfo(
            handle: Int,
            name: String,
            type: StoredFileType,
            size: Long,
            capturedAt: Long,
        ) = CameraObjectDescriptor(
            storageId = StorageId,
            objectHandle = handle,
            parentHandle = 0,
            formatCode = if (type == StoredFileType.RAW) UsbBulkMtpProtocol.ObjectFormatSonyRaw else UsbBulkMtpProtocol.ObjectFormatExifJpeg,
            protectionStatus = 0,
            compressedSizeBytes = size,
            thumbFormatCode = 0,
            thumbCompressedSizeBytes = 0,
            thumbWidth = 0,
            thumbHeight = 0,
            imageWidth = 0,
            imageHeight = 0,
            imageBitDepth = 0,
            associationType = 0,
            associationDescription = 0,
            sequenceNumber = handle.toLong(),
            fileName = name,
            dateCreatedEpochSeconds = capturedAt,
            dateModifiedEpochSeconds = capturedAt,
            keywords = "",
        )
    }
}
