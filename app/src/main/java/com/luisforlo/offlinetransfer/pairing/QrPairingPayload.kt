package com.luisforlo.offlinetransfer.pairing

import com.luisforlo.offlinetransfer.security.SecuritySessionStore
import java.security.SecureRandom
import java.util.Base64

data class QrPairingPayload(
    val deviceAddress: String,
    val deviceName: String,
    val nonce: String,
    val secureSessionIdBase64: String? = null,
    val receiverPublicKeyBase64: String? = null,
) {
    val isSecure: Boolean
        get() = !secureSessionIdBase64.isNullOrBlank() && !receiverPublicKeyBase64.isNullOrBlank()

    fun encode(): String {
        val encodedName = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(deviceName.toByteArray(Charsets.UTF_8))
        return if (isSecure) {
            listOf(
                SECURE_PREFIX,
                deviceAddress,
                encodedName,
                secureSessionIdBase64,
                receiverPublicKeyBase64,
                nonce,
            ).joinToString("|")
        } else {
            listOf(LEGACY_PREFIX, deviceAddress, encodedName, nonce).joinToString("|")
        }
    }

    companion object {
        private const val LEGACY_PREFIX = "OTFQR1"
        private const val SECURE_PREFIX = "OTFQR2"
        private val secureRandom = SecureRandom()

        fun create(deviceAddress: String, deviceName: String): QrPairingPayload {
            val nonceBytes = ByteArray(8).also(secureRandom::nextBytes)
            val nonce = nonceBytes.joinToString("") { "%02x".format(it) }
            val receiverSession = SecuritySessionStore.prepareReceiver()
            return QrPairingPayload(
                deviceAddress = deviceAddress.trim(),
                deviceName = deviceName.ifBlank { "Android" },
                nonce = nonce,
                secureSessionIdBase64 = receiverSession.sessionIdBase64,
                receiverPublicKeyBase64 = receiverSession.publicKeyBase64,
            )
        }

        fun decode(raw: String): QrPairingPayload? {
            val parts = raw.trim().split('|')
            return when {
                parts.size == 6 && parts[0] == SECURE_PREFIX -> decodeSecure(parts)
                parts.size == 4 && parts[0] == LEGACY_PREFIX -> decodeLegacy(parts)
                else -> null
            }
        }

        private fun decodeSecure(parts: List<String>): QrPairingPayload? {
            val address = parts[1].trim()
            if (address.isBlank()) return null
            val name = decodeName(parts[2]) ?: return null
            val sessionId = parts[3].trim()
            val publicKey = parts[4].trim()
            val nonce = parts[5].trim()
            if (sessionId.isBlank() || publicKey.isBlank() || nonce.length !in 8..64) return null

            val payload = QrPairingPayload(
                deviceAddress = address,
                deviceName = name.ifBlank { "Android" },
                nonce = nonce,
                secureSessionIdBase64 = sessionId,
                receiverPublicKeyBase64 = publicKey,
            )

            runCatching {
                SecuritySessionStore.prepareSender(
                    sessionIdBase64 = sessionId,
                    receiverPublicKeyBase64 = publicKey,
                )
            }.getOrElse { return null }

            QrPairingRegistry.remember(payload.deviceAddress, payload.deviceName)
            return payload
        }

        private fun decodeLegacy(parts: List<String>): QrPairingPayload? {
            val address = parts[1].trim()
            if (address.isBlank()) return null
            val name = decodeName(parts[2]) ?: return null
            val nonce = parts[3].trim()
            if (nonce.length !in 8..64) return null

            val payload = QrPairingPayload(
                deviceAddress = address,
                deviceName = name.ifBlank { "Android" },
                nonce = nonce,
            )
            QrPairingRegistry.remember(payload.deviceAddress, payload.deviceName)
            return payload
        }

        private fun decodeName(encoded: String): String? = runCatching {
            String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8)
        }.getOrNull()
    }
}
