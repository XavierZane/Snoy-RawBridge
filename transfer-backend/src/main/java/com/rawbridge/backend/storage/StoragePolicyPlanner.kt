package com.rawbridge.backend.storage

import com.rawbridge.backend.config.ReceiverSettings
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object StoragePolicyPlanner {
    private const val DefaultSaveRoot = "Pictures/RAWBridge"
    private val dateFolderFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val conflictStampFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    private val publicRootSegments = setOf(
        "pictures",
        "dcim",
        "download",
        "documents",
        "movies",
        "music",
        "podcasts",
    )
    private val imagePublicRootSegments = setOf("pictures", "dcim")

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
        val normalizedRoot = normalizeSaveRoot(settings.saveRoot)
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

    fun normalizeSaveRoot(saveRoot: String): String {
        val normalized = saveRoot
            .trim()
            .replace('\\', '/')
            .replace(Regex("/+"), "/")
            .trim('/')

        if (normalized.isBlank()) {
            return DefaultSaveRoot
        }

        val lowered = normalized.lowercase()
        if (
            ':' in normalized ||
            normalized.startsWith("/") ||
            lowered.startsWith("storage/") ||
            lowered.startsWith("sdcard/") ||
            lowered.startsWith("mnt/") ||
            lowered.startsWith("data/")
        ) {
            return DefaultSaveRoot
        }

        val firstSegment = normalized.substringBefore('/').lowercase()
        return if (firstSegment in publicRootSegments) {
            normalized
        } else {
            "Pictures/$normalized"
        }
    }

    fun mediaStoreCollectionKind(
        fileType: StoredFileType,
        relativePath: String,
    ): StorageMediaCollection {
        val root = relativePath.trimStart('/').substringBefore('/').lowercase()
        return if (root in imagePublicRootSegments) {
            StorageMediaCollection.Images
        } else {
            StorageMediaCollection.Files
        }
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

enum class StorageMediaCollection {
    Images,
    Files,
}
