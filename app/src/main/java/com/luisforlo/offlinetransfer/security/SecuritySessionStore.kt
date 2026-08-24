package com.luisforlo.offlinetransfer.security

import com.luisforlo.offlinetransfer.transfer.TransferCancellation

/**
 * Process-local ephemeral security state. No session key or private key is persisted.
 * A new receiver key pair is generated after reset/disconnect.
 */
object SecuritySessionStore {
    @Volatile
    private var receiverSession: SecureSessionCrypto.ReceiverSession? = null

    @Volatile
    private var senderSession: SecureSessionCrypto.SenderSession? = null

    @Volatile
    private var establishedLink: SecureSessionCrypto.SecureLink? = null

    @Synchronized
    fun prepareReceiver(): SecureSessionCrypto.ReceiverSession {
        senderSession = null
        establishedLink = null
        return receiverSession ?: SecureSessionCrypto.createReceiverSession().also {
            receiverSession = it
        }
    }

    @Synchronized
    fun prepareSender(
        sessionIdBase64: String,
        receiverPublicKeyBase64: String,
    ): SecureSessionCrypto.SenderSession {
        receiverSession = null
        establishedLink = null
        return SecureSessionCrypto.createSenderSession(
            sessionIdBase64 = sessionIdBase64,
            receiverPublicKeyBase64 = receiverPublicKeyBase64,
        ).also { senderSession = it }
    }

    fun hasReceiverSession(): Boolean = receiverSession != null
    fun hasSenderSession(): Boolean = senderSession != null
    fun expectsSecureSend(): Boolean = senderSession != null

    fun establishAsReceiver(cancellation: TransferCancellation? = null): SecureSessionCrypto.SecureLink {
        val receiver = requireNotNull(receiverSession) { "Secure receiver session is not prepared" }
        return SecureLinkHandshake.establishAsReceiver(receiver, cancellation).also {
            establishedLink = it
        }
    }

    fun establishAsSender(
        host: String,
        cancellation: TransferCancellation? = null,
    ): SecureSessionCrypto.SecureLink {
        val sender = requireNotNull(senderSession) { "Secure sender session is not prepared" }
        return SecureLinkHandshake.establishAsSender(host, sender, cancellation).also {
            establishedLink = it
        }
    }

    fun linkOrNull(): SecureSessionCrypto.SecureLink? = establishedLink

    fun linkForSend(): SecureSessionCrypto.SecureLink? {
        val link = establishedLink
        if (senderSession != null && link == null) {
            error("La sesión QR segura aún no termina su handshake")
        }
        return link
    }

    fun linkForReceive(waitMillis: Long = 7_500L): SecureSessionCrypto.SecureLink? {
        establishedLink?.let { return it }
        if (receiverSession == null) return null

        val deadline = System.nanoTime() + waitMillis * 1_000_000L
        while (System.nanoTime() < deadline) {
            establishedLink?.let { return it }
            Thread.sleep(50L)
        }
        return establishedLink
    }

    fun verificationCodeOrNull(): String? = establishedLink?.verificationCode

    @Synchronized
    fun reset() {
        establishedLink?.sessionKey?.fill(0)
        senderSession?.sessionKey?.fill(0)
        establishedLink = null
        senderSession = null
        receiverSession = null
    }
}
