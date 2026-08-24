package com.luisforlo.offlinetransfer.transfer

import com.luisforlo.offlinetransfer.protocol.TransferHeader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TcpFileTransferTest {
    @Test
    fun transfersFileAndReturnsReceiverHashAcknowledgement() {
        val source = File.createTempFile("offline-transfer-source-", ".bin")
        val destination = File.createTempFile("offline-transfer-destination-", ".bin")
        destination.delete()

        val payload = ByteArray(900_000) { index -> (index % 251).toByte() }
        source.writeBytes(payload)

        val hash = source.inputStream().use { FileHasher.sha256(it) }
        val header = TransferHeader(
            fileName = "payload.bin",
            mimeType = "application/octet-stream",
            sizeBytes = source.length(),
            sha256 = hash,
        )
        val port = ServerSocket(0).use { it.localPort }
        val executor = Executors.newSingleThreadExecutor()

        try {
            val receiver = executor.submit<TcpFileTransfer.Result> {
                TcpFileTransfer.receive(destination = destination, port = port)
            }

            val senderResult = TcpFileTransfer.send(
                host = "127.0.0.1",
                header = header,
                inputFactory = { source.inputStream() },
                port = port,
            )
            val receiverResult = receiver.get(10, TimeUnit.SECONDS)

            assertTrue(senderResult.verified)
            assertTrue(receiverResult.verified)
            assertEquals(payload.size.toLong(), senderResult.bytesTransferred)
            assertEquals(payload.size.toLong(), receiverResult.bytesTransferred)
            assertArrayEquals(payload, destination.readBytes())
        } finally {
            executor.shutdownNow()
            source.delete()
            destination.delete()
        }
    }
}
