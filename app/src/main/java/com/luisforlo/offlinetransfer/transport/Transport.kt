package com.luisforlo.offlinetransfer.transport

/**
 * Boundary between the transfer engine and the underlying connection technology.
 *
 * Future implementations can use Wi-Fi Direct, local-only hotspot, LAN or an
 * optical QR stream without changing the higher-level file protocol.
 */
interface Transport {
    val name: String
    suspend fun start()
    suspend fun stop()
}
