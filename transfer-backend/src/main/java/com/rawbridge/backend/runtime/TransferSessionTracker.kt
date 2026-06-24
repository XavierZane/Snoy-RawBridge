package com.rawbridge.backend.runtime

import com.rawbridge.backend.config.ReceiverSettings
import com.rawbridge.backend.history.SessionLifecycleStatus
import com.rawbridge.backend.history.TransferFileRecord
import com.rawbridge.backend.history.TransferRecordStatus
import com.rawbridge.backend.history.TransferSession
import com.rawbridge.backend.platform.usb.UsbImportSessionEvent
import com.rawbridge.backend.storage.SavedIncomingFile
import com.rawbridge.backend.storage.StoredFileType
import java.io.File
import java.util.UUID

internal class TransferSessionTracker(
    val settings: ReceiverSettings,
    startedAtEpochMillis: Long = System.currentTimeMillis(),
) {
    val sessionId: String = "session-${UUID.randomUUID()}"
    val startedAtEpochMillis: Long = startedAtEpochMillis

    private var successCount: Int = 0
    private var failedCount: Int = 0
    private var expectedTotalCount: Int = 0
    private var totalBytes: Long = 0L
    private var lastErrorMessage: String? = null

    fun startBatch(targetCount: Int) {
        expectedTotalCount = targetCount.coerceAtLeast(0)
    }

    fun recordSuccess(savedFile: SavedIncomingFile): TransferFileRecord {
        successCount += 1
        totalBytes += savedFile.sizeBytes
        return TransferFileRecord(
            recordId = "record-${UUID.randomUUID()}",
            sessionId = sessionId,
            originalFileName = savedFile.displayName,
            savedFileName = savedFile.displayName,
            fileType = savedFile.fileType,
            status = TransferRecordStatus.Success,
            sizeBytes = savedFile.sizeBytes,
            savedPath = savedFile.displayPath(),
            receivedAtEpochMillis = System.currentTimeMillis(),
        )
    }

    fun recordFailure(event: UsbImportSessionEvent.FileImportFailed): TransferFileRecord {
        failedCount += 1
        lastErrorMessage = event.reason
        return TransferFileRecord(
            recordId = "record-${UUID.randomUUID()}",
            sessionId = sessionId,
            originalFileName = event.fileName ?: "unknown",
            savedFileName = event.fileName ?: "unknown",
            fileType = detectFileType(event.fileName),
            status = TransferRecordStatus.Failed,
            sizeBytes = 0L,
            savedPath = null,
            errorMessage = event.reason,
            receivedAtEpochMillis = event.importedAtEpochMillis,
        )
    }

    fun markError(message: String?) {
        if (!message.isNullOrBlank()) {
            lastErrorMessage = message
        }
    }

    fun runningSession(): TransferSession {
        return TransferSession(
            sessionId = sessionId,
            protocolLabel = "USB ${settings.usbModePreference.name}",
            status = SessionLifecycleStatus.Running,
            targetDirectory = settings.saveRoot,
            startedAtEpochMillis = startedAtEpochMillis,
            totalCount = totalCount(),
            successCount = successCount,
            failedCount = failedCount,
            totalBytes = totalBytes,
            lastErrorMessage = lastErrorMessage,
        )
    }

    fun finishedSession(status: SessionLifecycleStatus): TransferSession {
        return TransferSession(
            sessionId = sessionId,
            protocolLabel = "USB ${settings.usbModePreference.name}",
            status = status,
            targetDirectory = settings.saveRoot,
            startedAtEpochMillis = startedAtEpochMillis,
            finishedAtEpochMillis = System.currentTimeMillis(),
            totalCount = totalCount(),
            successCount = successCount,
            failedCount = failedCount,
            totalBytes = totalBytes,
            lastErrorMessage = lastErrorMessage,
        )
    }

    fun totalCount(): Int = expectedTotalCount.coerceAtLeast(successCount + failedCount)

    fun completedCount(): Int = successCount + failedCount

    private fun SavedIncomingFile.displayPath(): String {
        return if (relativePath.contains(File.separatorChar) && !relativePath.endsWith("/")) {
            File(relativePath, displayName).absolutePath
        } else {
            "$relativePath$displayName"
        }
    }

    private fun detectFileType(fileName: String?): StoredFileType {
        val extension = fileName
            ?.substringAfterLast('.', "")
            ?.lowercase()
            .orEmpty()
        return when (extension) {
            "jpg", "jpeg" -> StoredFileType.JPEG
            "arw", "raw", "dng" -> StoredFileType.RAW
            else -> StoredFileType.OTHER
        }
    }
}
