package com.luisforlo.offlinetransfer.pairing.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import java.nio.ByteBuffer

class NfcPairingHostService : HostApduService() {
    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray {
        if (NfcPairingProtocol.isSelectAid(commandApdu)) {
            return if (NfcPairingStore.current() != null) {
                NfcPairingProtocol.SW_OK
            } else {
                NfcPairingProtocol.SW_NOT_FOUND
            }
        }

        val payload = NfcPairingStore.current()?.toByteArray(Charsets.UTF_8)
            ?: return NfcPairingProtocol.SW_NOT_FOUND

        if (NfcPairingProtocol.isLengthCommand(commandApdu)) {
            return NfcPairingProtocol.appendStatus(
                ByteBuffer.allocate(Int.SIZE_BYTES).putInt(payload.size).array(),
            )
        }

        val read = NfcPairingProtocol.parseReadCommand(commandApdu)
            ?: return NfcPairingProtocol.SW_WRONG_DATA
        val (offset, requested) = read
        if (offset > payload.size) return NfcPairingProtocol.SW_WRONG_DATA

        val end = minOf(
            payload.size,
            offset + requested.coerceAtMost(NfcPairingProtocol.MAX_CHUNK_BYTES),
        )
        return NfcPairingProtocol.appendStatus(payload.copyOfRange(offset, end))
    }

    override fun onDeactivated(reason: Int) = Unit
}
