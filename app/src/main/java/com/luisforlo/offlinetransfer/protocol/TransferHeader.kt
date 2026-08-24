package com.luisforlo.offlinetransfer.protocol

import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Minimal v1 header for the first end-to-end transfer prototype.
 */
data class TransferHeader(
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
) {
    fun writeTo(output: DataOutputStream) {
        output.writeInt(MAGIC)
        output.writeInt(VERSION)
        output.writeUTF(fileName)
        output.writeUTF(mimeType)
        output.writeLong(sizeBytes)
        output.writeUTF(sha256)
    }

    companion object {
        private const val MAGIC = 0x4F544631 // "OTF1"
        private const val VERSION = 1

        fun readFrom(input: DataInputStream): TransferHeader {
            require(input.readInt() == MAGIC) { "Transfer protocol magic mismatch" }
            require(input.readInt() == VERSION) { "Unsupported transfer protocol version" }
            return TransferHeader(
                fileName = input.readUTF(),
                mimeType = input.readUTF(),
                sizeBytes = input.readLong(),
                sha256 = input.readUTF(),
            )
        }
    }
}
