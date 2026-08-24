package com.luisforlo.offlinetransfer.transfer

import kotlin.math.ceil

data class TransferMetrics(
    val bytesPerSecond: Double,
    val etaSeconds: Long?,
)

object TransferMetricsCalculator {
    fun calculate(
        bytesDone: Long,
        bytesTotal: Long,
        elapsedMillis: Long,
    ): TransferMetrics {
        if (bytesDone <= 0L || elapsedMillis <= 0L) {
            return TransferMetrics(bytesPerSecond = 0.0, etaSeconds = null)
        }

        val bytesPerSecond = bytesDone.toDouble() * 1_000.0 / elapsedMillis.toDouble()
        val remaining = (bytesTotal - bytesDone).coerceAtLeast(0L)
        val eta = if (bytesPerSecond > 0.0 && remaining > 0L) {
            ceil(remaining / bytesPerSecond).toLong()
        } else if (remaining == 0L) {
            0L
        } else {
            null
        }

        return TransferMetrics(bytesPerSecond, eta)
    }
}
