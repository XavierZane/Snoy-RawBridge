package com.rawbridge.backend.history

import kotlinx.coroutines.flow.Flow

interface TransferHistoryRepository {
    fun observeRecentSessions(limit: Int = 20): Flow<List<TransferSession>>

    fun observeRecentRecords(limit: Int = 100): Flow<List<TransferFileRecord>>

    suspend fun upsertSession(session: TransferSession)

    suspend fun finishSession(
        sessionId: String,
        status: SessionLifecycleStatus,
        totalCount: Int,
        successCount: Int,
        failedCount: Int,
        totalBytes: Long,
        finishedAtEpochMillis: Long,
        lastErrorMessage: String? = null,
    )

    suspend fun appendRecord(record: TransferFileRecord)

    suspend fun clearAll()
}
