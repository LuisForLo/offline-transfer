package com.luisforlo.offlinetransfer.security

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object SecureSessionCrypto {
    private const val CURVE = "secp256r1"
    private const val KEY_BYTES = 32
    private const val NONCE_PREFIX_BYTES = 8
    private const val GCM_TAG_BITS = 128
    private val secureRandom = SecureRandom()

    data class ReceiverSession(
        val sessionId: ByteArray,
        val keyPair: KeyPair,
    ) {
        val sessionIdBase64: String
            get() = Base64.getUrlEncoder().withoutPadding().encodeToString(sessionId)
        val publicKeyBase64: String
            get() = Base64.getUrlEncoder().withoutPadding().encodeToString(keyPair.public.encoded)
    }

    data class SenderSession(
        val sessionId: ByteArray,
        val keyPair: KeyPair,
        val receiverPublicKey: PublicKey,
        val sessionKey: ByteArray,
    ) {
        val senderPublicKeyBytes: ByteArray
            get() = keyPair.public.encoded
        val verificationCode: String
            get() = verificationCode(sessionKey, sessionId)
    }

    fun createReceiverSession(): ReceiverSession = ReceiverSession(
        sessionId = randomBytes(16),
        keyPair = generateKeyPair(),
    )

    fun createSenderSession(
        sessionIdBase64: String,
        receiverPublicKeyBase64: String,
    ): SenderSession {
        val sessionId = Base64.getUrlDecoder().decode(sessionIdBase64)
        require(sessionId.size == 16) { "Invalid secure session id" }
        val receiverPublicKey = decodePublicKey(
            Base64.getUrlDecoder().decode(receiverPublicKeyBase64),
        )
        val keyPair = generateKeyPair()
        val key = deriveSessionKey(
            ownPrivate = keyPair.private,
            peerPublic = receiverPublicKey,
            sessionId = sessionId,
        )
        return SenderSession(sessionId, keyPair, receiverPublicKey, key)
    }

    fun deriveReceiverKey(
        receiverSession: ReceiverSession,
        senderPublicKeyBytes: ByteArray,
    ): ByteArray {
        val senderPublicKey = decodePublicKey(senderPublicKeyBytes)
        return deriveSessionKey(
            ownPrivate = receiverSession.keyPair.private,
            peerPublic = senderPublicKey,
            sessionId = receiverSession.sessionId,
        )
    }

    fun newNoncePrefix(): ByteArray = randomBytes(NONCE_PREFIX_BYTES)

    fun encryptChunk(
        sessionKey: ByteArray,
        sessionId: ByteArray,
        noncePrefix: ByteArray,
        counter: Int,
        plaintext: ByteArray,
        length: Int = plaintext.size,
    ): ByteArray {
        require(length in 0..plaintext.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(sessionKey, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, nonceFor(noncePrefix, counter)),
        )
        cipher.updateAAD(aad(sessionId, counter))
        return cipher.doFinal(plaintext, 0, length)
    }

    fun decryptChunk(
        sessionKey: ByteArray,
        sessionId: ByteArray,
        noncePrefix: ByteArray,
        counter: Int,
        ciphertext: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(sessionKey, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, nonceFor(noncePrefix, counter)),
        )
        cipher.updateAAD(aad(sessionId, counter))
        return cipher.doFinal(ciphertext)
    }

    fun acknowledgementMac(
        sessionKey: ByteArray,
        sessionId: ByteArray,
        sha256: String,
        verified: Boolean,
    ): ByteArray = hmacSha256(
        sessionKey,
        "OTF-ACK|${Base64.getUrlEncoder().withoutPadding().encodeToString(sessionId)}|$sha256|$verified"
            .toByteArray(Charsets.UTF_8),
    )

    fun verificationCode(sessionKey: ByteArray, sessionId: ByteArray): String {
        val digest = hmacSha256(
            sessionKey,
            "OTF-VERIFY|${Base64.getUrlEncoder().withoutPadding().encodeToString(sessionId)}"
                .toByteArray(Charsets.UTF_8),
        )
        val value = ((digest[0].toInt() and 0xff) shl 16) or
            ((digest[1].toInt() and 0xff) shl 8) or
            (digest[2].toInt() and 0xff)
        return "%06d".format(value % 1_000_000)
    }

    fun constantTimeEquals(left: ByteArray, right: ByteArray): Boolean =
        MessageDigest.isEqual(left, right)

    private fun generateKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec(CURVE), secureRandom)
        return generator.generateKeyPair()
    }

    private fun decodePublicKey(encoded: ByteArray): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))

    private fun deriveSessionKey(
        ownPrivate: java.security.PrivateKey,
        peerPublic: PublicKey,
        sessionId: ByteArray,
    ): ByteArray {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(ownPrivate)
        agreement.doPhase(peerPublic, true)
        val sharedSecret = agreement.generateSecret()

        val salt = MessageDigest.getInstance("SHA-256").digest(
            "OfflineTransfer-E2E-v1".toByteArray(Charsets.UTF_8) + sessionId,
        )
        val prk = hmacSha256(salt, sharedSecret)
        val info = "OfflineTransfer session key".toByteArray(Charsets.UTF_8)
        val okm = hmacSha256(prk, info + byteArrayOf(1))
        sharedSecret.fill(0)
        return okm.copyOf(KEY_BYTES)
    }

    private fun nonceFor(prefix: ByteArray, counter: Int): ByteArray {
        require(prefix.size == NONCE_PREFIX_BYTES)
        require(counter >= 0)
        return prefix + byteArrayOf(
            (counter ushr 24).toByte(),
            (counter ushr 16).toByte(),
            (counter ushr 8).toByte(),
            counter.toByte(),
        )
    }

    private fun aad(sessionId: ByteArray, counter: Int): ByteArray =
        "OTFSEC1".toByteArray(Charsets.US_ASCII) + sessionId + byteArrayOf(
            (counter ushr 24).toByte(),
            (counter ushr 16).toByte(),
            (counter ushr 8).toByte(),
            counter.toByte(),
        )

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also(secureRandom::nextBytes)
}
