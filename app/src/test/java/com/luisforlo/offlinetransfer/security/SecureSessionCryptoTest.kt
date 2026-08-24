package com.luisforlo.offlinetransfer.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class SecureSessionCryptoTest {
    @Test
    fun ecdhDerivesSameKeyAndVerificationCode() {
        val receiver = SecureSessionCrypto.createReceiverSession()
        val sender = SecureSessionCrypto.createSenderSession(
            receiver.sessionIdBase64,
            receiver.publicKeyBase64,
        )
        val receiverLink = SecureSessionCrypto.deriveReceiverLink(
            receiver,
            sender.senderPublicKeyBytes,
        )
        val senderLink = sender.asSecureLink()

        assertArrayEquals(senderLink.sessionKey, receiverLink.sessionKey)
        assertEquals(senderLink.verificationCode, receiverLink.verificationCode)
        assertEquals(6, senderLink.verificationCode.length)
    }

    @Test
    fun aesGcmRejectsTamperedChunk() {
        val receiver = SecureSessionCrypto.createReceiverSession()
        val sender = SecureSessionCrypto.createSenderSession(
            receiver.sessionIdBase64,
            receiver.publicKeyBase64,
        )
        val link = sender.asSecureLink()
        val noncePrefix = SecureSessionCrypto.newNoncePrefix()
        val plain = ByteArray(4096) { (it % 251).toByte() }

        val encrypted = SecureSessionCrypto.encryptChunk(
            sessionKey = link.sessionKey,
            sessionId = link.sessionId,
            noncePrefix = noncePrefix,
            counter = 7,
            plaintext = plain,
        )
        val decrypted = SecureSessionCrypto.decryptChunk(
            sessionKey = link.sessionKey,
            sessionId = link.sessionId,
            noncePrefix = noncePrefix,
            counter = 7,
            ciphertext = encrypted,
        )
        assertArrayEquals(plain, decrypted)

        encrypted[encrypted.lastIndex / 2] = (encrypted[encrypted.lastIndex / 2].toInt() xor 0x01).toByte()
        try {
            SecureSessionCrypto.decryptChunk(
                sessionKey = link.sessionKey,
                sessionId = link.sessionId,
                noncePrefix = noncePrefix,
                counter = 7,
                ciphertext = encrypted,
            )
            fail("Tampered AES-GCM ciphertext must be rejected")
        } catch (_: Throwable) {
            // Expected: AEAD authentication failure.
        }
    }
}
