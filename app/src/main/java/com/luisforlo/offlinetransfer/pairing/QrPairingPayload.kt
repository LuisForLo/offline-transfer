package com.luisforlo.offlinetransfer.pairing

import java.security.SecureRandom
import java.util.Base64

data class QrPairingPayload(
    val deviceAddress: String,
    val deviceName: String,
    val nonce: String,
) {
    fun encode(): String {
        val encodedName = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(deviceName.toByteArray(Charsets.UTF_8))
        return listOf(PREFIX, deviceAddress, encodedName, nonce).joinToString("|")
    }

    companion object {
        private const val PREFIX = "OTFQR1"
        private val secureRandom = SecureRandom()

        fun create(deviceAddress: String, deviceName: String): QrPairingPayload {
            val nonceBytes = ByteArray(8).also(secureRandom::nextBytes)
            val nonce = nonceBytes.joinToString("") { "%02x".format(it) }
            return QrPairingPayload(
                deviceAddress = deviceAddress.trim(),
                deviceName = deviceName.ifBlank { "Android" },
                nonce = nonce,
            )
        }

        fun decode(raw: String): QrPairingPayload? {
            val parts = raw.trim().split('|')
            if (parts.size != 4 || parts[0] != PREFIX) return null
            val address = parts[1].trim()
            if (address.isBlank()) return null

            val name = runCatching {
                String(Base64.getUrlDecoder().decode(parts[2]), Charsets.UTF_8)
            }.getOrNull() ?: return null

            val nonce = parts[3].trim()
            if (nonce.length !in 8..64) return null

            return QrPairingPayload(address, name.ifBlank { "Android" }, nonce)
        }
    }
}
