package com.luisforlo.offlinetransfer.security

import com.luisforlo.offlinetransfer.transfer.TransferCancellation
import com.luisforlo.offlinetransfer.transfer.TransferCancelledException
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

object SecureLinkHandshake {
    const val DEFAULT_PORT = 42818
    private const val MAGIC = 0x4F544853 // "OTHS"
    private const val VERSION = 1
    private const val CONNECT_TIMEOUT_MS = 2_000
    private const val CONNECT_ATTEMPTS = 15
    private const val CONNECT_RETRY_DELAY_MS = 300L
    private const val RECEIVER_ACCEPT_TIMEOUT_MS = 7_000

    fun establishAsSender(
        host: String,
        senderSession: SecureSessionCrypto.SenderSession,
        cancellation: TransferCancellation? = null,
        port: Int = DEFAULT_PORT,
    ): SecureSessionCrypto.SecureLink {
        val socket = connectWithRetry(host, port, cancellation)
        try {
            val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 64 * 1024))
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 64 * 1024))
            val senderPublicKey = senderSession.senderPublicKeyBytes
            val link = senderSession.asSecureLink()

            output.writeInt(MAGIC)
            output.writeInt(VERSION)
            writeBytes(output, senderSession.sessionId)
            writeBytes(output, senderPublicKey)
            writeBytes(
                output,
                SecureSessionCrypto.handshakeMac(
                    sessionKey = link.sessionKey,
                    sessionId = link.sessionId,
                    senderPublicKeyBytes = senderPublicKey,
                    direction = "SENDER",
                ),
            )
            output.flush()

            val accepted = input.readBoolean()
            require(accepted) { "Receiver rejected secure handshake" }
            val receiverProof = readBytes(input, 128)
            val expected = SecureSessionCrypto.handshakeMac(
                sessionKey = link.sessionKey,
                sessionId = link.sessionId,
                senderPublicKeyBytes = senderPublicKey,
                direction = "RECEIVER",
            )
            require(SecureSessionCrypto.constantTimeEquals(receiverProof, expected)) {
                "Secure handshake authentication failed"
            }
            return link
        } catch (error: IOException) {
            if (cancellation?.isCancelled == true) throw TransferCancelledException()
            throw error
        } finally {
            cancellation?.untrack(socket)
            runCatching { socket.close() }
        }
    }

    fun establishAsReceiver(
        receiverSession: SecureSessionCrypto.ReceiverSession,
        cancellation: TransferCancellation? = null,
        port: Int = DEFAULT_PORT,
    ): SecureSessionCrypto.SecureLink {
        val server = ServerSocket()
        cancellation?.track(server)
        try {
            server.reuseAddress = true
            server.soTimeout = RECEIVER_ACCEPT_TIMEOUT_MS
            server.bind(InetSocketAddress(port))
            val socket = server.accept()
            cancellation?.track(socket)
            try {
                val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 64 * 1024))
                val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 64 * 1024))

                require(input.readInt() == MAGIC) { "Secure handshake magic mismatch" }
                require(input.readInt() == VERSION) { "Unsupported secure handshake version" }
                val sessionId = readBytes(input, 64)
                require(SecureSessionCrypto.constantTimeEquals(sessionId, receiverSession.sessionId)) {
                    "Secure session id mismatch"
                }

                val senderPublicKey = readBytes(input, 4096)
                val senderProof = readBytes(input, 128)
                val link = SecureSessionCrypto.deriveReceiverLink(receiverSession, senderPublicKey)
                val expectedSenderProof = SecureSessionCrypto.handshakeMac(
                    sessionKey = link.sessionKey,
                    sessionId = link.sessionId,
                    senderPublicKeyBytes = senderPublicKey,
                    direction = "SENDER",
                )
                val valid = SecureSessionCrypto.constantTimeEquals(senderProof, expectedSenderProof)

                output.writeBoolean(valid)
                if (valid) {
                    writeBytes(
                        output,
                        SecureSessionCrypto.handshakeMac(
                            sessionKey = link.sessionKey,
                            sessionId = link.sessionId,
                            senderPublicKeyBytes = senderPublicKey,
                            direction = "RECEIVER",
                        ),
                    )
                }
                output.flush()
                require(valid) { "Secure sender proof invalid" }
                return link
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
                socket.tcpNoDelay = true
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
        throw lastError ?: IOException("Unable to establish secure control link")
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
}
