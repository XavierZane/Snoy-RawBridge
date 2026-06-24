package com.rawbridge.backend.history.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transfer_session")
data class TransferSessionEntity(
    @PrimaryKey
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    @ColumnInfo(name = "protocol_label")
    val protocolLabel: String,
    val status: String,
    @ColumnInfo(name = "target_directory")
    val targetDirectory: String,
    @ColumnInfo(name = "started_at_epoch_millis")
    val startedAtEpochMillis: Long,
    @ColumnInfo(name = "finished_at_epoch_millis")
    val finishedAtEpochMillis: Long?,
    @ColumnInfo(name = "total_count")
    val totalCount: Int,
    @ColumnInfo(name = "success_count")
    val successCount: Int,
    @ColumnInfo(name = "failed_count")
    val failedCount: Int,
    @ColumnInfo(name = "total_bytes")
    val totalBytes: Long,
    @ColumnInfo(name = "last_error_message")
    val lastErrorMessage: String?,
)
