package com.luisforlo.offlinetransfer.transfer

import com.luisforlo.offlinetransfer.protocol.TransferHeader
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest

object TcpFileTransfer {
    const val DEFAULT_PORT = 42819
    private const val BUFFER_SIZE = 256 * 1024
    private const val CONNECT_TIMEOUT_MS = 2_000
    private const val CONNECT_ATTEMPTS = 15
    private const val CONNECT_RETRY_DELAY_MS = 300L

    data class Result(
        val header: TransferHeader,
        val bytesTransferred: Long,
        val verified: Boolean,
    )

    fun send(
        host: String,
        header: TransferHeader,
        inputFactory: () -> InputStream,
        port: Int = DEFAULT_PORT,
        cancellation: TransferCancellation? = null,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
    ): Result {
        cancellation?.throwIfCancelled()
        val socket = connectWithRetry(host, port, cancellation)

        try {
            socket.tcpNoDelay = true
            socket.keepAlive = true

            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), BUFFER_SIZE))
            val acknowledgement = DataInputStream(BufferedInputStream(socket.getInputStream()))
            header.writeTo(output)

            var sent = 0L
            inputFactory().use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (sent < header.sizeBytes) {
                    cancellation?.throwIfCancelled()
                    val maxRead = minOf(buffer.size.toLong(), header.sizeBytes - sent).toInt()
                    val read = input.read(buffer, 0, maxRead)
                    check(read >= 0) { "Source ended before declared file size" }
                    output.write(buffer, 0, read)
                    sent += read
                    onProgress(sent, header.sizeBytes)
                }
            }
            output.flush()
            cancellation?.throwIfCancelled()

            val verifiedByReceiver = acknowledgement.readBoolean()
            cancellation?.throwIfCancelled()
            return Result(header, sent, verified = verifiedByReceiver)
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
        port: Int = DEFAULT_PORT,
        cancellation: TransferCancellation? = null,
        onProgress: (received: Long, total: Long) -> Unit = { _, _ -> },
    ): Result {
        cancellation?.throwIfCancelled()
        val server = ServerSocket()
        cancellation?.track(server)

        try {
            server.reuseAddress = true
            server.bind(InetSocketAddress(port))

            val socket = server.accept()
            cancellation?.track(socket)
            try {
                return receiveFromSocket(socket, destination, cancellation, onProgress)
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
        cancellation: TransferCancellation?,
        onProgress: (received: Long, total: Long) -> Unit,
    ): Result {
        socket.keepAlive = true
        val input = DataInputStream(BufferedInputStream(socket.getInputStream(), BUFFER_SIZE))
        val acknowledgement = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
        val header = TransferHeader.readFrom(input)
        require(header.sizeBytes >= 0) { "Invalid file size" }
        require(header.sizeBytes <= 1L shl 50) { "Refusing implausibly large transfer" }

        destination.parentFile?.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        var received = 0L

        destination.outputStream().buffered(BUFFER_SIZE).use { output ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (received < header.sizeBytes) {
                cancellation?.throwIfCancelled()
                val maxRead = minOf(buffer.size.toLong(), header.sizeBytes - received).toInt()
                val read = input.read(buffer, 0, maxRead)
                if (read < 0) {
                    cancellation?.throwIfCancelled()
                    error("Connection closed before transfer completed")
                }
                output.write(buffer, 0, read)
                digest.update(buffer, 0, read)
                received += read
                onProgress(received, header.sizeBytes)
            }
        }

        cancellation?.throwIfCancelled()
        val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
        val verified = actualHash.equals(header.sha256, ignoreCase = true)
        if (!verified) destination.delete()

        acknowledgement.writeBoolean(verified)
        acknowledgement.flush()

        return Result(header, received, verified)
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
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                cancellation?.throwIfCancelled()
                return socket
            } catch (error: IOException) {
                cancellation?.untrack(socket)
                runCatching { socket.close() }
                if (cancellation?.isCancelled == true) throw TransferCancelledException()
                lastError = error
                if (attempt < CONNECT_ATTEMPTS - 1) {
                    Thread.sleep(CONNECT_RETRY_DELAY_MS)
                }
            }
        }

        throw lastError ?: IOException("Unable to connect to receiver")
    }
}
