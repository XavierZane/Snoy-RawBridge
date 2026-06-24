package com.rawbridge.backend.storage

import com.rawbridge.backend.config.ReceiverSettings
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StoragePolicyPlannerTest {
    @Test
    fun `detectFileType identifies sony raw and jpeg`() {
        assertEquals(StoredFileType.RAW, StoragePolicyPlanner.detectFileType("A7M3_0001.ARW"))
        assertEquals(StoredFileType.JPEG, StoragePolicyPlanner.detectFileType("A7M3_0001.jpg"))
        assertEquals(StoredFileType.OTHER, StoragePolicyPlanner.detectFileType("notes.txt"))
    }

    @Test
    fun `buildRelativePath applies date and type buckets`() {
        val settings = ReceiverSettings(
            saveRoot = "Pictures/RAWBridge",
            autoCreateDateFolder = true,
            splitRawAndJpeg = true,
        )

        val path = StoragePolicyPlanner.buildRelativePath(
            settings = settings,
            fileType = StoredFileType.RAW,
            receivedAt = Instant.parse("2026-06-23T10:15:30Z"),
            zoneId = ZoneId.of("UTC"),
        )

        assertEquals("Pictures/RAWBridge/2026-06-23/RAW/", path)
    }

    @Test
    fun `resolveConflict appends timestamp suffix`() {
        val resolved = StoragePolicyPlanner.resolveConflict(
            originalFileName = "A7M3_0001.ARW",
            nameExists = { candidate -> candidate == "A7M3_0001.ARW" },
            receivedAt = Instant.parse("2026-06-23T10:15:30Z"),
            zoneId = ZoneId.of("UTC"),
        )

        assertTrue(resolved.startsWith("A7M3_0001_20260623_101530"))
        assertTrue(resolved.endsWith(".ARW"))
    }
}
