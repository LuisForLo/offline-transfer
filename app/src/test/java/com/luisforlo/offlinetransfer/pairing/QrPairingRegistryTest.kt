package com.luisforlo.offlinetransfer.pairing

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrPairingRegistryTest {
    @After
    fun tearDown() {
        QrPairingRegistry.clearForTests()
    }

    @Test
    fun decodedPayloadRegistersNameForAddressFallback() {
        val original = QrPairingPayload(
            deviceAddress = "12:34:56:78:9A:BC",
            deviceName = "moto g82 5G_PJYp",
            nonce = "0123456789abcdef",
        )

        val decoded = QrPairingPayload.decode(original.encode())

        assertEquals(original, decoded)
        assertEquals(
            "moto g82 5G_PJYp",
            QrPairingRegistry.nameFor("12:34:56:78:9a:bc"),
        )
    }

    @Test
    fun forgetRemovesFallbackIdentity() {
        QrPairingRegistry.remember("AA:BB:CC:DD:EE:FF", "Phone")
        QrPairingRegistry.forget("aa:bb:cc:dd:ee:ff")

        assertNull(QrPairingRegistry.nameFor("AA:BB:CC:DD:EE:FF"))
    }
}
