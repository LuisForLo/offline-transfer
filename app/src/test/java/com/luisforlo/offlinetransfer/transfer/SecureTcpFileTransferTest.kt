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
        val header = headerFor(payload, "secure.bin")
        val (senderLink, receiverLink) = secureLinks()

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
            assertEquals(payload.size.toLong(), sendResult.networkBytesTransferred)
            assertArrayEquals(payload, destination.readBytes())
        } finally {
            executor.shutdownNow()
            destination.delete()
        }
    }

    @Test
    fun resumesEncryptedTransferFromExistingAuthenticatedPrefix() {
        val payload = ByteArray(3_700_000) { ((it * 17 + 11) % 251).toByte() }
        val resumeOffset = 1_300_123
        val header = headerFor(payload, "resume.bin")
        val (senderLink, receiverLink) = secureLinks()

        val port = ServerSocket(0).use { it.localPort }
        val destination = File.createTempFile("secure-resume-test-", ".part")
        destination.writeBytes(payload.copyOfRange(0, resumeOffset))
        val executor = Executors.newSingleThreadExecutor()

        try {
            val receiveFuture = executor.submit<SecureTcpFileTransfer.ReceiveResult> {
                SecureTcpFileTransfer.receiveResumable(
                    link = receiverLink,
                    destinationFor = { destination },
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
            val receiveResult = receiveFuture.get(20, TimeUnit.SECONDS).transfer

            assertTrue(sendResult.verified)
            assertTrue(receiveResult.verified)
            assertTrue(sendResult.encrypted)
            assertEquals(resumeOffset.toLong(), sendResult.resumedFromBytes)
            assertEquals(resumeOffset.toLong(), receiveResult.resumedFromBytes)
            assertEquals(
                payload.size.toLong() - resumeOffset.toLong(),
                sendResult.networkBytesTransferred,
            )
            assertEquals(
                payload.size.toLong() - resumeOffset.toLong(),
                receiveResult.networkBytesTransferred,
            )
            assertArrayEquals(payload, destination.readBytes())
        } finally {
            executor.shutdownNow()
            destination.delete()
        }
    }

    @Test
    fun interruptedTransferResumesAcrossFreshE2eSession() {
        val payload = ByteArray(6_500_000) { ((it * 29 + 7) % 251).toByte() }
        val header = headerFor(payload, "restart-resume.bin")
        val destination = File.createTempFile("secure-process-restart-", ".part")
        destination.delete()
        val firstPort = ServerSocket(0).use { it.localPort }
        val firstExecutor = Executors.newSingleThreadExecutor()
        val receiverCancellation = TransferCancellation()
        val (firstSenderLink, firstReceiverLink) = secureLinks()

        try {
            val firstReceive = firstExecutor.submit {
                runCatching {
                    SecureTcpFileTransfer.receiveResumable(
                        link = firstReceiverLink,
                        destinationFor = { destination },
                        port = firstPort,
                        cancellation = receiverCancellation,
                        onProgress = { received, _ ->
                            if (received >= 2_100_000L) receiverCancellation.cancel()
                        },
                    )
                }
            }

            Thread.sleep(100L)
            runCatching {
                SecureTcpFileTransfer.send(
                    host = "127.0.0.1",
                    link = firstSenderLink,
                    header = header,
                    inputFactory = { payload.inputStream() },
                    port = firstPort,
                )
            }
            firstReceive.get(20, TimeUnit.SECONDS)

            val persistedPrefix = destination.length()
            assertTrue("partial should survive interruption", persistedPrefix > 0L)
            assertTrue("partial must be incomplete", persistedPrefix < payload.size.toLong())

            // Simulate a process/app restart: entirely new QR/ECDH session and a fresh server.
            val (secondSenderLink, secondReceiverLink) = secureLinks()
            val secondPort = ServerSocket(0).use { it.localPort }
            val secondExecutor = Executors.newSingleThreadExecutor()
            try {
                val secondReceive = secondExecutor.submit<SecureTcpFileTransfer.ReceiveResult> {
                    SecureTcpFileTransfer.receiveResumable(
                        link = secondReceiverLink,
                        destinationFor = { destination },
                        port = secondPort,
                    )
                }

                Thread.sleep(100L)
                val secondSend = SecureTcpFileTransfer.send(
                    host = "127.0.0.1",
                    link = secondSenderLink,
                    header = header,
                    inputFactory = { payload.inputStream() },
                    port = secondPort,
                )
                val secondReceiveResult = secondReceive.get(20, TimeUnit.SECONDS).transfer

                assertTrue(secondSend.verified)
                assertTrue(secondReceiveResult.verified)
                assertEquals(persistedPrefix, secondSend.resumedFromBytes)
                assertEquals(persistedPrefix, secondReceiveResult.resumedFromBytes)
                assertEquals(
                    payload.size.toLong() - persistedPrefix,
                    secondSend.networkBytesTransferred,
                )
                assertArrayEquals(payload, destination.readBytes())
            } finally {
                secondExecutor.shutdownNow()
            }
        } finally {
            receiverCancellation.cancel()
            firstExecutor.shutdownNow()
            destination.delete()
        }
    }

    private fun headerFor(payload: ByteArray, name: String): TransferHeader {
        val sha = MessageDigest.getInstance("SHA-256")
            .digest(payload)
            .joinToString("") { "%02x".format(it) }
        return TransferHeader(
            fileName = name,
            mimeType = "application/octet-stream",
            sizeBytes = payload.size.toLong(),
            sha256 = sha,
        )
    }

    private fun secureLinks(): Pair<SecureSessionCrypto.SecureLink, SecureSessionCrypto.SecureLink> {
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
        return senderLink to receiverLink
    }
}
