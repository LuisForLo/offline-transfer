package com.luisforlo.offlinetransfer.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrPairingPayloadTest {
    @Test
    fun roundTripsPairingPayload() {
        val original = QrPairingPayload(
            deviceAddress = "12:34:56:78:9A:BC",
            deviceName = "Teléfono receptor",
            nonce = "0123456789abcdef",
        )

        val decoded = QrPairingPayload.decode(original.encode())

        assertEquals(original, decoded)
    }

    @Test
    fun rejectsForeignQr() {
        assertNull(QrPairingPayload.decode("https://example.com"))
    }
}
