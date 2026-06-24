package com.rawbridge.backend.history.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        TransferSessionEntity::class,
        TransferFileRecordEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class TransferDatabase : RoomDatabase() {
    abstract fun historyDao(): TransferHistoryDao
}
