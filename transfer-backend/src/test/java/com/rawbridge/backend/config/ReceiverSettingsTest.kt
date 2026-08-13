package com.rawbridge.backend.config

import org.junit.Assert.assertEquals
import org.junit.Test

class ReceiverSettingsTest {
    @Test
    fun `MTP is the only supported USB transfer mode`() {
        assertEquals(listOf(UsbModePreference.MTP), UsbModePreference.entries.toList())
        assertEquals(UsbModePreference.MTP, ReceiverSettings().usbModePreference)
    }
}
