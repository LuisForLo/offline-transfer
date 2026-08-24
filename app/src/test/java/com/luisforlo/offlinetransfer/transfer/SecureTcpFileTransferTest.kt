package com.luisforlo.offlinetransfer.transfer

import com.luisforlo.offlinetransfer.protocol.TransferHeader
import com.luisforlo.offlinetransfer.security.SecureSessionCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SecureTcpFileTransferTest {
    @Test
    fun transfersMultipleEncryptedChunksAndAuthenticatesAck() {
        val payload = ByteArray(2_400_000) { ((it * 31) % 251).toByte() }
        val sha = MessageDigest.getInstance("SHA-256")
            .digest(payload)
            .joinToString("") { "%02x".format(it) }
        val header = TransferHeader(
            fileName = "secure.bin",
            mimeType = "application/octet-stream",
            sizeBytes = payload.size.toLong(),
            sha256 = sha,
        )

        val receiverSession = SecureSessionCrypto.createReceiverSession()
        val senderSession = SecureSessionCrypto.createSenderSession(
            receiverSession.sessionIdBase64,
            receiverSession.publicKeyBase64,
        )
        val senderLink = senderSession.asSecureLink()
        val receiverLink = SecureSessionCrypto.deriveReceiverLink(
            receiverSession,
            senderSession.senderPublicKeyBytes,
        )
        assertEquals(senderLink.verificationCode, receiverLink.verificationCode)

        val port = ServerSocket(0).use { it.localPort }
        val destination = File.createTempFile("secure-transfer-test-", ".bin")
        destination.delete()
        val executor = Executors.newSingleThreadExecutor()

        try {
            val receiveFuture = executor.submit<TcpFileTransfer.Result> {
                SecureTcpFileTransfer.receive(
                    destination = destination,
                    link = receiverLink,
                    port = port,
                )
            }

            Thread.sleep(100L)
            val sendResult = SecureTcpFileTransfer.send(
                host = "127.0.0.1",
                link = senderLink,
                header = header,
                inputFactory = { payload.inputStream() },
                port = port,
            )
            val receiveResult = receiveFuture.get(20, TimeUnit.SECONDS)

            assertTrue(sendResult.verified)
            assertTrue(receiveResult.verified)
            assertTrue(sendResult.encrypted)
            assertTrue(receiveResult.encrypted)
            assertEquals(payload.size.toLong(), receiveResult.bytesTransferred)
            assertArrayEquals(payload, destination.readBytes())
        } finally {
            executor.shutdownNow()
            destination.delete()
        }
    }
}
