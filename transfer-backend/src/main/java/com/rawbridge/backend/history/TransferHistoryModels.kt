package com.rawbridge.backend.history

enum class SessionLifecycleStatus {
    Ready,
    Running,
    Completed,
    Failed,
    Cancelled,
}

enum class TransferRecordStatus {
    Success,
    Failed,
}

data class TransferSession(
    val sessionId: String,
    val protocolLabel: String = "USB MTP",
    val status: SessionLifecycleStatus,
    val targetDirectory: String,
    val startedAtEpochMillis: Long,
    val finishedAtEpochMillis: Long? = null,
    val totalCount: Int = 0,
    val successCount: Int = 0,
    val failedCount: Int = 0,
    val totalBytes: Long = 0L,
    val lastErrorMessage: String? = null,
)

data class TransferFileRecord(
    val recordId: String,
    val sessionId: String,
    val originalFileName: String,
    val savedFileName: String,
    val fileType: com.rawbridge.backend.storage.StoredFileType,
    val status: TransferRecordStatus,
    val sizeBytes: Long,
    val savedPath: String?,
    val errorMessage: String? = null,
    val receivedAtEpochMillis: Long,
)
