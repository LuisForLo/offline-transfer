package com.luisforlo.offlinetransfer.pairing.nfc

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class NfcPairingReader(
    private val activity: Activity,
) {
    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)
    private val active = AtomicBoolean(false)
    private val reading = AtomicBoolean(false)

    val isSupported: Boolean
        get() = adapter != null

    val isEnabled: Boolean
        get() = adapter?.isEnabled == true

    fun enable(
        onTagDetected: () -> Unit = {},
        onStage: (String) -> Unit = {},
        onPayload: (String) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        val nfc = adapter ?: error("Este teléfono no tiene NFC")
        check(nfc.isEnabled) { "NFC está desactivado" }

        active.set(true)
        reading.set(false)

        val options = Bundle().apply {
            putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 750)
        }

        nfc.enableReaderMode(
            activity,
            { tag -> readTag(tag, onTagDetected, onStage, onPayload, onError) },
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
                NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
            options,
        )
    }

    fun disable() {
        active.set(false)
        reading.set(false)
        runCatching { adapter?.disableReaderMode(activity) }
    }

    private fun readTag(
        tag: Tag,
        onTagDetected: () -> Unit,
        onStage: (String) -> Unit,
        onPayload: (String) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        if (!active.get()) return
        if (!reading.compareAndSet(false, true)) return

        activity.runOnUiThread(onTagDetected)

        var stage = "detección"
        try {
            stage = "ISO-DEP"
            activity.runOnUiThread { onStage("NFC detectado · abriendo canal ISO-DEP…") }

            val isoDep = IsoDep.get(tag) ?: error("El otro dispositivo no expuso ISO-DEP")
            isoDep.use {
                stage = "conexión ISO-DEP"
                it.timeout = 5_000
                it.connect()
                check(it.isConnected) { "ISO-DEP no quedó conectado" }

                stage = "SELECT AID"
                activity.runOnUiThread { onStage("NFC conectado · seleccionando Offline Transfer…") }
                requireSuccess(it.transceive(NfcPairingProtocol.selectAidCommand()), stage)

                stage = "longitud del payload"
                activity.runOnUiThread { onStage("AID confirmado · leyendo sesión segura…") }
                val lengthResponse = requireSuccess(
                    it.transceive(NfcPairingProtocol.lengthCommand()),
                    stage,
                )
                val lengthData = NfcPairingProtocol.responseData(lengthResponse)
                require(lengthData.size == Int.SIZE_BYTES) { "Longitud NFC inválida" }

                val total = ByteBuffer.wrap(lengthData).int
                require(total in 1..NfcPairingProtocol.MAX_PAYLOAD_BYTES) {
                    "Payload NFC fuera de rango: $total"
                }

                val output = ByteArrayOutputStream(total)
                var offset = 0
                while (offset < total) {
                    stage = "lectura $offset/$total B"
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
                        stage,
                    )
                    val chunk = NfcPairingProtocol.responseData(response)
                    require(chunk.isNotEmpty()) { "El enlace NFC devolvió un bloque vacío" }
                    output.write(chunk)
                    offset += chunk.size
                    val progress = offset
                    activity.runOnUiThread {
                        onStage("NFC leyendo sesión · $progress/$total B")
                    }
                }

                stage = "decodificación"
                val payload = output.toByteArray().toString(Charsets.UTF_8)
                activity.runOnUiThread {
                    disable()
                    onStage("NFC completo · iniciando Wi-Fi Direct…")
                    onPayload(payload)
                }
            }
        } catch (error: Throwable) {
            activity.runOnUiThread {
                disable()
                val detail = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
                onError(IllegalStateException("Falló NFC en $stage: $detail", error))
            }
        } finally {
            reading.set(false)
        }
    }

    private fun requireSuccess(response: ByteArray, stage: String): ByteArray {
        require(NfcPairingProtocol.hasSuccessStatus(response)) {
            val status = if (response.size >= 2) {
                "%02X%02X".format(
                    response[response.lastIndex - 1].toInt() and 0xFF,
                    response[response.lastIndex].toInt() and 0xFF,
                )
            } else {
                "sin estado"
            }
            "Offline Transfer rechazó $stage · SW=$status"
        }
        return response
    }
}
