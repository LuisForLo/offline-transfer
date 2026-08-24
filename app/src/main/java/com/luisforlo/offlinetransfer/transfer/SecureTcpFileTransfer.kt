package com.luisforlo.offlinetransfer.transfer

import com.luisforlo.offlinetransfer.protocol.TransferHeader
import com.luisforlo.offlinetransfer.security.SecureSessionCrypto
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object SecureTcpFileTransfer {
    const val DEFAULT_PORT = TcpFileTransfer.DEFAULT_PORT
    private const val MAGIC = 0x4F545332 // "OTS2"
    private const val VERSION = 2
    private const val CONNECT_TIMEOUT_MS = 2_000
    private const val CONNECT_ATTEMPTS = 15
    private const val CONNECT_RETRY_DELAY_MS = 300L
    private const val MAX_HEADER_PLAINTEXT = 64 * 1024
    private const val MAX_CIPHERTEXT_FRAME = TcpFileTransfer.STREAM_BUFFER_SIZE + 64

    data class ReceiveResult(
        val transfer: TcpFileTransfer.Result,
        val destination: File,
    )

    fun send(
        host: String,
        link: SecureSessionCrypto.SecureLink,
        header: TransferHeader,
        inputFactory: () -> InputStream,
        port: Int = DEFAULT_PORT,
        cancellation: TransferCancellation? = null,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
    ): TcpFileTransfer.Result {
        cancellation?.throwIfCancelled()
        val socket = connectWithRetry(host, port, cancellation)

        try {
            configureSocket(socket)
            val effectiveSendBuffer = socket.sendBufferSize
            val effectiveReceiveBuffer = socket.receiveBufferSize
            val output = DataOutputStream(
                BufferedOutputStream(socket.getOutputStream(), TcpFileTransfer.STREAM_BUFFER_SIZE),
            )
            val input = DataInputStream(
                BufferedInputStream(socket.getInputStream(), 64 * 1024),
            )
            val noncePrefix = SecureSessionCrypto.newNoncePrefix()

            val startedAt = System.nanoTime()
            output.writeInt(MAGIC)
            output.writeInt(VERSION)
            writeBytes(output, link.sessionId)
            writeBytes(output, noncePrefix)

            val headerBytes = serializeHeader(header)
            writeEncryptedFrame(
                output = output,
                link = link,
                noncePrefix = noncePrefix,
                counter = 0,
                plaintext = headerBytes,
                length = headerBytes.size,
            )
            output.flush()

            val resumeOffset = input.readLong()
            require(resumeOffset in 0..header.sizeBytes) { "Invalid resume offset" }
            val resumeProof = readBytes(input, 128)
            val expectedResumeProof = SecureSessionCrypto.resumeMac(
                sessionKey = link.sessionKey,
                sessionId = link.sessionId,
                sha256 = header.sha256,
                sizeBytes = header.sizeBytes,
                resumeOffset = resumeOffset,
            )
            require(SecureSessionCrypto.constantTimeEquals(resumeProof, expectedResumeProof)) {
                "Resume offset authentication failed"
            }

            onProgress(resumeOffset, header.sizeBytes)
            var filePosition = resumeOffset
            var sentThisConnection = 0L
            var counter = 1

            inputFactory().use { rawInput ->
                BufferedInputStream(rawInput, TcpFileTransfer.STREAM_BUFFER_SIZE).use { fileInput ->
                    skipExactly(fileInput, resumeOffset, cancellation)
                    val buffer = ByteArray(TcpFileTransfer.STREAM_BUFFER_SIZE)
                    while (filePosition < header.sizeBytes) {
                        cancellation?.throwIfCancelled()
                        val maxRead = minOf(buffer.size.toLong(), header.sizeBytes - filePosition).toInt()
                        val read = fileInput.read(buffer, 0, maxRead)
                        check(read >= 0) { "Source ended before declared file size" }
                        if (read == 0) continue
                        writeEncryptedFrame(
                            output = output,
                            link = link,
                            noncePrefix = noncePrefix,
                            counter = counter++,
                            plaintext = buffer,
                            length = read,
                        )
                        filePosition += read
                        sentThisConnection += read
                        onProgress(filePosition, header.sizeBytes)
                    }
                }
            }
            output.flush()
            cancellation?.throwIfCancelled()

            val verified = input.readBoolean()
            val ackMac = readBytes(input, 128)
            val expectedMac = SecureSessionCrypto.acknowledgementMac(
                sessionKey = link.sessionKey,
                sessionId = link.sessionId,
                sha256 = header.sha256,
                verified = verified,
            )
            require(SecureSessionCrypto.constantTimeEquals(ackMac, expectedMac)) {
                "Receiver acknowledgement authentication failed"
            }
            val elapsedMillis = nanosToMillis(System.nanoTime() - startedAt)

            return TcpFileTransfer.Result(
                header = header,
                bytesTransferred = header.sizeBytes,
                verified = verified,
                transferElapsedMillis = elapsedMillis,
                effectiveSendBufferBytes = effectiveSendBuffer,
                effectiveReceiveBufferBytes = effectiveReceiveBuffer,
                encrypted = true,
                securityVerificationCode = link.verificationCode,
                resumedFromBytes = resumeOffset,
                networkBytesTransferred = sentThisConnection,
            )
        } catch (error: IOException) {
            if (cancellation?.isCancelled == true) throw TransferCancelledException()
            throw error
        } finally {
            cancellation?.untrack(socket)
            runCatching { socket.close() }
        }
    }

    fun receive(
        destination: File,
        link: SecureSessionCrypto.SecureLink,
        port: Int = DEFAULT_PORT,
        cancellation: TransferCancellation? = null,
        onProgress: (received: Long, total: Long) -> Unit = { _, _ -> },
    ): TcpFileTransfer.Result = receiveInternal(
        link = link,
        port = port,
        cancellation = cancellation,
        onProgress = onProgress,
        allowResume = false,
        destinationFor = { destination },
    ).transfer

    fun receiveResumable(
        link: SecureSessionCrypto.SecureLink,
        destinationFor: (TransferHeader) -> File,
        port: Int = DEFAULT_PORT,
        cancellation: TransferCancellation? = null,
        onProgress: (received: Long, total: Long) -> Unit = { _, _ -> },
    ): ReceiveResult = receiveInternal(
        link = link,
        port = port,
        cancellation = cancellation,
        onProgress = onProgress,
        allowResume = true,
        destinationFor = destinationFor,
    )

    private fun receiveInternal(
        link: SecureSessionCrypto.SecureLink,
        port: Int,
        cancellation: TransferCancellation?,
        onProgress: (received: Long, total: Long) -> Unit,
        allowResume: Boolean,
        destinationFor: (TransferHeader) -> File,
    ): ReceiveResult {
        cancellation?.throwIfCancelled()
        val server = ServerSocket()
        cancellation?.track(server)

        try {
            server.reuseAddress = true
            server.receiveBufferSize = TcpFileTransfer.REQUESTED_SOCKET_BUFFER_SIZE
            server.bind(InetSocketAddress(port))
            val socket = server.accept()
            cancellation?.track(socket)
            try {
                configureSocket(socket)
                return receiveFromSocket(
                    socket = socket,
                    link = link,
                    cancellation = cancellation,
                    onProgress = onProgress,
                    allowResume = allowResume,
                    destinationFor = destinationFor,
                )
            } finally {
                cancellation?.untrack(socket)
                runCatching { socket.close() }
            }
        } catch (error: IOException) {
            if (cancellation?.isCancelled == true) throw TransferCancelledException()
            throw error
        } finally {
            cancellation?.untrack(server)
            runCatching { server.close() }
        }
    }

    private fun receiveFromSocket(
        socket: Socket,
        link: SecureSessionCrypto.SecureLink,
        cancellation: TransferCancellation?,
        onProgress: (received: Long, total: Long) -> Unit,
        allowResume: Boolean,
        destinationFor: (TransferHeader) -> File,
    ): ReceiveResult {
        val effectiveSendBuffer = socket.sendBufferSize
        val effectiveReceiveBuffer = socket.receiveBufferSize
        val input = DataInputStream(
            BufferedInputStream(socket.getInputStream(), TcpFileTransfer.STREAM_BUFFER_SIZE),
        )
        val output = DataOutputStream(
            BufferedOutputStream(socket.getOutputStream(), 64 * 1024),
        )
        val startedAt = System.nanoTime()

        require(input.readInt() == MAGIC) { "Secure transfer magic mismatch" }
        require(input.readInt() == VERSION) { "Unsupported secure transfer version" }
        val sessionId = readBytes(input, 64)
        require(SecureSessionCrypto.constantTimeEquals(sessionId, link.sessionId)) {
            "Secure transfer session mismatch"
        }
        val noncePrefix = readBytes(input, 32)
        require(noncePrefix.size == 8) { "Invalid secure nonce prefix" }

        val encryptedHeader = readEncryptedFrame(input, MAX_HEADER_PLAINTEXT)
        require(encryptedHeader.counter == 0) { "Secure header frame out of order" }
        val headerPlain = SecureSessionCrypto.decryptChunk(
            sessionKey = link.sessionKey,
            sessionId = link.sessionId,
            noncePrefix = noncePrefix,
            counter = 0,
            ciphertext = encryptedHeader.ciphertext,
        )
        require(headerPlain.size == encryptedHeader.plainLength) { "Secure header length mismatch" }
        val header = deserializeHeader(headerPlain)
        require(header.sizeBytes >= 0) { "Invalid file size" }
        require(header.sizeBytes <= 1L shl 50) { "Refusing implausibly large transfer" }

        val destination = destinationFor(header)
        destination.parentFile?.mkdirs()
        if (!allowResume && destination.exists()) destination.delete()
        if (destination.exists() && destination.length() > header.sizeBytes) destination.delete()

        val resumeOffset = if (allowResume && destination.exists()) {
            destination.length().coerceIn(0L, header.sizeBytes)
        } else {
            0L
        }

        output.writeLong(resumeOffset)
        writeBytes(
            output,
            SecureSessionCrypto.resumeMac(
                sessionKey = link.sessionKey,
                sessionId = link.sessionId,
                sha256 = header.sha256,
                sizeBytes = header.sizeBytes,
                resumeOffset = resumeOffset,
            ),
        )
        output.flush()
        onProgress(resumeOffset, header.sizeBytes)

        var received = resumeOffset
        var networkReceived = 0L
        var expectedCounter = 1

        FileOutputStream(destination, resumeOffset > 0L).use { rawOutput ->
            BufferedOutputStream(rawOutput, TcpFileTransfer.STREAM_BUFFER_SIZE).use { fileOutput ->
                while (received < header.sizeBytes) {
                    cancellation?.throwIfCancelled()
                    val frame = readEncryptedFrame(input, TcpFileTransfer.STREAM_BUFFER_SIZE)
                    require(frame.counter == expectedCounter++) { "Secure frame counter mismatch" }
                    val plain = SecureSessionCrypto.decryptChunk(
                        sessionKey = link.sessionKey,
                        sessionId = link.sessionId,
                        noncePrefix = noncePrefix,
                        counter = frame.counter,
                        ciphertext = frame.ciphertext,
                    )
                    require(plain.size == frame.plainLength) { "Secure frame length mismatch" }
                    require(received + plain.size <= header.sizeBytes) { "Secure payload exceeds declared size" }
                    fileOutput.write(plain)
                    received += plain.size
                    networkReceived += plain.size
                    onProgress(received, header.sizeBytes)
                }
                fileOutput.flush()
            }
        }

        cancellation?.throwIfCancelled()
        val actualHash = sha256(destination, cancellation)
        val verified = actualHash.equals(header.sha256, ignoreCase = true)
        if (!verified) destination.delete()

        output.writeBoolean(verified)
        writeBytes(
            output,
            SecureSessionCrypto.acknowledgementMac(
                sessionKey = link.sessionKey,
                sessionId = link.sessionId,
                sha256 = header.sha256,
                verified = verified,
            ),
        )
        output.flush()
        val elapsedMillis = nanosToMillis(System.nanoTime() - startedAt)

        return ReceiveResult(
            transfer = TcpFileTransfer.Result(
                header = header,
                bytesTransferred = received,
                verified = verified,
                transferElapsedMillis = elapsedMillis,
                effectiveSendBufferBytes = effectiveSendBuffer,
                effectiveReceiveBufferBytes = effectiveReceiveBuffer,
                encrypted = true,
                securityVerificationCode = link.verificationCode,
                resumedFromBytes = resumeOffset,
                networkBytesTransferred = networkReceived,
            ),
            destination = destination,
        )
    }

    private data class EncryptedFrame(
        val counter: Int,
        val plainLength: Int,
        val ciphertext: ByteArray,
    )

    private fun writeEncryptedFrame(
        output: DataOutputStream,
        link: SecureSessionCrypto.SecureLink,
        noncePrefix: ByteArray,
        counter: Int,
        plaintext: ByteArray,
        length: Int,
    ) {
        val cipher = SecureSessionCrypto.encryptChunk(
            sessionKey = link.sessionKey,
            sessionId = link.sessionId,
            noncePrefix = noncePrefix,
            counter = counter,
            plaintext = plaintext,
            length = length,
        )
        output.writeInt(counter)
        output.writeInt(length)
        output.writeInt(cipher.size)
        output.write(cipher)
    }

    private fun readEncryptedFrame(input: DataInputStream, maxPlaintext: Int): EncryptedFrame {
        val counter = input.readInt()
        val plainLength = input.readInt()
        val cipherLength = input.readInt()
        require(counter >= 0) { "Invalid secure frame counter" }
        require(plainLength in 0..maxPlaintext) { "Invalid secure plaintext frame size" }
        require(cipherLength in 16..(maxPlaintext + 32)) { "Invalid secure ciphertext frame size" }
        require(cipherLength <= MAX_CIPHERTEXT_FRAME || maxPlaintext < TcpFileTransfer.STREAM_BUFFER_SIZE) {
            "Secure frame too large"
        }
        val ciphertext = ByteArray(cipherLength)
        input.readFully(ciphertext)
        return EncryptedFrame(counter, plainLength, ciphertext)
    }

    private fun serializeHeader(header: TransferHeader): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use(header::writeTo)
        return output.toByteArray()
    }

    private fun deserializeHeader(bytes: ByteArray): TransferHeader =
        DataInputStream(ByteArrayInputStream(bytes)).use(TransferHeader::readFrom)

    private fun skipExactly(
        input: InputStream,
        bytesToSkip: Long,
        cancellation: TransferCancellation?,
    ) {
        var remaining = bytesToSkip
        val discard = ByteArray(TcpFileTransfer.STREAM_BUFFER_SIZE)
        while (remaining > 0L) {
            cancellation?.throwIfCancelled()
            val skipped = input.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
                continue
            }
            val read = input.read(discard, 0, minOf(discard.size.toLong(), remaining).toInt())
            check(read >= 0) { "Source ended before resume offset" }
            remaining -= read
        }
    }

    private fun sha256(file: File, cancellation: TransferCancellation?): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { raw ->
            BufferedInputStream(raw, TcpFileTransfer.STREAM_BUFFER_SIZE).use { input ->
                val buffer = ByteArray(TcpFileTransfer.STREAM_BUFFER_SIZE)
                while (true) {
                    cancellation?.throwIfCancelled()
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) digest.update(buffer, 0, read)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun connectWithRetry(
        host: String,
        port: Int,
        cancellation: TransferCancellation?,
    ): Socket {
        var lastError: IOException? = null
        repeat(CONNECT_ATTEMPTS) { attempt ->
            cancellation?.throwIfCancelled()
            val socket = Socket()
            cancellation?.track(socket)
            try {
                socket.sendBufferSize = TcpFileTransfer.REQUESTED_SOCKET_BUFFER_SIZE
                socket.receiveBufferSize = TcpFileTransfer.REQUESTED_SOCKET_BUFFER_SIZE
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                cancellation?.throwIfCancelled()
                return socket
            } catch (error: IOException) {
                cancellation?.untrack(socket)
                runCatching { socket.close() }
                if (cancellation?.isCancelled == true) throw TransferCancelledException()
                lastError = error
                if (attempt < CONNECT_ATTEMPTS - 1) Thread.sleep(CONNECT_RETRY_DELAY_MS)
            }
        }
        throw lastError ?: IOException("Unable to connect to secure receiver")
    }

    private fun configureSocket(socket: Socket) {
        socket.tcpNoDelay = true
        socket.keepAlive = true
        runCatching { socket.sendBufferSize = TcpFileTransfer.REQUESTED_SOCKET_BUFFER_SIZE }
        runCatching { socket.receiveBufferSize = TcpFileTransfer.REQUESTED_SOCKET_BUFFER_SIZE }
    }

    private fun writeBytes(output: DataOutputStream, bytes: ByteArray) {
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun readBytes(input: DataInputStream, maxBytes: Int): ByteArray {
        val size = input.readInt()
        require(size in 1..maxBytes) { "Invalid secure field size" }
        return ByteArray(size).also(input::readFully)
    }

    private fun nanosToMillis(nanos: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(nanos).coerceAtLeast(1L)
}
