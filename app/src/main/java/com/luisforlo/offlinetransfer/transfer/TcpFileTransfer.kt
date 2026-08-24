package com.luisforlo.offlinetransfer.transfer

import com.luisforlo.offlinetransfer.protocol.TransferHeader
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest

object TcpFileTransfer {
    const val DEFAULT_PORT = 42819
    private const val BUFFER_SIZE = 256 * 1024

    data class Result(
        val header: TransferHeader,
        val bytesTransferred: Long,
        val verified: Boolean,
    )

    fun send(
        host: String,
        header: TransferHeader,
        inputFactory: () -> InputStream,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
    ): Result {
        Socket(host, DEFAULT_PORT).use { socket ->
            socket.tcpNoDelay = true
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), BUFFER_SIZE))
            header.writeTo(output)

            var sent = 0L
            inputFactory().use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (sent < header.sizeBytes) {
                    val maxRead = minOf(buffer.size.toLong(), header.sizeBytes - sent).toInt()
                    val read = input.read(buffer, 0, maxRead)
                    check(read >= 0) { "Source ended before declared file size" }
                    output.write(buffer, 0, read)
                    sent += read
                    onProgress(sent, header.sizeBytes)
                }
            }
            output.flush()
            return Result(header, sent, verified = true)
        }
    }

    fun receive(
        destination: File,
        onProgress: (received: Long, total: Long) -> Unit = { _, _ -> },
    ): Result {
        ServerSocket(DEFAULT_PORT).use { server ->
            server.reuseAddress = true
            server.accept().use { socket ->
                return receiveFromSocket(socket, destination, onProgress)
            }
        }
    }

    private fun receiveFromSocket(
        socket: Socket,
        destination: File,
        onProgress: (received: Long, total: Long) -> Unit,
    ): Result {
        val input = DataInputStream(BufferedInputStream(socket.getInputStream(), BUFFER_SIZE))
        val header = TransferHeader.readFrom(input)
        require(header.sizeBytes >= 0) { "Invalid file size" }
        require(header.sizeBytes <= 1L shl 50) { "Refusing implausibly large transfer" }

        destination.parentFile?.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        var received = 0L

        destination.outputStream().buffered(BUFFER_SIZE).use { output ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (received < header.sizeBytes) {
                val maxRead = minOf(buffer.size.toLong(), header.sizeBytes - received).toInt()
                val read = input.read(buffer, 0, maxRead)
                check(read >= 0) { "Connection closed before transfer completed" }
                output.write(buffer, 0, read)
                digest.update(buffer, 0, read)
                received += read
                onProgress(received, header.sizeBytes)
            }
        }

        val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
        val verified = actualHash.equals(header.sha256, ignoreCase = true)
        if (!verified) destination.delete()

        return Result(header, received, verified)
    }
}
