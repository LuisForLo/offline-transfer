package com.luisforlo.offlinetransfer.pairing.nfc

object NfcPairingStore {
    @Volatile
    private var payload: String? = null

    fun publish(value: String) {
        require(value.toByteArray(Charsets.UTF_8).size <= NfcPairingProtocol.MAX_PAYLOAD_BYTES) {
            "NFC pairing payload is too large"
        }
        payload = value
    }

    fun current(): String? = payload

    fun clear() {
        payload = null
    }
}
