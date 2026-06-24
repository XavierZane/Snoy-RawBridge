package com.rawbridge.backend.history.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface TransferHistoryDao {
    @Query(
        """
        SELECT * FROM transfer_session
        ORDER BY started_at_epoch_millis DESC
        LIMIT :limit
        """,
    )
    fun observeRecentSessions(limit: Int): Flow<List<TransferSessionEntity>>

    @Query(
        """
        SELECT * FROM transfer_file_record
        ORDER BY received_at_epoch_millis DESC
        LIMIT :limit
        """,
    )
    fun observeRecentRecords(limit: Int): Flow<List<TransferFileRecordEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(entity: TransferSessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecord(entity: TransferFileRecordEntity)

    @Query(
        """
        UPDATE transfer_session
        SET status = :status,
            total_count = :totalCount,
            success_count = :successCount,
            failed_count = :failedCount,
            total_bytes = :totalBytes,
            finished_at_epoch_millis = :finishedAtEpochMillis,
            last_error_message = :lastErrorMessage
        WHERE session_id = :sessionId
        """,
    )
    suspend fun finishSession(
        sessionId: String,
        status: String,
        totalCount: Int,
        successCount: Int,
        failedCount: Int,
        totalBytes: Long,
        finishedAtEpochMillis: Long,
        lastErrorMessage: String?,
    )

    @Query("DELETE FROM transfer_file_record")
    suspend fun deleteAllRecords()

    @Query("DELETE FROM transfer_session")
    suspend fun deleteAllSessions()

    @Transaction
    suspend fun clearAllHistory() {
        deleteAllRecords()
        deleteAllSessions()
    }
}
