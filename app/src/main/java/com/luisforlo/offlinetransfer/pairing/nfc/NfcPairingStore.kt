package com.luisforlo.offlinetransfer.pairing.nfc

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object NfcPairingStore {
    @Volatile
    private var payload: String? = null

    private val _status = MutableStateFlow("NFC HCE inactivo")
    val status: StateFlow<String> = _status.asStateFlow()

    fun publish(value: String) {
        require(value.toByteArray(Charsets.UTF_8).size <= NfcPairingProtocol.MAX_PAYLOAD_BYTES) {
            "NFC pairing payload is too large"
        }
        payload = value
        _status.value = "NFC HCE preparado · esperando campo del emisor"
    }

    fun current(): String? = payload

    fun markAidSelected() {
        _status.value = "NFC detectado · AID de Offline Transfer seleccionado"
    }

    fun markPayloadRead(bytes: Int, total: Int) {
        _status.value = if (bytes >= total) {
            "NFC leído · sesión segura entregada al emisor"
        } else {
            "NFC activo · enviando sesión ${bytes.coerceAtLeast(0)}/${total.coerceAtLeast(0)} B"
        }
    }

    fun markDeactivated() {
        if (payload != null) {
            _status.value = "NFC HCE preparado · esperando otro toque"
        } else {
            _status.value = "NFC HCE inactivo"
        }
    }

    fun clear() {
        payload = null
        _status.value = "NFC HCE inactivo"
    }
}
