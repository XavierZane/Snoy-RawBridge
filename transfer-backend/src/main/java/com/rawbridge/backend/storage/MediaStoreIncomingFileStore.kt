package com.rawbridge.backend.storage

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream

internal class MediaStoreIncomingFileStore(
    private val appContext: Context,
) : IncomingFileStore {
    override suspend fun saveIncomingFile(
        request: SaveIncomingFileRequest,
        source: InputStream,
    ): SavedIncomingFile = withContext(Dispatchers.IO) {
        val fileType = StoragePolicyPlanner.detectFileType(request.originalFileName)
        val relativePath = StoragePolicyPlanner.buildRelativePath(
            settings = request.settings,
            fileType = fileType,
            receivedAt = request.receivedAt,
        )

        val tempFile = File(appContext.cacheDir, "incoming-${System.nanoTime()}.part")
        val byteCount = source.use { input ->
            tempFile.outputStream().use { output -> input.copyTo(output) }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveWithMediaStore(
                    request = request,
                    tempFile = tempFile,
                    relativePath = relativePath,
                    fileType = fileType,
                    byteCount = byteCount,
                )
            } else {
                saveLegacyFallback(
                    request = request,
                    tempFile = tempFile,
                    relativePath = relativePath,
                    fileType = fileType,
                    byteCount = byteCount,
                )
            }
        } finally {
            tempFile.delete()
        }
    }

    private fun saveWithMediaStore(
        request: SaveIncomingFileRequest,
        tempFile: File,
        relativePath: String,
        fileType: StoredFileType,
        byteCount: Long,
    ): SavedIncomingFile {
        val resolver = appContext.contentResolver
        val displayName = StoragePolicyPlanner.resolveConflict(
            originalFileName = request.originalFileName,
            nameExists = { candidate ->
                resolver.query(
                    MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                    arrayOf(relativePath, candidate),
                    null,
                )?.use { cursor -> cursor.count > 0 } ?: false
            },
            receivedAt = request.receivedAt,
        )

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, request.mimeType ?: defaultMimeType(fileType))
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)
            ?: throw IOException("MediaStore 插入失败")

        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                tempFile.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IOException("无法打开目标输出流")

            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }

        return SavedIncomingFile(
            displayName = displayName,
            relativePath = relativePath,
            contentUri = uri.toString(),
            sizeBytes = byteCount,
            fileType = fileType,
        )
    }

    private fun saveLegacyFallback(
        request: SaveIncomingFileRequest,
        tempFile: File,
        relativePath: String,
        fileType: StoredFileType,
        byteCount: Long,
    ): SavedIncomingFile {
        val root = appContext.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: throw IOException("无法访问应用外部图片目录")
        val targetDirectory = File(root, relativePath.removePrefix("Pictures/").trim('/'))
        if (!targetDirectory.exists() && !targetDirectory.mkdirs()) {
            throw IOException("无法创建目标目录: ${targetDirectory.absolutePath}")
        }

        val displayName = StoragePolicyPlanner.resolveConflict(
            originalFileName = request.originalFileName,
            nameExists = { candidate -> File(targetDirectory, candidate).exists() },
            receivedAt = request.receivedAt,
        )

        val targetFile = File(targetDirectory, displayName)
        tempFile.copyTo(targetFile, overwrite = false)
        MediaScannerConnection.scanFile(
            appContext,
            arrayOf(targetFile.absolutePath),
            arrayOf(request.mimeType ?: defaultMimeType(fileType)),
            null,
        )

        return SavedIncomingFile(
            displayName = displayName,
            relativePath = targetDirectory.absolutePath,
            contentUri = targetFile.toURI().toString(),
            sizeBytes = byteCount,
            fileType = fileType,
        )
    }

    private fun defaultMimeType(fileType: StoredFileType): String {
        return when (fileType) {
            StoredFileType.JPEG -> "image/jpeg"
            StoredFileType.RAW -> "image/x-sony-arw"
            StoredFileType.OTHER -> "application/octet-stream"
        }
    }
}
