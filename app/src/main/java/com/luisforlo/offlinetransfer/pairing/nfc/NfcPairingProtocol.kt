package com.luisforlo.offlinetransfer.pairing.nfc

object NfcPairingProtocol {
    const val AID_HEX = "F04F5446310001"

    private const val CLA_ISO = 0x00
    private const val INS_SELECT = 0xA4
    private const val P1_SELECT_BY_NAME = 0x04

    private const val CLA_OTF = 0x80
    private const val INS_LENGTH = 0xCA
    private const val INS_READ = 0xCB

    const val MAX_CHUNK_BYTES = 220
    const val MAX_PAYLOAD_BYTES = 16 * 1024

    val SW_OK = byteArrayOf(0x90.toByte(), 0x00)
    val SW_NOT_FOUND = byteArrayOf(0x6A, 0x82.toByte())
    val SW_WRONG_DATA = byteArrayOf(0x6A, 0x80.toByte())
    val SW_WRONG_LENGTH = byteArrayOf(0x67, 0x00)

    private val aidBytes = hexToBytes(AID_HEX)

    fun selectAidCommand(): ByteArray =
        byteArrayOf(
            CLA_ISO.toByte(),
            INS_SELECT.toByte(),
            P1_SELECT_BY_NAME.toByte(),
            0x00,
            aidBytes.size.toByte(),
        ) + aidBytes + byteArrayOf(0x00)

    fun lengthCommand(): ByteArray =
        byteArrayOf(CLA_OTF.toByte(), INS_LENGTH.toByte(), 0x00, 0x00, 0x04)

    fun readCommand(offset: Int, requested: Int = MAX_CHUNK_BYTES): ByteArray {
        require(offset in 0..0xFFFF)
        require(requested in 1..255)
        return byteArrayOf(
            CLA_OTF.toByte(),
            INS_READ.toByte(),
            ((offset ushr 8) and 0xFF).toByte(),
            (offset and 0xFF).toByte(),
            requested.toByte(),
        )
    }

    fun isSelectAid(command: ByteArray): Boolean {
        if (command.size < 5) return false
        if (command[0].toInt() and 0xFF != CLA_ISO) return false
        if (command[1].toInt() and 0xFF != INS_SELECT) return false
        if (command[2].toInt() and 0xFF != P1_SELECT_BY_NAME) return false
        val lc = command[4].toInt() and 0xFF
        if (lc != aidBytes.size || command.size < 5 + lc) return false
        return command.copyOfRange(5, 5 + lc).contentEquals(aidBytes)
    }

    fun isLengthCommand(command: ByteArray): Boolean =
        command.size >= 4 &&
            command[0].toInt() and 0xFF == CLA_OTF &&
            command[1].toInt() and 0xFF == INS_LENGTH

    fun parseReadCommand(command: ByteArray): Pair<Int, Int>? {
        if (command.size < 5) return null
        if (command[0].toInt() and 0xFF != CLA_OTF) return null
        if (command[1].toInt() and 0xFF != INS_READ) return null
        val offset =
            ((command[2].toInt() and 0xFF) shl 8) or
                (command[3].toInt() and 0xFF)
        val requested = (command[4].toInt() and 0xFF).let { if (it == 0) 256 else it }
        return offset to requested
    }

    fun appendStatus(data: ByteArray, status: ByteArray = SW_OK): ByteArray = data + status

    fun hasSuccessStatus(response: ByteArray): Boolean =
        response.size >= 2 &&
            response[response.lastIndex - 1] == SW_OK[0] &&
            response[response.lastIndex] == SW_OK[1]

    fun responseData(response: ByteArray): ByteArray {
        require(hasSuccessStatus(response)) { "NFC response status is not success" }
        return response.copyOfRange(0, response.size - 2)
    }

    private fun hexToBytes(value: String): ByteArray {
        require(value.length % 2 == 0)
        return ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
