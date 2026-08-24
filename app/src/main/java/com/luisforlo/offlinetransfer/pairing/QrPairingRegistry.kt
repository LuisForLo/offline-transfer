package com.luisforlo.offlinetransfer.pairing

import java.util.concurrent.ConcurrentHashMap

/**
 * Short-lived bridge between the QR decoder and Wi-Fi Direct discovery.
 *
 * Some Android vendors expose a different P2P MAC to the local device than the
 * address seen by remote peers. The QR still carries that address, but we keep
 * the decoded Wi-Fi Direct device name as a secondary identity so discovery can
 * safely fall back to the name when the MAC does not match.
 */
object QrPairingRegistry {
    private val namesByAddress = ConcurrentHashMap<String, String>()

    fun remember(deviceAddress: String, deviceName: String) {
        val address = deviceAddress.trim().lowercase()
        val name = deviceName.trim()
        if (address.isNotBlank() && name.isNotBlank()) {
            namesByAddress[address] = name
        }
    }

    fun nameFor(deviceAddress: String): String? =
        namesByAddress[deviceAddress.trim().lowercase()]

    fun forget(deviceAddress: String) {
        namesByAddress.remove(deviceAddress.trim().lowercase())
    }

    internal fun clearForTests() {
        namesByAddress.clear()
    }
}
