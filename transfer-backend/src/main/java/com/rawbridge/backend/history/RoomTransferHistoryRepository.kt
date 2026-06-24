package com.rawbridge.backend.history

import com.rawbridge.backend.history.db.TransferFileRecordEntity
import com.rawbridge.backend.history.db.TransferHistoryDao
import com.rawbridge.backend.history.db.TransferSessionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal class RoomTransferHistoryRepository(
    private val historyDao: TransferHistoryDao,
) : TransferHistoryRepository {
    override fun observeRecentSessions(limit: Int): Flow<List<TransferSession>> {
        return historyDao.observeRecentSessions(limit).map { rows ->
            rows.map { entity ->
                TransferSession(
                    sessionId = entity.sessionId,
                    protocolLabel = entity.protocolLabel,
                    status = SessionLifecycleStatus.valueOf(entity.status),
                    targetDirectory = entity.targetDirectory,
                    startedAtEpochMillis = entity.startedAtEpochMillis,
                    finishedAtEpochMillis = entity.finishedAtEpochMillis,
                    totalCount = entity.totalCount,
                    successCount = entity.successCount,
                    failedCount = entity.failedCount,
                    totalBytes = entity.totalBytes,
                    lastErrorMessage = entity.lastErrorMessage,
                )
            }
        }
    }

    override fun observeRecentRecords(limit: Int): Flow<List<TransferFileRecord>> {
        return historyDao.observeRecentRecords(limit).map { rows ->
            rows.map { entity ->
                TransferFileRecord(
                    recordId = entity.recordId,
                    sessionId = entity.sessionId,
                    originalFileName = entity.originalFileName,
                    savedFileName = entity.savedFileName,
                    fileType = com.rawbridge.backend.storage.StoredFileType.valueOf(entity.fileType),
                    status = TransferRecordStatus.valueOf(entity.status),
                    sizeBytes = entity.sizeBytes,
                    savedPath = entity.savedPath,
                    errorMessage = entity.errorMessage,
                    receivedAtEpochMillis = entity.receivedAtEpochMillis,
                )
            }
        }
    }

    override suspend fun upsertSession(session: TransferSession) {
        historyDao.upsertSession(
            TransferSessionEntity(
                sessionId = session.sessionId,
                protocolLabel = session.protocolLabel,
                status = session.status.name,
                targetDirectory = session.targetDirectory,
                startedAtEpochMillis = session.startedAtEpochMillis,
                finishedAtEpochMillis = session.finishedAtEpochMillis,
                totalCount = session.totalCount,
                successCount = session.successCount,
                failedCount = session.failedCount,
                totalBytes = session.totalBytes,
                lastErrorMessage = session.lastErrorMessage,
            ),
        )
    }

    override suspend fun finishSession(
        sessionId: String,
        status: SessionLifecycleStatus,
        totalCount: Int,
        successCount: Int,
        failedCount: Int,
        totalBytes: Long,
        finishedAtEpochMillis: Long,
        lastErrorMessage: String?,
    ) {
        historyDao.finishSession(
            sessionId = sessionId,
            status = status.name,
            totalCount = totalCount,
            successCount = successCount,
            failedCount = failedCount,
            totalBytes = totalBytes,
            finishedAtEpochMillis = finishedAtEpochMillis,
            lastErrorMessage = lastErrorMessage,
        )
    }

    override suspend fun appendRecord(record: TransferFileRecord) {
        historyDao.insertRecord(
            TransferFileRecordEntity(
                recordId = record.recordId,
                sessionId = record.sessionId,
                originalFileName = record.originalFileName,
                savedFileName = record.savedFileName,
                fileType = record.fileType.name,
                status = record.status.name,
                sizeBytes = record.sizeBytes,
                savedPath = record.savedPath,
                errorMessage = record.errorMessage,
                receivedAtEpochMillis = record.receivedAtEpochMillis,
            ),
        )
    }

    override suspend fun clearAll() {
        historyDao.clearAllHistory()
    }
}
