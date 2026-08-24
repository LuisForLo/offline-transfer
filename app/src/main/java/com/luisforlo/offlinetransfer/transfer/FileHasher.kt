package com.luisforlo.offlinetransfer.transfer

import java.io.InputStream
import java.security.MessageDigest

object FileHasher {
    fun sha256(
        input: InputStream,
        cancellation: TransferCancellation? = null,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            cancellation?.throwIfCancelled()
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
        cancellation?.throwIfCancelled()
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
