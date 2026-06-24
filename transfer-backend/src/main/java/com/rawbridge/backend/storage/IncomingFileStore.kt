package com.rawbridge.backend.storage

import java.io.InputStream

interface IncomingFileStore {
    suspend fun saveIncomingFile(
        request: SaveIncomingFileRequest,
        source: InputStream,
    ): SavedIncomingFile
}
