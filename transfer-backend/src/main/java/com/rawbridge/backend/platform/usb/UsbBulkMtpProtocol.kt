package com.rawbridge.backend.platform.usb

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDateTime
import java.time.ZoneId

internal object UsbBulkMtpProtocol {
    const val BackendLabel = "USB Bulk MTP"

    const val ContainerTypeCommand = 0x0001
    const val ContainerTypeData = 0x0002
    const val ContainerTypeResponse = 0x0003
    const val ContainerTypeEvent = 0x0004

    const val OperationGetDeviceInfo = 0x1001
    const val OperationOpenSession = 0x1002
    const val OperationCloseSession = 0x1003
    const val OperationGetStorageIds = 0x1004
    const val OperationGetStorageInfo = 0x1005
    const val OperationGetObjectHandles = 0x1007
    const val OperationGetObjectInfo = 0x1008
    const val OperationGetObject = 0x1009
    const val OperationGetThumb = 0x100A
    const val OperationGetPartialObject = 0x101B
    const val OperationGetPartialObject64 = 0x95C1

    const val ResponseOk = 0x2001
    const val ResponseGeneralError = 0x2002
    const val ResponseSessionNotOpen = 0x2003
    const val ResponseInvalidTransactionId = 0x2004
    const val ResponseOperationNotSupported = 0x2005
    const val ResponseParameterNotSupported = 0x2006
    const val ResponseIncompleteTransfer = 0x2007
    const val ResponseInvalidStorageId = 0x2008
    const val ResponseInvalidObjectHandle = 0x2009
    const val ResponseDevicePropNotSupported = 0x200A
    const val ResponseInvalidObjectFormatCode = 0x200B
    const val ResponseStoreFull = 0x200C
    const val ResponseObjectWriteProtected = 0x200D
    const val ResponseStoreReadOnly = 0x200E
    const val ResponseAccessDenied = 0x200F
    const val ResponseNoThumbnailPresent = 0x2010
    const val ResponseSelfTestFailed = 0x2011
    const val ResponsePartialDeletion = 0x2012
    const val ResponseStoreNotAvailable = 0x2013
    const val ResponseSpecificationByFormatUnsupported = 0x2014
    const val ResponseNoValidObjectInfo = 0x2015
    const val ResponseInvalidCodeFormat = 0x2016
    const val ResponseUnknownVendorCode = 0x2017
    const val ResponseCaptureAlreadyTerminated = 0x2018
    const val ResponseDeviceBusy = 0x2019
    const val ResponseInvalidParentObject = 0x201A
    const val ResponseInvalidDevicePropFormat = 0x201B
    const val ResponseInvalidDevicePropValue = 0x201C
    const val ResponseInvalidParameter = 0x201D
    const val ResponseSessionAlreadyOpen = 0x201E
    const val ResponseTransactionCanceled = 0x201F
    const val ResponseSpecificationOfDestinationUnsupported = 0x2020
    const val ResponseMtpObjectTooLarge = 0xA809

    const val ObjectFormatAssociation = 0x3001
    const val ObjectFormatExifJpeg = 0x3801
    const val ObjectFormatJfif = 0x3808
    const val ObjectFormatPng = 0x380B
    const val ObjectFormatDng = 0x3811
    const val ObjectFormatSonyRaw = 0xB101

    const val RootParentHandle = -1
    const val UnknownObjectSize = 0xFFFF_FFFFL

    fun buildCommandContainer(
        code: Int,
        transactionId: Int,
        parameters: IntArray,
    ): ByteArray {
        val length = 12 + parameters.size * 4
        return ByteBuffer.allocate(length)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(length)
            .putShort(ContainerTypeCommand.toShort())
            .putShort(code.toShort())
            .putInt(transactionId)
            .apply {
                parameters.forEach(::putInt)
            }
            .array()
    }

    fun parseContainerHeader(bytes: ByteArray): MtpContainerHeader {
        val reader = MtpDataReader(bytes)
        return MtpContainerHeader(
            length = reader.readInt32(),
            type = reader.readUInt16(),
            code = reader.readUInt16(),
            transactionId = reader.readInt32(),
        )
    }

    fun parseResponseParameters(payload: ByteArray): IntArray {
        if (payload.isEmpty()) return intArrayOf()
        val reader = MtpDataReader(payload)
        val parameters = IntArray(payload.size / 4)
        var index = 0
        while (reader.remaining >= 4) {
            parameters[index] = reader.readInt32()
            index += 1
        }
        return if (index == parameters.size) parameters else parameters.copyOf(index)
    }

    fun parseDeviceDescriptor(payload: ByteArray): CameraDeviceDescriptor {
        val reader = MtpDataReader(payload)
        reader.readUInt16()
        reader.readUInt32()
        reader.readUInt16()
        reader.readString()
        reader.readUInt16()
        val operations = reader.readUInt16Array()
        val events = reader.readUInt16Array()
        reader.readUInt16Array()
        reader.readUInt16Array()
        reader.readUInt16Array()
        val manufacturer = reader.readString()
        val model = reader.readString()
        val version = reader.readString()
        val serialNumber = reader.readString()
        return CameraDeviceDescriptor(
            manufacturer = manufacturer,
            model = model,
            version = version,
            serialNumber = serialNumber,
            operationsSupported = operations,
            eventsSupported = events,
        )
    }

    fun parseStorageIds(payload: ByteArray): IntArray {
        return MtpDataReader(payload).readUInt32Array()
    }

    fun parseStorageDescriptor(
        storageId: Int,
        payload: ByteArray,
    ): CameraStorageDescriptor {
        val reader = MtpDataReader(payload)
        reader.readUInt16()
        reader.readUInt16()
        reader.readUInt16()
        val maxCapacity = reader.readUInt64()
        val freeSpace = reader.readUInt64()
        reader.readUInt32()
        val description = reader.readString()
        val volumeIdentifier = reader.readString()
        return CameraStorageDescriptor(
            storageId = storageId,
            description = description,
            volumeIdentifier = volumeIdentifier,
            freeSpaceBytes = freeSpace,
            maxCapacityBytes = maxCapacity,
        )
    }

    fun parseObjectHandles(payload: ByteArray): IntArray {
        return MtpDataReader(payload).readUInt32Array()
    }

    fun parseObjectDescriptor(payload: ByteArray): CameraObjectDescriptor {
        val reader = MtpDataReader(payload)
        val storageId = reader.readInt32()
        val formatCode = reader.readUInt16()
        val protectionStatus = reader.readUInt16()
        val compressedSizeBytes = reader.readUInt32()
        val thumbFormatCode = reader.readUInt16()
        val thumbCompressedSizeBytes = reader.readUInt32()
        val thumbWidth = reader.readUInt32()
        val thumbHeight = reader.readUInt32()
        val imageWidth = reader.readUInt32()
        val imageHeight = reader.readUInt32()
        val imageBitDepth = reader.readUInt32()
        val parentHandle = reader.readInt32()
        val associationType = reader.readUInt16()
        val associationDescription = reader.readInt32()
        val sequenceNumber = reader.readUInt32()
        val fileName = reader.readString()
        val created = parsePtpDateTime(reader.readString())
        val modified = parsePtpDateTime(reader.readString())
        val keywords = reader.readString()
        return CameraObjectDescriptor(
            storageId = storageId,
            objectHandle = 0,
            parentHandle = parentHandle,
            formatCode = formatCode,
            protectionStatus = protectionStatus,
            compressedSizeBytes = compressedSizeBytes,
            thumbFormatCode = thumbFormatCode,
            thumbCompressedSizeBytes = thumbCompressedSizeBytes,
            thumbWidth = thumbWidth,
            thumbHeight = thumbHeight,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            imageBitDepth = imageBitDepth,
            associationType = associationType,
            associationDescription = associationDescription,
            sequenceNumber = sequenceNumber,
            fileName = fileName,
            dateCreatedEpochSeconds = created,
            dateModifiedEpochSeconds = modified,
            keywords = keywords,
        )
    }

    fun operationLabel(code: Int): String {
        return when (code) {
            OperationGetDeviceInfo -> "GetDeviceInfo"
            OperationOpenSession -> "OpenSession"
            OperationCloseSession -> "CloseSession"
            OperationGetStorageIds -> "GetStorageIDs"
            OperationGetStorageInfo -> "GetStorageInfo"
            OperationGetObjectHandles -> "GetObjectHandles"
            OperationGetObjectInfo -> "GetObjectInfo"
            OperationGetObject -> "GetObject"
            OperationGetThumb -> "GetThumb"
            OperationGetPartialObject -> "GetPartialObject"
            OperationGetPartialObject64 -> "GetPartialObject64"
            else -> "0x${code.toHex16()}"
        }
    }

    fun responseLabel(code: Int): String {
        return when (code) {
            ResponseOk -> "OK"
            ResponseGeneralError -> "GeneralError"
            ResponseSessionNotOpen -> "SessionNotOpen"
            ResponseInvalidTransactionId -> "InvalidTransactionId"
            ResponseOperationNotSupported -> "OperationNotSupported"
            ResponseParameterNotSupported -> "ParameterNotSupported"
            ResponseIncompleteTransfer -> "IncompleteTransfer"
            ResponseInvalidStorageId -> "InvalidStorageId"
            ResponseInvalidObjectHandle -> "InvalidObjectHandle"
            ResponseDevicePropNotSupported -> "DevicePropNotSupported"
            ResponseInvalidObjectFormatCode -> "InvalidObjectFormatCode"
            ResponseStoreFull -> "StoreFull"
            ResponseObjectWriteProtected -> "ObjectWriteProtected"
            ResponseStoreReadOnly -> "StoreReadOnly"
            ResponseAccessDenied -> "AccessDenied"
            ResponseNoThumbnailPresent -> "NoThumbnailPresent"
            ResponseSelfTestFailed -> "SelfTestFailed"
            ResponsePartialDeletion -> "PartialDeletion"
            ResponseStoreNotAvailable -> "StoreNotAvailable"
            ResponseSpecificationByFormatUnsupported -> "SpecificationByFormatUnsupported"
            ResponseNoValidObjectInfo -> "NoValidObjectInfo"
            ResponseInvalidCodeFormat -> "InvalidCodeFormat"
            ResponseUnknownVendorCode -> "UnknownVendorCode"
            ResponseCaptureAlreadyTerminated -> "CaptureAlreadyTerminated"
            ResponseDeviceBusy -> "DeviceBusy"
            ResponseInvalidParentObject -> "InvalidParentObject"
            ResponseInvalidDevicePropFormat -> "InvalidDevicePropFormat"
            ResponseInvalidDevicePropValue -> "InvalidDevicePropValue"
            ResponseInvalidParameter -> "InvalidParameter"
            ResponseSessionAlreadyOpen -> "SessionAlreadyOpen"
            ResponseTransactionCanceled -> "TransactionCanceled"
            ResponseSpecificationOfDestinationUnsupported -> "SpecificationOfDestinationUnsupported"
            ResponseMtpObjectTooLarge -> "ObjectTooLarge"
            else -> "0x${code.toHex16()}"
        }
    }

    fun responseMessage(code: Int): String {
        return when (code) {
            ResponseSessionNotOpen -> "相机会话还没有打开，请重新扫描后再试。"
            ResponseOperationNotSupported -> "当前相机不支持这一步 MTP 操作。"
            ResponseInvalidStorageId,
            ResponseStoreNotAvailable,
            -> "相机存储暂时不可用，请确认存储卡已挂载。"
            ResponseInvalidObjectHandle -> "相机返回的文件句柄已经失效，请重新扫描图库。"
            ResponseAccessDenied,
            ResponseStoreReadOnly,
            ResponseObjectWriteProtected,
            -> "相机拒绝了当前读取请求，请检查相机模式和权限。"
            ResponseNoThumbnailPresent -> "当前文件没有可读取的缩略图。"
            ResponseDeviceBusy -> "相机当前正忙，请稍后重试。"
            ResponseInvalidParameter -> "MTP 请求参数无效，建议重新连接后再试。"
            ResponseMtpObjectTooLarge -> "文件过大，当前读取方式无法直接完成。"
            ResponseSessionAlreadyOpen -> "相机已经有一个活跃会话，本次会继续复用。"
            else -> "相机返回 ${responseLabel(code)}。"
        }
    }

    fun parsePtpDateTime(value: String): Long? {
        if (value.length < 15) return null
        return runCatching {
            val dateTime = LocalDateTime.of(
                value.substring(0, 4).toInt(),
                value.substring(4, 6).toInt(),
                value.substring(6, 8).toInt(),
                value.substring(9, 11).toInt(),
                value.substring(11, 13).toInt(),
                value.substring(13, 15).toInt(),
            )
            dateTime.atZone(ZoneId.systemDefault()).toEpochSecond()
        }.getOrNull()
    }
}

internal data class MtpContainerHeader(
    val length: Int,
    val type: Int,
    val code: Int,
    val transactionId: Int,
)

internal class MtpOperationException(
    val operationCode: Int,
    val responseCode: Int,
    val transactionId: Int,
    detailMessage: String = UsbBulkMtpProtocol.responseMessage(responseCode),
) : IllegalStateException(detailMessage)

internal class MtpContainerStreamReader(
    private val readChunk: (ByteArray, Int, Int) -> Int,
    bufferSize: Int = 16 * 1024,
) {
    private val buffer = ByteArray(bufferSize)
    private var bufferOffset = 0
    private var bufferSize = 0

    fun readHeader(): MtpContainerHeader {
        val bytes = ByteArray(ContainerHeaderSize)
        readFully(bytes, 0, bytes.size)
        return UsbBulkMtpProtocol.parseContainerHeader(bytes).also { header ->
            check(header.length >= ContainerHeaderSize) {
                "MTP container length invalid: ${header.length}"
            }
        }
    }

    fun readPayload(header: MtpContainerHeader): ByteArray {
        val payloadSize = header.payloadSize()
        check(payloadSize <= MaxBufferedPayloadSize) {
            "MTP container payload too large to buffer: $payloadSize"
        }
        return ByteArray(payloadSize).also { payload ->
            readFully(payload, 0, payload.size)
        }
    }

    fun copyPayloadTo(
        header: MtpContainerHeader,
        output: OutputStream,
    ): Long {
        var remaining = header.payloadSize()
        var copied = 0L
        while (remaining > 0) {
            ensureBuffered()
            val count = minOf(remaining, bufferSize - bufferOffset)
            output.write(buffer, bufferOffset, count)
            bufferOffset += count
            remaining -= count
            copied += count
        }
        return copied
    }

    fun reset() {
        bufferOffset = 0
        bufferSize = 0
    }

    private fun readFully(
        destination: ByteArray,
        destinationOffset: Int,
        length: Int,
    ) {
        var copied = 0
        while (copied < length) {
            ensureBuffered()
            val count = minOf(length - copied, bufferSize - bufferOffset)
            System.arraycopy(buffer, bufferOffset, destination, destinationOffset + copied, count)
            bufferOffset += count
            copied += count
        }
    }

    private fun ensureBuffered() {
        if (bufferOffset < bufferSize) return
        bufferOffset = 0
        bufferSize = readChunk(buffer, 0, buffer.size)
        check(bufferSize > 0) { "MTP transport returned no bytes" }
    }

    private fun MtpContainerHeader.payloadSize(): Int {
        return length - ContainerHeaderSize
    }

    private companion object {
        const val ContainerHeaderSize = 12
        const val MaxBufferedPayloadSize = 16 * 1024 * 1024
    }
}

internal fun requireExpectedObjectSize(
    expectedSizeBytes: Long?,
    actualSizeBytes: Long,
) {
    val expected = normalizeExpectedObjectSize(expectedSizeBytes) ?: return
    check(actualSizeBytes == expected) {
        "MTP object size mismatch expected=$expected actual=$actualSizeBytes"
    }
}

internal fun normalizeExpectedObjectSize(expectedSizeBytes: Long?): Long? {
    return expectedSizeBytes
        ?.takeUnless { it == UsbBulkMtpProtocol.UnknownObjectSize }
}

internal fun buildPartialObject64Parameters(
    handle: Int,
    offset: Long,
    maximumBytes: Int,
): IntArray {
    require(offset >= 0L) { "MTP partial-object offset must not be negative" }
    require(maximumBytes >= 0) { "MTP partial-object maximum bytes must not be negative" }
    return intArrayOf(
        handle,
        (offset and 0xFFFF_FFFFL).toInt(),
        ((offset ushr 32) and 0xFFFF_FFFFL).toInt(),
        maximumBytes,
    )
}

internal fun toPartialObjectOffset(offset: Long): Int {
    require(offset in 0L..0xFFFF_FFFFL) {
        "GetPartialObject only supports UInt32 offsets"
    }
    return offset.toInt()
}

internal fun requireExpectedMtpTransaction(
    header: MtpContainerHeader,
    transactionId: Int,
    expectedDataOperation: Int? = null,
) {
    check(header.transactionId == transactionId) {
        "MTP transaction mismatch expected=$transactionId actual=${header.transactionId}"
    }
    if (header.type == UsbBulkMtpProtocol.ContainerTypeData && expectedDataOperation != null) {
        check(header.code == expectedDataOperation) {
            "MTP data operation mismatch expected=$expectedDataOperation actual=${header.code}"
        }
    }
}

internal class MtpDataReader(
    private val bytes: ByteArray,
) {
    var offset: Int = 0
        private set

    val remaining: Int
        get() = bytes.size - offset

    fun readUInt8(): Int {
        requireAvailable(1)
        return bytes[offset++].toInt() and 0xFF
    }

    fun readUInt16(): Int {
        requireAvailable(2)
        val value = ((bytes[offset].toInt() and 0xFF)) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8)
        offset += 2
        return value
    }

    fun readInt32(): Int {
        requireAvailable(4)
        val value = ByteBuffer.wrap(bytes, offset, 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int
        offset += 4
        return value
    }

    fun readUInt32(): Long {
        return readInt32().toLong() and 0xFFFF_FFFFL
    }

    fun readUInt64(): Long {
        requireAvailable(8)
        val value = ByteBuffer.wrap(bytes, offset, 8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .long
        offset += 8
        return value
    }

    fun readUInt16Array(): IntArray {
        val count = readUInt32().toInt().coerceAtLeast(0)
        return IntArray(count) { readUInt16() }
    }

    fun readUInt32Array(): IntArray {
        val count = readUInt32().toInt().coerceAtLeast(0)
        return IntArray(count) { readInt32() }
    }

    fun readString(): String {
        val charCount = readUInt8()
        if (charCount == 0) return ""
        val byteCount = charCount * 2
        requireAvailable(byteCount)
        val charsWithoutNull = (charCount - 1).coerceAtLeast(0)
        val value = if (charsWithoutNull == 0) {
            ""
        } else {
            String(bytes, offset, charsWithoutNull * 2, Charsets.UTF_16LE)
        }
        offset += byteCount
        return value
    }

    private fun requireAvailable(byteCount: Int) {
        check(offset + byteCount <= bytes.size) {
            "MTP dataset truncated at offset=$offset need=$byteCount size=${bytes.size}"
        }
    }
}

internal fun Int.toHex16(): String = toString(16).uppercase().padStart(4, '0')
