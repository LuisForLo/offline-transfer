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
    private const val VERSION = 1
    private const val CONNECT_TIMEOUT_MS = 2_000
    private const val CONNECT_ATTEMPTS = 15
    private const val CONNECT_RETRY_DELAY_MS = 300L
    private const val MAX_HEADER_PLAINTEXT = 64 * 1024
    private const val MAX_CIPHERTEXT_FRAME = TcpFileTransfer.STREAM_BUFFER_SIZE + 64

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
            val acknowledgement = DataInputStream(
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

            var sent = 0L
            var counter = 1
            inputFactory().use { rawInput ->
                BufferedInputStream(rawInput, TcpFileTransfer.STREAM_BUFFER_SIZE).use { input ->
                    val buffer = ByteArray(TcpFileTransfer.STREAM_BUFFER_SIZE)
                    while (sent < header.sizeBytes) {
                        cancellation?.throwIfCancelled()
                        val maxRead = minOf(buffer.size.toLong(), header.sizeBytes - sent).toInt()
                        val read = input.read(buffer, 0, maxRead)
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
                        sent += read
                        onProgress(sent, header.sizeBytes)
                    }
                }
            }
            output.flush()
            cancellation?.throwIfCancelled()

            val verified = acknowledgement.readBoolean()
            val ackMac = readBytes(acknowledgement, 128)
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
                bytesTransferred = sent,
                verified = verified,
                transferElapsedMillis = elapsedMillis,
                effectiveSendBufferBytes = effectiveSendBuffer,
                effectiveReceiveBufferBytes = effectiveReceiveBuffer,
                encrypted = true,
                securityVerificationCode = link.verificationCode,
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
    ): TcpFileTransfer.Result {
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
                return receiveFromSocket(socket, destination, link, cancellation, onProgress)
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
        destination: File,
        link: SecureSessionCrypto.SecureLink,
        cancellation: TransferCancellation?,
        onProgress: (received: Long, total: Long) -> Unit,
    ): TcpFileTransfer.Result {
        val effectiveSendBuffer = socket.sendBufferSize
        val effectiveReceiveBuffer = socket.receiveBufferSize
        val input = DataInputStream(
            BufferedInputStream(socket.getInputStream(), TcpFileTransfer.STREAM_BUFFER_SIZE),
        )
        val acknowledgement = DataOutputStream(
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

        destination.parentFile?.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        var received = 0L
        var expectedCounter = 1

        destination.outputStream().use { rawOutput ->
            BufferedOutputStream(rawOutput, TcpFileTransfer.STREAM_BUFFER_SIZE).use { output ->
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
                    output.write(plain)
                    digest.update(plain)
                    received += plain.size
                    onProgress(received, header.sizeBytes)
                }
                output.flush()
            }
        }

        cancellation?.throwIfCancelled()
        val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
        val verified = actualHash.equals(header.sha256, ignoreCase = true)
        if (!verified) destination.delete()

        acknowledgement.writeBoolean(verified)
        writeBytes(
            acknowledgement,
            SecureSessionCrypto.acknowledgementMac(
                sessionKey = link.sessionKey,
                sessionId = link.sessionId,
                sha256 = header.sha256,
                verified = verified,
            ),
        )
        acknowledgement.flush()
        val elapsedMillis = nanosToMillis(System.nanoTime() - startedAt)

        return TcpFileTransfer.Result(
            header = header,
            bytesTransferred = received,
            verified = verified,
            transferElapsedMillis = elapsedMillis,
            effectiveSendBufferBytes = effectiveSendBuffer,
            effectiveReceiveBufferBytes = effectiveReceiveBuffer,
            encrypted = true,
            securityVerificationCode = link.verificationCode,
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
