package com.rawbridge.backend.storage

import com.rawbridge.backend.config.ReceiverSettings
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object StoragePolicyPlanner {
    private val dateFolderFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val conflictStampFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

    fun detectFileType(fileName: String): StoredFileType {
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> StoredFileType.JPEG
            "arw", "raw", "dng" -> StoredFileType.RAW
            else -> StoredFileType.OTHER
        }
    }

    fun buildRelativePath(
        settings: ReceiverSettings,
        fileType: StoredFileType,
        receivedAt: Instant = Instant.now(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String {
        val normalizedRoot = settings.saveRoot
            .trim()
            .trim('/')
            .replace('\\', '/')
        val segments = mutableListOf(normalizedRoot)

        if (settings.autoCreateDateFolder) {
            segments += dateFolderFormatter.format(receivedAt.atZone(zoneId))
        }

        if (settings.splitRawAndJpeg) {
            segments += when (fileType) {
                StoredFileType.RAW -> "RAW"
                StoredFileType.JPEG -> "JPEG"
                StoredFileType.OTHER -> "OTHER"
            }
        }

        return segments.filter { it.isNotBlank() }.joinToString(separator = "/", postfix = "/")
    }

    fun resolveConflict(
        originalFileName: String,
        nameExists: (String) -> Boolean,
        receivedAt: Instant = Instant.now(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String {
        if (!nameExists(originalFileName)) return originalFileName

        val baseName = originalFileName.substringBeforeLast('.', originalFileName)
        val extension = originalFileName.substringAfterLast('.', "")
        val stamp = conflictStampFormatter.format(receivedAt.atZone(zoneId))
        var attempt = 0

        while (true) {
            val suffix = if (attempt == 0) "_$stamp" else "_${stamp}_${attempt + 1}"
            val candidate = if (extension.isBlank()) {
                "$baseName$suffix"
            } else {
                "$baseName$suffix.$extension"
            }

            if (!nameExists(candidate)) return candidate
            attempt += 1
        }
    }
}
