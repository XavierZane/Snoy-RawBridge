package com.rawbridge.app

import com.rawbridge.backend.debug.UsbDebugLogEntry
import com.rawbridge.backend.debug.UsbDebugLogLevel
import org.junit.Assert.assertEquals
import org.junit.Test

class UsbDebugLogVisibilityTest {
    @Test
    fun `debug screen retains import lifecycle entries at every log level`() {
        val logs = listOf(
            UsbDebugLogEntry(1, 1L, UsbDebugLogLevel.Debug, "USB", "import start"),
            UsbDebugLogEntry(2, 2L, UsbDebugLogLevel.Warn, "USB", "unsupported format"),
            UsbDebugLogEntry(3, 3L, UsbDebugLogLevel.Error, "USB", "import failed"),
        )

        assertEquals(logs, visibleUsbDebugLogs(logs))
    }
}
