package com.luisforlo.offlinetransfer.pairing

import com.luisforlo.offlinetransfer.security.SecureSessionCrypto
import java.security.SecureRandom
import java.util.Base64

data class SecureQrPairingPayload(
    val deviceAddress: String,
    val deviceName: String,
    val sessionIdBase64: String,
    val receiverPublicKeyBase64: String,
    val nonce: String,
) {
    fun encode(): String {
        val encodedName = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(deviceName.toByteArray(Charsets.UTF_8))
        return listOf(
            PREFIX,
            deviceAddress,
            encodedName,
            sessionIdBase64,
            receiverPublicKeyBase64,
            nonce,
        ).joinToString("|")
    }

    companion object {
        private const val PREFIX = "OTFQR2"
        private val secureRandom = SecureRandom()

        fun create(
            deviceAddress: String,
            deviceName: String,
            receiverSession: SecureSessionCrypto.ReceiverSession,
        ): SecureQrPairingPayload {
            val nonceBytes = ByteArray(8).also(secureRandom::nextBytes)
            val nonce = nonceBytes.joinToString("") { "%02x".format(it) }
            return SecureQrPairingPayload(
                deviceAddress = deviceAddress.trim(),
                deviceName = deviceName.ifBlank { "Android" },
                sessionIdBase64 = receiverSession.sessionIdBase64,
                receiverPublicKeyBase64 = receiverSession.publicKeyBase64,
                nonce = nonce,
            )
        }

        fun decode(raw: String): SecureQrPairingPayload? {
            val parts = raw.trim().split('|')
            if (parts.size != 6 || parts[0] != PREFIX) return null

            val address = parts[1].trim()
            if (address.isBlank()) return null

            val name = runCatching {
                String(Base64.getUrlDecoder().decode(parts[2]), Charsets.UTF_8)
            }.getOrNull() ?: return null

            val sessionId = parts[3].trim()
            val publicKey = parts[4].trim()
            val nonce = parts[5].trim()
            if (sessionId.isBlank() || publicKey.isBlank() || nonce.length !in 8..64) return null

            val payload = SecureQrPairingPayload(
                deviceAddress = address,
                deviceName = name.ifBlank { "Android" },
                sessionIdBase64 = sessionId,
                receiverPublicKeyBase64 = publicKey,
                nonce = nonce,
            )
            QrPairingRegistry.remember(payload.deviceAddress, payload.deviceName)
            return payload
        }
    }
}
