package com.rawbridge.backend.platform.usb

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class MtpContainerStreamReaderTest {
    @Test
    fun `reader reassembles a header split across bulk reads`() {
        val container = container(
            type = UsbBulkMtpProtocol.ContainerTypeResponse,
            code = UsbBulkMtpProtocol.ResponseOk,
            transactionId = 7,
            payload = byteArrayOf(1, 2, 3, 4),
        )
        val reader = MtpContainerStreamReader(ChunkedMtpInput(container, intArrayOf(5, 2, 9))::read)

        val header = reader.readHeader()
        val payload = reader.readPayload(header)

        assertEquals(16, header.length)
        assertEquals(UsbBulkMtpProtocol.ContainerTypeResponse, header.type)
        assertEquals(UsbBulkMtpProtocol.ResponseOk, header.code)
        assertEquals(7, header.transactionId)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), payload)
    }

    @Test
    fun `reader preserves a following container received in the same bulk read`() {
        val first = container(
            type = UsbBulkMtpProtocol.ContainerTypeResponse,
            code = UsbBulkMtpProtocol.ResponseOk,
            transactionId = 11,
            payload = byteArrayOf(),
        )
        val second = container(
            type = UsbBulkMtpProtocol.ContainerTypeEvent,
            code = 0x4002,
            transactionId = 12,
            payload = byteArrayOf(9, 8, 7, 6),
        )
        val reader = MtpContainerStreamReader(ChunkedMtpInput(first + second, intArrayOf(first.size + second.size))::read)

        val firstHeader = reader.readHeader()
        assertArrayEquals(byteArrayOf(), reader.readPayload(firstHeader))
        val secondHeader = reader.readHeader()

        assertEquals(0x4002, secondHeader.code)
        assertEquals(12, secondHeader.transactionId)
        assertArrayEquals(byteArrayOf(9, 8, 7, 6), reader.readPayload(secondHeader))
    }

    @Test
    fun `reader streams a data payload to output without buffering the container`() {
        val payload = ByteArray(32 * 1024) { index -> (index % 251).toByte() }
        val input = container(
            type = UsbBulkMtpProtocol.ContainerTypeData,
            code = UsbBulkMtpProtocol.OperationGetObject,
            transactionId = 21,
            payload = payload,
        )
        val reader = MtpContainerStreamReader(ChunkedMtpInput(input, intArrayOf(7, 29, 1024, 8192, 4096, 20000))::read)
        val output = ByteArrayOutputStream()

        val header = reader.readHeader()
        reader.copyPayloadTo(header, output)

        assertArrayEquals(payload, output.toByteArray())
    }

    @Test
    fun `size validation rejects a truncated known object`() {
        try {
            requireExpectedObjectSize(expectedSizeBytes = 100L, actualSizeBytes = 99L)
            fail("Expected a truncated object to be rejected")
        } catch (error: IllegalStateException) {
            assertEquals("MTP object size mismatch expected=100 actual=99", error.message)
        }
    }

    @Test
    fun `size validation accepts the MTP unknown-size marker`() {
        requireExpectedObjectSize(
            expectedSizeBytes = UsbBulkMtpProtocol.UnknownObjectSize,
            actualSizeBytes = 123L,
        )
    }

    @Test
    fun `partial object 64 request uses a 64-bit offset and 32-bit maximum length`() {
        assertArrayEquals(
            intArrayOf(42, 2, 1, 512 * 1024),
            buildPartialObject64Parameters(
                handle = 42,
                offset = 0x1_0000_0002L,
                maximumBytes = 512 * 1024,
            ),
        )
    }

    @Test
    fun `unknown object size does not limit partial reads`() {
        assertNull(normalizeExpectedObjectSize(UsbBulkMtpProtocol.UnknownObjectSize))
        assertEquals(123L, normalizeExpectedObjectSize(123L))
    }

    @Test
    fun `partial object supports the complete unsigned 32-bit offset range`() {
        assertEquals(-1, toPartialObjectOffset(0xFFFF_FFFFL))
        try {
            toPartialObjectOffset(0x1_0000_0000L)
            fail("Expected an offset above UInt32 to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test
    fun `transaction validation rejects a stale response`() {
        val staleResponse = MtpContainerHeader(
            length = 12,
            type = UsbBulkMtpProtocol.ContainerTypeResponse,
            code = UsbBulkMtpProtocol.ResponseOk,
            transactionId = 4,
        )

        try {
            requireExpectedMtpTransaction(
                header = staleResponse,
                transactionId = 5,
            )
            fail("Expected a stale response to be rejected")
        } catch (_: IllegalStateException) {
            // Expected.
        }
    }

    private fun container(
        type: Int,
        code: Int,
        transactionId: Int,
        payload: ByteArray,
    ): ByteArray {
        val length = 12 + payload.size
        return ByteBuffer.allocate(length)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(length)
            .putShort(type.toShort())
            .putShort(code.toShort())
            .putInt(transactionId)
            .put(payload)
            .array()
    }
}

private class ChunkedMtpInput(
    private val bytes: ByteArray,
    private val chunkSizes: IntArray,
) {
    private var offset = 0
    private var chunkIndex = 0

    fun read(destination: ByteArray, destinationOffset: Int, maximumLength: Int): Int {
        if (offset >= bytes.size) return -1
        val chunkSize = chunkSizes.getOrElse(chunkIndex) { maximumLength }
        chunkIndex += 1
        val count = minOf(chunkSize, maximumLength, bytes.size - offset)
        System.arraycopy(bytes, offset, destination, destinationOffset, count)
        offset += count
        return count
    }
}
