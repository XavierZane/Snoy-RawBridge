package com.rawbridge.backend.config

import kotlinx.coroutines.flow.Flow

interface TransferSettingsRepository {
    val settings: Flow<ReceiverSettings>

    suspend fun current(): ReceiverSettings

    suspend fun save(settings: ReceiverSettings)

    suspend fun update(transform: (ReceiverSettings) -> ReceiverSettings)
}
