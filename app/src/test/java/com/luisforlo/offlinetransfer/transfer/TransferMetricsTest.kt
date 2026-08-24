package com.luisforlo.offlinetransfer.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TransferMetricsTest {
    @Test
    fun calculatesSpeedAndEta() {
        val metrics = TransferMetricsCalculator.calculate(
            bytesDone = 10_000_000L,
            bytesTotal = 20_000_000L,
            elapsedMillis = 2_000L,
        )

        assertEquals(5_000_000.0, metrics.bytesPerSecond, 0.01)
        assertEquals(2L, metrics.etaSeconds)
    }

    @Test
    fun cancellingReceiverWakesBlockingAccept() {
        val destination = File.createTempFile("offline-transfer-cancel-", ".bin")
        destination.delete()
        val port = ServerSocket(0).use { it.localPort }
        val cancellation = TransferCancellation()
        val executor = Executors.newSingleThreadExecutor()

        try {
            val receiver = executor.submit<Throwable?> {
                runCatching {
                    TcpFileTransfer.receive(
                        destination = destination,
                        port = port,
                        cancellation = cancellation,
                    )
                }.exceptionOrNull()
            }

            Thread.sleep(150L)
            cancellation.cancel()
            val error = receiver.get(3, TimeUnit.SECONDS)
            assertTrue(error is TransferCancelledException)
        } finally {
            cancellation.cancel()
            executor.shutdownNow()
            destination.delete()
        }
    }
}
