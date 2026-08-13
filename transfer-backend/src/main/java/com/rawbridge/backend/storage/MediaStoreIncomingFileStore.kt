package com.rawbridge.backend.storage

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.rawbridge.backend.debug.UsbDebugLogger
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        UsbDebugLogger.d(
            DebugTag,
            "save incoming file staging-ready name=${request.originalFileName} bytes=$byteCount relativePath=$relativePath fileType=$fileType",
        )

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
        val collection = mediaStoreCollection(
            fileType = fileType,
            relativePath = relativePath,
        )
        val displayName = StoragePolicyPlanner.resolveConflict(
            originalFileName = request.originalFileName,
            nameExists = { candidate ->
                resolver.query(
                    collection,
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

        val uri = resolver.insert(collection, values)
            ?: throw IOException("MediaStore 插入失败，目标目录可能无效。")
        UsbDebugLogger.d(
            DebugTag,
            "save incoming file mediastore-inserted name=$displayName relativePath=$relativePath uri=$uri",
        )

        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                tempFile.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IOException("无法打开 MediaStore 输出流。")

            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            UsbDebugLogger.d(
                DebugTag,
                "save incoming file mediastore-success name=$displayName bytes=$byteCount uri=$uri",
            )
        } catch (error: Throwable) {
            UsbDebugLogger.e(
                DebugTag,
                "save incoming file mediastore-failed name=$displayName uri=$uri",
                error,
            )
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
            ?: throw IOException("无法访问应用外部图片目录。")
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
        UsbDebugLogger.d(
            DebugTag,
            "save incoming file legacy-success name=$displayName path=${targetFile.absolutePath} bytes=$byteCount",
        )

        return SavedIncomingFile(
            displayName = displayName,
            relativePath = targetDirectory.absolutePath,
            contentUri = Uri.fromFile(targetFile).toString(),
            sizeBytes = byteCount,
            fileType = fileType,
        )
    }

    private fun mediaStoreCollection(
        fileType: StoredFileType,
        relativePath: String,
    ): Uri {
        return when (StoragePolicyPlanner.mediaStoreCollectionKind(fileType, relativePath)) {
            StorageMediaCollection.Images ->
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            StorageMediaCollection.Files ->
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
    }

    private fun defaultMimeType(fileType: StoredFileType): String {
        return when (fileType) {
            StoredFileType.JPEG -> "image/jpeg"
            StoredFileType.RAW -> "image/x-sony-arw"
            StoredFileType.OTHER -> "application/octet-stream"
        }
    }

    private companion object {
        private const val DebugTag = "RawBridgeUsbDebug"
    }
}
