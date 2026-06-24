package com.rawbridge.backend.history.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "transfer_file_record",
    foreignKeys = [
        ForeignKey(
            entity = TransferSessionEntity::class,
            parentColumns = ["session_id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("session_id"),
        Index("received_at_epoch_millis"),
    ],
)
data class TransferFileRecordEntity(
    @PrimaryKey
    @ColumnInfo(name = "record_id")
    val recordId: String,
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    @ColumnInfo(name = "original_file_name")
    val originalFileName: String,
    @ColumnInfo(name = "saved_file_name")
    val savedFileName: String,
    @ColumnInfo(name = "file_type")
    val fileType: String,
    val status: String,
    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long,
    @ColumnInfo(name = "saved_path")
    val savedPath: String?,
    @ColumnInfo(name = "error_message")
    val errorMessage: String?,
    @ColumnInfo(name = "received_at_epoch_millis")
    val receivedAtEpochMillis: Long,
)
