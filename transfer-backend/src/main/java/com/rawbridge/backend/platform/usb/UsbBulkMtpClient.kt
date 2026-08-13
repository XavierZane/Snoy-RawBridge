package com.rawbridge.backend.platform.usb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.net.Uri
import com.rawbridge.backend.config.UsbModePreference
import com.rawbridge.backend.debug.UsbDebugLogger
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min

internal class UsbBulkMtpClient(
    context: Context,
) : CameraBrowseClient {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager

    override fun probe(
        device: UsbDevice,
        preferredMode: UsbModePreference,
    ): CameraBrowseProbeResult {
        if (!usbManager.hasPermission(device)) {
            return CameraBrowseProbeResult(
                protocolReady = false,
                modeLabel = preferredMode.name,
                browseBackendLabel = UsbBulkMtpProtocol.BackendLabel,
                errorMessage = "相机已连接，但还没有 USB 访问权限。",
            )
        }
        return runCatching {
            openSession(device, preferredMode).use { session ->
                val protocolReady = session.storageIds.isNotEmpty() ||
                    session.deviceDescriptor.operationsSupported.isNotEmpty() ||
                    session.deviceDescriptor.model.isNotBlank()
                CameraBrowseProbeResult(
                    protocolReady = protocolReady,
                    modeLabel = preferredMode.name,
                    browseBackendLabel = session.backendLabel,
                    errorMessage = if (protocolReady) {
                        null
                    } else {
                        "低层 MTP 会话已建立，但相机暂未返回可浏览存储信息。"
                    },
                )
            }
        }.getOrElse { error ->
            UsbDebugLogger.e(DebugTag, "probe failed device=${device.deviceName}", error)
            CameraBrowseProbeResult(
                protocolReady = false,
                modeLabel = preferredMode.name,
                browseBackendLabel = UsbBulkMtpProtocol.BackendLabel,
                errorMessage = error.message ?: "低层 MTP 会话建立失败。",
            )
        }
    }

    override fun openSession(
        device: UsbDevice,
        preferredMode: UsbModePreference,
    ): CameraBrowseSession {
        val opened = openTransport(device)
        return runCatching {
            UsbBulkMtpSession(
                device = device,
                connection = opened.connection,
                claim = opened.claim,
            ).also { it.initialize() }
        }.getOrElse { error ->
            runCatching { opened.connection.releaseInterface(opened.claim.usbInterface) }
            opened.connection.close()
            throw error
        }
    }

    private fun openTransport(
        device: UsbDevice,
    ): OpenedUsbTransport {
        val connection = usbManager.openDevice(device)
            ?: error("无法打开 USB 设备连接。")
        val claim = runCatching {
            claimStillImageTransport(device, connection)
        }.getOrElse { error ->
            connection.close()
            throw error
        }
        return OpenedUsbTransport(
            connection = connection,
            claim = claim,
        )
    }

    private fun claimStillImageTransport(
        device: UsbDevice,
        connection: UsbDeviceConnection,
    ): ClaimedUsbTransport {
        val candidateInterfaces = (0 until device.interfaceCount)
            .map(device::getInterface)
            .sortedWith(
                compareByDescending<UsbInterface> {
                    it.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE
                }.thenBy { it.id },
            )

        candidateInterfaces.forEach { usbInterface ->
            val bulkIn = (0 until usbInterface.endpointCount)
                .map(usbInterface::getEndpoint)
                .firstOrNull { endpoint ->
                    endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        endpoint.direction == UsbConstants.USB_DIR_IN
                }
            val bulkOut = (0 until usbInterface.endpointCount)
                .map(usbInterface::getEndpoint)
                .firstOrNull { endpoint ->
                    endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        endpoint.direction == UsbConstants.USB_DIR_OUT
                }
            val interruptIn = (0 until usbInterface.endpointCount)
                .map(usbInterface::getEndpoint)
                .firstOrNull { endpoint ->
                    endpoint.type == UsbConstants.USB_ENDPOINT_XFER_INT &&
                        endpoint.direction == UsbConstants.USB_DIR_IN
                }

            if (bulkIn == null || bulkOut == null) return@forEach
            if (!connection.claimInterface(usbInterface, true)) return@forEach

            UsbDebugLogger.d(
                DebugTag,
                "claim interface ok id=${usbInterface.id} class=${usbInterface.interfaceClass} subclass=${usbInterface.interfaceSubclass} protocol=${usbInterface.interfaceProtocol} bulkIn=${bulkIn.address}/packet=${bulkIn.maxPacketSize} bulkOut=${bulkOut.address}/packet=${bulkOut.maxPacketSize} interruptIn=${interruptIn?.address ?: -1}",
            )
            return ClaimedUsbTransport(
                usbInterface = usbInterface,
                bulkIn = bulkIn,
                bulkOut = bulkOut,
                interruptIn = interruptIn,
            )
        }

        error("没有找到可用的 Still Image bulk 端点。")
    }

    private inner class UsbBulkMtpSession(
        private val device: UsbDevice,
        private var connection: UsbDeviceConnection,
        private var claim: ClaimedUsbTransport,
    ) : CameraBrowseSession, Closeable {
        override val backendLabel: String = UsbBulkMtpProtocol.BackendLabel
        override lateinit var deviceDescriptor: CameraDeviceDescriptor
            private set
        override var storageIds: IntArray = intArrayOf()
            private set

        private var sessionId: Int = 1
        private var nextTransactionId: Int = 0
        private var isClosed: Boolean = false
        private val bulkReadScratch = ByteArray(ReadBufferSize)
        private val containerReader = MtpContainerStreamReader(readChunk = { destination, destinationOffset, maximumLength ->
            val requestLength = min(maximumLength, bulkReadScratch.size)
            val read = connection.bulkTransfer(
                claim.bulkIn,
                bulkReadScratch,
                requestLength,
                TransferTimeoutMillis,
            )
            if (read <= 0) {
                throw UsbTransportException("USB bulk in 读取失败，actual=$read")
            }
            System.arraycopy(bulkReadScratch, 0, destination, destinationOffset, read)
            read
        })

        fun initialize() {
            val deviceInfo = transact(
                operationCode = UsbBulkMtpProtocol.OperationGetDeviceInfo,
                parameters = intArrayOf(),
            )
            deviceDescriptor = UsbBulkMtpProtocol.parseDeviceDescriptor(deviceInfo.dataPayload ?: byteArrayOf())

            val openSession = transact(
                operationCode = UsbBulkMtpProtocol.OperationOpenSession,
                parameters = intArrayOf(sessionId),
                acceptedResponseCodes = intArrayOf(
                    UsbBulkMtpProtocol.ResponseOk,
                    UsbBulkMtpProtocol.ResponseSessionAlreadyOpen,
                ),
            )
            if (openSession.responseCode == UsbBulkMtpProtocol.ResponseSessionAlreadyOpen) {
                openSession.responseParameters.firstOrNull()
                    ?.takeIf { it > 0 }
                    ?.let { reopenedId -> sessionId = reopenedId }
                UsbDebugLogger.d(
                    DebugTag,
                    "session already open, continue with sessionId=$sessionId",
                )
            }

            storageIds = UsbBulkMtpProtocol.parseStorageIds(
                transact(
                    operationCode = UsbBulkMtpProtocol.OperationGetStorageIds,
                    parameters = intArrayOf(),
                ).dataPayload ?: byteArrayOf(),
            )

            UsbDebugLogger.d(
                DebugTag,
                "session ready backend=$backendLabel model=${deviceDescriptor.model} storageCount=${storageIds.size}",
            )
        }

        override fun getStorageInfo(storageId: Int): CameraStorageDescriptor? {
            return runCatching {
                val result = transactRecoveringTransport(
                    operationCode = UsbBulkMtpProtocol.OperationGetStorageInfo,
                    parameters = intArrayOf(storageId),
                )
                UsbBulkMtpProtocol.parseStorageDescriptor(
                    storageId = storageId,
                    payload = result.dataPayload ?: byteArrayOf(),
                )
            }.getOrElse { error ->
                UsbDebugLogger.w(
                    DebugTag,
                    "GetStorageInfo failed storageId=$storageId error=${error.message.orEmpty()}",
                )
                null
            }
        }

        override fun getObjectHandles(
            storageId: Int,
            parentHandle: Int,
        ): IntArray {
            val result = transactRecoveringTransport(
                operationCode = UsbBulkMtpProtocol.OperationGetObjectHandles,
                parameters = intArrayOf(
                    storageId,
                    0,
                    if (parentHandle == UsbBulkMtpProtocol.RootParentHandle) 0 else parentHandle,
                ),
            )
            return UsbBulkMtpProtocol.parseObjectHandles(result.dataPayload ?: byteArrayOf())
        }

        override fun getObjectInfo(handle: Int): CameraObjectDescriptor? {
            val result = transactRecoveringTransport(
                operationCode = UsbBulkMtpProtocol.OperationGetObjectInfo,
                parameters = intArrayOf(handle),
            )
            return UsbBulkMtpProtocol.parseObjectDescriptor(result.dataPayload ?: byteArrayOf())
                .copy(objectHandle = handle)
        }

        override fun getThumbnail(handle: Int): CameraThumbnailResult? {
            return runCatching {
                val result = transactRecoveringTransport(
                    operationCode = UsbBulkMtpProtocol.OperationGetThumb,
                    parameters = intArrayOf(handle),
                )
                val payload = result.dataPayload ?: return null
                CameraThumbnailResult(
                    bytes = payload,
                    operationLabel = UsbBulkMtpProtocol.operationLabel(result.operationCode),
                )
            }.getOrElse { error ->
                UsbDebugLogger.w(
                    DebugTag,
                    "GetThumb failed handle=$handle error=${error.message.orEmpty()}",
                )
                null
            }
        }

        override fun readObjectToFile(
            handle: Int,
            expectedSizeBytes: Long?,
            target: File,
        ): CameraReadResult {
            target.parentFile?.mkdirs()
            val normalizedExpectedSizeBytes = normalizeExpectedObjectSize(expectedSizeBytes)
            val supportsPartial64 = deviceDescriptor.operationsSupported
                .any { it == UsbBulkMtpProtocol.OperationGetPartialObject64 }
            val supportsPartial32 = deviceDescriptor.operationsSupported
                .any { it == UsbBulkMtpProtocol.OperationGetPartialObject }

            return runCatching {
                val result = when {
                    supportsPartial64 -> readObjectToFileViaPartial64(
                        handle = handle,
                        expectedSizeBytes = normalizedExpectedSizeBytes,
                        target = target,
                    )
                    supportsPartial32 -> readObjectToFileViaPartial32(
                        handle = handle,
                        expectedSizeBytes = normalizedExpectedSizeBytes,
                        target = target,
                    )
                    else -> readObjectToFileViaFullObject(
                        handle = handle,
                        expectedSizeBytes = normalizedExpectedSizeBytes,
                        target = target,
                    )
                }
                requireExpectedObjectSize(
                    expectedSizeBytes = normalizedExpectedSizeBytes,
                    actualSizeBytes = result.bytesRead,
                )
                result
            }.recoverCatching { error ->
                UsbDebugLogger.w(
                    DebugTag,
                    "primary object read failed handle=$handle reason=${error.message.orEmpty()} fallback=GetObject",
                )
                if (supportsPartial64 || supportsPartial32) {
                    reopenTransport()
                }
                readObjectToFileViaFullObject(
                    handle = handle,
                    expectedSizeBytes = normalizedExpectedSizeBytes,
                    target = target,
                )
            }.getOrElse { error ->
                target.delete()
                throw error
            }
        }

        private fun readObjectToFileViaPartial64(
            handle: Int,
            expectedSizeBytes: Long?,
            target: File,
        ): CameraReadResult {
            var offset = 0L
            var totalBytesRead = 0L
            val chunkSize = DefaultReadChunkSize.toLong()
            FileOutputStream(target).use { output ->
                while (true) {
                    val requestBytes = expectedSizeBytes?.let { expected ->
                        min(chunkSize, (expected - offset).coerceAtLeast(0L))
                    } ?: chunkSize
                    if (requestBytes <= 0L) break

                    val response = transact(
                        operationCode = UsbBulkMtpProtocol.OperationGetPartialObject64,
                        parameters = buildPartialObject64Parameters(
                            handle = handle,
                            offset = offset,
                            maximumBytes = requestBytes.toInt(),
                        ),
                    )
                    val payload = response.dataPayload ?: byteArrayOf()
                    if (payload.isEmpty()) break

                    output.write(payload)
                    totalBytesRead += payload.size
                    offset += payload.size

                    if (payload.size.toLong() < requestBytes) break
                    if (expectedSizeBytes != null && totalBytesRead >= expectedSizeBytes) break
                }
            }
            return CameraReadResult(
                bytesRead = totalBytesRead,
                operationLabel = "GetPartialObject64",
            )
        }

        private fun readObjectToFileViaPartial32(
            handle: Int,
            expectedSizeBytes: Long?,
            target: File,
        ): CameraReadResult {
            var offset = 0L
            var totalBytesRead = 0L
            val chunkSize = DefaultReadChunkSize.toLong()
            FileOutputStream(target).use { output ->
                while (true) {
                    val requestBytes = expectedSizeBytes?.let { expected ->
                        min(chunkSize, (expected - offset).coerceAtLeast(0L))
                    } ?: chunkSize
                    if (requestBytes <= 0L) break
                    if (requestBytes > 0xFFFF_FFFFL) {
                        error("GetPartialObject 不支持超过 UInt32 的分块")
                    }

                    val response = transact(
                        operationCode = UsbBulkMtpProtocol.OperationGetPartialObject,
                        parameters = intArrayOf(
                            handle,
                            toPartialObjectOffset(offset),
                            requestBytes.toInt(),
                        ),
                    )
                    val payload = response.dataPayload ?: byteArrayOf()
                    if (payload.isEmpty()) break

                    output.write(payload)
                    totalBytesRead += payload.size
                    offset += payload.size

                    if (payload.size.toLong() < requestBytes) break
                    if (expectedSizeBytes != null && totalBytesRead >= expectedSizeBytes) break
                }
            }
            return CameraReadResult(
                bytesRead = totalBytesRead,
                operationLabel = "GetPartialObject",
            )
        }

        private fun readObjectToFileViaFullObject(
            handle: Int,
            expectedSizeBytes: Long?,
            target: File,
        ): CameraReadResult {
            val transactionId = nextTransactionId + 1
            nextTransactionId = transactionId
            writeBulkOut(
                UsbBulkMtpProtocol.buildCommandContainer(
                    code = UsbBulkMtpProtocol.OperationGetObject,
                    transactionId = transactionId,
                    parameters = intArrayOf(handle),
                ),
            )

            var responseCode: Int? = null
            var responseParameters = intArrayOf()
            var totalBytesRead = 0L

            FileOutputStream(target).use { output ->
                while (responseCode == null) {
                    val header = containerReader.readHeader()
                    if (header.type != UsbBulkMtpProtocol.ContainerTypeEvent) {
                        requireExpectedMtpTransaction(
                            header = header,
                            transactionId = transactionId,
                            expectedDataOperation = UsbBulkMtpProtocol.OperationGetObject,
                        )
                    }
                    when (header.type) {
                        UsbBulkMtpProtocol.ContainerTypeData -> {
                            totalBytesRead += containerReader.copyPayloadTo(header, output)
                        }

                        UsbBulkMtpProtocol.ContainerTypeResponse -> {
                            responseCode = header.code
                            responseParameters = UsbBulkMtpProtocol.parseResponseParameters(
                                containerReader.readPayload(header),
                            )
                        }

                        UsbBulkMtpProtocol.ContainerTypeEvent -> {
                            containerReader.readPayload(header)
                            UsbDebugLogger.d(
                                DebugTag,
                                "mtp event code=${UsbBulkMtpProtocol.responseLabel(header.code)} tx=${header.transactionId}",
                            )
                        }

                        else -> containerReader.readPayload(header)
                    }
                }
            }

            logTransaction(
                operationCode = UsbBulkMtpProtocol.OperationGetObject,
                transactionId = transactionId,
                responseCode = responseCode ?: UsbBulkMtpProtocol.ResponseGeneralError,
                parameters = intArrayOf(handle),
                responseParameters = responseParameters,
                byteCount = totalBytesRead.toInt().coerceAtLeast(0),
            )
            if (responseCode != UsbBulkMtpProtocol.ResponseOk) {
                throw MtpOperationException(
                    operationCode = UsbBulkMtpProtocol.OperationGetObject,
                    responseCode = responseCode ?: UsbBulkMtpProtocol.ResponseGeneralError,
                    transactionId = transactionId,
                )
            }
            requireExpectedObjectSize(
                expectedSizeBytes = expectedSizeBytes,
                actualSizeBytes = totalBytesRead,
            )
            return CameraReadResult(
                bytesRead = totalBytesRead,
                operationLabel = "GetObject",
            )
        }

        private fun reopenTransport() {
            runCatching { connection.releaseInterface(claim.usbInterface) }
            runCatching { connection.close() }
            containerReader.reset()

            val reopened = runCatching {
                openTransport(device)
            }.getOrElse { error ->
                throw UsbTransportException(
                    error.message ?: "无法重新打开 USB 设备连接",
                )
            }

            connection = reopened.connection
            claim = reopened.claim
            val reopenResult = transact(
                operationCode = UsbBulkMtpProtocol.OperationOpenSession,
                parameters = intArrayOf(sessionId),
                acceptedResponseCodes = intArrayOf(
                    UsbBulkMtpProtocol.ResponseOk,
                    UsbBulkMtpProtocol.ResponseSessionAlreadyOpen,
                ),
            )
            if (reopenResult.responseCode == UsbBulkMtpProtocol.ResponseSessionAlreadyOpen) {
                reopenResult.responseParameters.firstOrNull()
                    ?.takeIf { it > 0 }
                    ?.let { reopenedId -> sessionId = reopenedId }
            }
            UsbDebugLogger.d(
                DebugTag,
                "usb connection reopened and session re-opened sessionId=$sessionId",
            )
        }

        private fun transactRecoveringTransport(
            operationCode: Int,
            parameters: IntArray,
            acceptedResponseCodes: IntArray = intArrayOf(UsbBulkMtpProtocol.ResponseOk),
        ): MtpOperationResult {
            return try {
                transact(
                    operationCode = operationCode,
                    parameters = parameters,
                    acceptedResponseCodes = acceptedResponseCodes,
                )
            } catch (error: Throwable) {
                if (!error.isTransportFailure()) {
                    throw error
                }
                UsbDebugLogger.w(
                    DebugTag,
                    "transport error during ${UsbBulkMtpProtocol.operationLabel(operationCode)}, retrying once reason=${error.message.orEmpty()}",
                )
                reopenTransport()
                transact(
                    operationCode = operationCode,
                    parameters = parameters,
                    acceptedResponseCodes = acceptedResponseCodes,
                )
            }
        }

        private fun transact(
            operationCode: Int,
            parameters: IntArray,
            acceptedResponseCodes: IntArray = intArrayOf(UsbBulkMtpProtocol.ResponseOk),
        ): MtpOperationResult {
            check(!isClosed) { "USB MTP 会话已经关闭。" }
            val transactionId = nextTransactionId + 1
            nextTransactionId = transactionId
            writeBulkOut(
                UsbBulkMtpProtocol.buildCommandContainer(
                    code = operationCode,
                    transactionId = transactionId,
                    parameters = parameters,
                ),
            )

            var dataPayload: ByteArray? = null
            var responseCode: Int? = null
            var responseParameters = intArrayOf()

            while (responseCode == null) {
                val packet = readContainerPacket()
                if (packet.header.type != UsbBulkMtpProtocol.ContainerTypeEvent) {
                    requireExpectedMtpTransaction(
                        header = packet.header,
                        transactionId = transactionId,
                        expectedDataOperation = operationCode,
                    )
                }
                when (packet.header.type) {
                    UsbBulkMtpProtocol.ContainerTypeData -> dataPayload = packet.payload
                    UsbBulkMtpProtocol.ContainerTypeResponse -> {
                        responseCode = packet.header.code
                        responseParameters = UsbBulkMtpProtocol.parseResponseParameters(packet.payload)
                    }
                    UsbBulkMtpProtocol.ContainerTypeEvent -> {
                        UsbDebugLogger.d(
                            DebugTag,
                            "mtp event code=${UsbBulkMtpProtocol.responseLabel(packet.header.code)} tx=${packet.header.transactionId}",
                        )
                    }
                }
            }

            logTransaction(
                operationCode = operationCode,
                transactionId = transactionId,
                responseCode = responseCode,
                parameters = parameters,
                responseParameters = responseParameters,
                byteCount = dataPayload?.size ?: 0,
            )

            if (responseCode !in acceptedResponseCodes) {
                throw MtpOperationException(
                    operationCode = operationCode,
                    responseCode = responseCode,
                    transactionId = transactionId,
                )
            }

            return MtpOperationResult(
                operationCode = operationCode,
                transactionId = transactionId,
                responseCode = responseCode,
                responseParameters = responseParameters,
                dataPayload = dataPayload,
            )
        }

        private fun writeBulkOut(bytes: ByteArray) {
            var offset = 0
            while (offset < bytes.size) {
                val remaining = bytes.size - offset
                val chunk = if (offset == 0) bytes else bytes.copyOfRange(offset, bytes.size)
                val written = connection.bulkTransfer(
                    claim.bulkOut,
                    chunk,
                    remaining,
                    TransferTimeoutMillis,
                )
                if (written <= 0 || written > remaining) {
                    throw UsbTransportException(
                        "USB bulk out 写入失败，expected=$remaining actual=$written total=${bytes.size}",
                    )
                }
                if (written < remaining) {
                    UsbDebugLogger.w(
                        DebugTag,
                        "usb bulk out short write endpoint=${claim.bulkOut.address} expected=$remaining actual=$written total=${bytes.size}",
                    )
                }
                offset += written
            }
        }

        private fun readContainerPacket(): MtpPacket {
            val header = containerReader.readHeader()
            return MtpPacket(
                header = header,
                payload = containerReader.readPayload(header),
            )
        }

        private fun logTransaction(
            operationCode: Int,
            transactionId: Int,
            responseCode: Int,
            parameters: IntArray,
            responseParameters: IntArray,
            byteCount: Int,
        ) {
            UsbDebugLogger.d(
                DebugTag,
                buildString {
                    append("mtp ")
                    append(UsbBulkMtpProtocol.operationLabel(operationCode))
                    append(" opcode=0x")
                    append(operationCode.toHex16())
                    append(" tx=")
                    append(transactionId)
                    append(" resp=")
                    append(UsbBulkMtpProtocol.responseLabel(responseCode))
                    append("(0x")
                    append(responseCode.toHex16())
                    append(')')
                    append(" params=")
                    append(parameters.joinToString(prefix = "[", postfix = "]"))
                    append(" respParams=")
                    append(responseParameters.joinToString(prefix = "[", postfix = "]"))
                    append(" bytes=")
                    append(byteCount)
                },
            )
        }

        override fun close() {
            if (isClosed) return
            runCatching {
                if (::deviceDescriptor.isInitialized) {
                    transact(
                        operationCode = UsbBulkMtpProtocol.OperationCloseSession,
                        parameters = intArrayOf(),
                        acceptedResponseCodes = intArrayOf(
                            UsbBulkMtpProtocol.ResponseOk,
                            UsbBulkMtpProtocol.ResponseSessionNotOpen,
                        ),
                    )
                }
            }
            isClosed = true
            runCatching { connection.releaseInterface(claim.usbInterface) }
            connection.close()
        }
    }

    private data class OpenedUsbTransport(
        val connection: UsbDeviceConnection,
        val claim: ClaimedUsbTransport,
    )

    private data class ClaimedUsbTransport(
        val usbInterface: UsbInterface,
        val bulkIn: UsbEndpoint,
        val bulkOut: UsbEndpoint,
        val interruptIn: UsbEndpoint?,
    )

    private data class MtpPacket(
        val header: MtpContainerHeader,
        val payload: ByteArray,
    )

    private data class MtpOperationResult(
        val operationCode: Int,
        val transactionId: Int,
        val responseCode: Int,
        val responseParameters: IntArray,
        val dataPayload: ByteArray?,
    )

    private companion object {
        const val DebugTag = "RawBridgeUsbDebug"
        const val TransferTimeoutMillis = 4_000
        const val ReadBufferSize = 16 * 1024
        const val DefaultReadChunkSize = 512 * 1024
    }
}

private class UsbTransportException(
    detailMessage: String,
) : IllegalStateException(detailMessage)

private fun Throwable.isTransportFailure(): Boolean {
    return this is UsbTransportException ||
        message?.contains("USB bulk", ignoreCase = true) == true ||
        message?.contains("USB 接口", ignoreCase = true) == true ||
        message?.contains("USB 设备连接", ignoreCase = true) == true
}

internal fun File.toUriString(): String = Uri.fromFile(this).toString()
