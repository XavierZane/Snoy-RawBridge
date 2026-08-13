package com.rawbridge.backend.platform.usb

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class CatalogEnumerationOutcomeTest {
    @Test
    fun `all storage enumeration failures are reported instead of an empty catalog`() {
        try {
            requireCatalogEnumerationSucceeded(
                storageCount = 1,
                successfulStorageCount = 0,
                firstFailure = IllegalStateException("GetObjectHandles failed"),
            )
            fail("Expected a complete enumeration failure to be reported")
        } catch (error: IllegalStateException) {
            assertEquals("无法读取相机存储目录。", error.message)
            assertEquals("GetObjectHandles failed", error.cause?.message)
        }
    }

    @Test
    fun `an empty catalog from a successfully enumerated storage remains valid`() {
        requireCatalogEnumerationSucceeded(
            storageCount = 1,
            successfulStorageCount = 1,
            firstFailure = null,
        )
    }

    @Test
    fun `a stopped import is not retried`() {
        try {
            requireImportRetryAllowed(isSessionActive = false)
            fail("Expected a stopped import retry to be rejected")
        } catch (_: IllegalStateException) {
            // Expected.
        }
    }

    @Test
    fun `coroutine cancellation is propagated instead of converted to an import failure`() {
        val cancellation = CancellationException("cancelled by caller")

        try {
            rethrowImportCancellation(cancellation)
            fail("Expected cancellation to be propagated")
        } catch (error: CancellationException) {
            assertEquals("cancelled by caller", error.message)
        }
    }
}
