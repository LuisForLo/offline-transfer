package com.luisforlo.offlinetransfer.pairing.nfc

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class NfcPairingReader(
    private val activity: Activity,
) {
    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)
    private val active = AtomicBoolean(false)

    val isSupported: Boolean
        get() = adapter != null

    val isEnabled: Boolean
        get() = adapter?.isEnabled == true

    fun enable(
        onTagDetected: () -> Unit = {},
        onPayload: (String) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        val nfc = adapter ?: error("Este teléfono no tiene NFC")
        check(nfc.isEnabled) { "NFC está desactivado" }

        active.set(true)
        nfc.enableReaderMode(
            activity,
            { tag -> readTag(tag, onTagDetected, onPayload, onError) },
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
                NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
            null,
        )
    }

    fun disable() {
        active.set(false)
        runCatching { adapter?.disableReaderMode(activity) }
    }

    private fun readTag(
        tag: Tag,
        onTagDetected: () -> Unit,
        onPayload: (String) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        if (!active.compareAndSet(true, false)) return
        activity.runOnUiThread(onTagDetected)
        runCatching { adapter?.disableReaderMode(activity) }

        try {
            val isoDep = IsoDep.get(tag) ?: error("El otro dispositivo no expuso ISO-DEP")
            isoDep.use {
                it.connect()
                it.timeout = 4_000

                requireSuccess(it.transceive(NfcPairingProtocol.selectAidCommand()))
                val lengthResponse = requireSuccess(it.transceive(NfcPairingProtocol.lengthCommand()))
                val lengthData = NfcPairingProtocol.responseData(lengthResponse)
                require(lengthData.size == Int.SIZE_BYTES) { "Longitud NFC inválida" }

                val total = ByteBuffer.wrap(lengthData).int
                require(total in 1..NfcPairingProtocol.MAX_PAYLOAD_BYTES) {
                    "Payload NFC fuera de rango: $total"
                }

                val output = ByteArrayOutputStream(total)
                var offset = 0
                while (offset < total) {
                    val response = requireSuccess(
                        it.transceive(
                            NfcPairingProtocol.readCommand(
                                offset = offset,
                                requested = minOf(
                                    NfcPairingProtocol.MAX_CHUNK_BYTES,
                                    total - offset,
                                ),
                            ),
                        ),
                    )
                    val chunk = NfcPairingProtocol.responseData(response)
                    require(chunk.isNotEmpty()) { "El enlace NFC devolvió un bloque vacío" }
                    output.write(chunk)
                    offset += chunk.size
                }

                val payload = output.toByteArray().toString(Charsets.UTF_8)
                activity.runOnUiThread {
                    disable()
                    onPayload(payload)
                }
            }
        } catch (error: Throwable) {
            activity.runOnUiThread {
                disable()
                onError(error)
            }
        }
    }

    private fun requireSuccess(response: ByteArray): ByteArray {
        require(NfcPairingProtocol.hasSuccessStatus(response)) {
            "El otro teléfono rechazó el emparejamiento NFC"
        }
        return response
    }
}
