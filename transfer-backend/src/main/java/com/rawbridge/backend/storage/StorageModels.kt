package com.rawbridge.backend.storage

import com.rawbridge.backend.config.ReceiverSettings
import java.time.Instant

enum class StoredFileType {
    JPEG,
    RAW,
    OTHER,
}

data class SaveIncomingFileRequest(
    val settings: ReceiverSettings,
    val originalFileName: String,
    val mimeType: String? = null,
    val receivedAt: Instant = Instant.now(),
)

data class SavedIncomingFile(
    val displayName: String,
    val relativePath: String,
    val contentUri: String,
    val sizeBytes: Long,
    val fileType: StoredFileType,
)
