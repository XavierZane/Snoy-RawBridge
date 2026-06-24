package com.rawbridge.app

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.rawbridge.backend.config.ReceiverSettings
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class MediaStoreGalleryRepository(
    private val appContext: Context,
) {
    suspend fun loadCaptureLibrary(
        settings: ReceiverSettings,
        limit: Int = 200,
    ): List<CapturePreviewItem> = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            loadFromMediaStore(settings, limit)
        } else {
            loadFromLegacyFiles(settings, limit)
        }
    }

    private fun loadFromMediaStore(
        settings: ReceiverSettings,
        limit: Int,
    ): List<CapturePreviewItem> {
        val collection = mediaCollectionUri()
        val rootPrefix = normalizeRoot(settings.saveRoot)
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.RELATIVE_PATH,
        )
        val selection = buildString {
            append("${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?")
            append(" AND ${MediaStore.MediaColumns.MIME_TYPE} LIKE ?")
        }
        val selectionArgs = arrayOf("$rootPrefix%", "image/%")
        val rows = mutableListOf<GalleryRow>()

        appContext.contentResolver.query(
            collection,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC",
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val dateIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val pathIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)

            while (cursor.moveToNext()) {
                val displayName = cursor.getString(nameIndex) ?: continue
                val mimeType = cursor.getString(mimeIndex)
                val fileType = mimeType.toCaptureFileType(displayName) ?: continue
                val id = cursor.getLong(idIndex)
                val sizeBytes = cursor.getLong(sizeIndex)
                val dateAddedMillis = cursor.getLong(dateIndex) * 1000L
                val relativePath = cursor.getString(pathIndex).orEmpty()
                val uri = ContentUris.withAppendedId(collection, id)

                rows += GalleryRow(
                    displayName = displayName,
                    mimeType = mimeType,
                    fileType = fileType,
                    sizeBytes = sizeBytes,
                    capturedAtMillis = dateAddedMillis,
                    relativePath = relativePath,
                    contentUri = uri.toString(),
                )
            }
        }

        val previewByKey = rows
            .filter { it.fileType == CaptureFileType.Jpeg }
            .associateBy { it.previewKey() }

        return rows
            .sortedByDescending { it.capturedAtMillis }
            .take(limit)
            .map { row ->
                val previewUri = if (row.fileType == CaptureFileType.Raw) {
                    previewByKey[row.previewKey()]?.contentUri ?: row.contentUri
                } else {
                    row.contentUri
                }

                row.toCapturePreviewItem(previewUri)
            }
    }

    private fun loadFromLegacyFiles(
        settings: ReceiverSettings,
        limit: Int,
    ): List<CapturePreviewItem> {
        val root = appContext.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)
            ?: return emptyList()
        val rootDirectory = File(root, normalizeRoot(settings.saveRoot).removePrefix("Pictures/"))
        if (!rootDirectory.exists()) return emptyList()

        val files = rootDirectory
            .walkTopDown()
            .filter { it.isFile }
            .filter { file -> file.extension.lowercase() in setOf("jpg", "jpeg", "arw", "raw", "dng") }
            .toList()

        val rows = files.map { file ->
            val fileType = file.name.toCaptureFileType()
                ?: CaptureFileType.Jpeg
            GalleryRow(
                displayName = file.name,
                mimeType = fileType.defaultMimeType(),
                fileType = fileType,
                sizeBytes = file.length(),
                capturedAtMillis = file.lastModified(),
                relativePath = file.parentFile?.absolutePath.orEmpty(),
                contentUri = android.net.Uri.fromFile(file).toString(),
            )
        }

        val previewByKey = rows
            .filter { it.fileType == CaptureFileType.Jpeg }
            .associateBy { it.previewKey() }

        return rows
            .sortedByDescending { it.capturedAtMillis }
            .take(limit)
            .map { row ->
                val previewUri = if (row.fileType == CaptureFileType.Raw) {
                    previewByKey[row.previewKey()]?.contentUri ?: row.contentUri
                } else {
                    row.contentUri
                }
                row.toCapturePreviewItem(previewUri)
            }
    }

    private fun mediaCollectionUri() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
    } else {
        MediaStore.Files.getContentUri("external")
    }

    private fun normalizeRoot(saveRoot: String): String {
        return saveRoot.trim().trim('/').replace('\\', '/').ifBlank { "Pictures/RAWBridge" }
    }

    private fun String?.toCaptureFileType(displayName: String): CaptureFileType? {
        val extension = displayName.substringAfterLast('.', "").lowercase()
        return when {
            this?.startsWith("image/jpeg") == true || extension in setOf("jpg", "jpeg") -> CaptureFileType.Jpeg
            this?.startsWith("image/") == true || extension in setOf("arw", "raw", "dng") -> CaptureFileType.Raw
            else -> null
        }
    }

    private fun String.toCaptureFileType(): CaptureFileType? {
        val extension = substringAfterLast('.', "").lowercase()
        return when (extension) {
            "jpg", "jpeg" -> CaptureFileType.Jpeg
            "arw", "raw", "dng" -> CaptureFileType.Raw
            else -> null
        }
    }

    private fun CaptureFileType.defaultMimeType(): String {
        return when (this) {
            CaptureFileType.Jpeg -> "image/jpeg"
            CaptureFileType.Raw -> "image/x-sony-arw"
        }
    }

    private fun GalleryRow.previewKey(): String {
        val baseName = displayName.substringBeforeLast('.', displayName)
        return "${relativePath.trimEnd('/').lowercase()}/${baseName.lowercase()}"
    }

    private fun GalleryRow.toCapturePreviewItem(previewUri: String): CapturePreviewItem {
        val timestamp = LocalDateTime.ofInstant(
            Instant.ofEpochMilli(capturedAtMillis),
            ZoneId.systemDefault(),
        )
        val baseName = displayName.substringBeforeLast('.', displayName)
        return CapturePreviewItem(
            id = contentUri,
            fileName = displayName,
            fileType = fileType,
            sizeLabel = formatBytes(sizeBytes),
            sizeInMegabytes = sizeBytes / 1024.0 / 1024.0,
            shotTitle = baseName,
            shotNote = if (fileType == CaptureFileType.Raw) {
                if (previewUri == contentUri) {
                    "RAW 预览"
                } else {
                    "RAW 预览 · 同名 JPEG"
                }
            } else {
                "JPEG 缩略图"
            },
            lensLabel = if (fileType == CaptureFileType.Raw) "RAW" else "JPEG",
            capturedAt = timestamp,
            previewMood = when (fileType) {
                CaptureFileType.Jpeg -> CapturePreviewMood.WarmGlass
                CaptureFileType.Raw -> CapturePreviewMood.CoolSky
            },
            contentUri = contentUri,
            thumbnailUri = previewUri,
        )
    }

    private data class GalleryRow(
        val displayName: String,
        val mimeType: String?,
        val fileType: CaptureFileType,
        val sizeBytes: Long,
        val capturedAtMillis: Long,
        val relativePath: String,
        val contentUri: String,
    )
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val megabytes = bytes / 1024.0 / 1024.0
    return when {
        megabytes < 1.0 -> "${(bytes / 1024.0).toInt().coerceAtLeast(1)} KB"
        megabytes < 100 -> String.format("%.1f MB", megabytes)
        else -> "${megabytes.toInt()} MB"
    }
}
