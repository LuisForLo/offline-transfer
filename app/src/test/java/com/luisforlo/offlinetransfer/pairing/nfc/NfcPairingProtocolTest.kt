package com.luisforlo.offlinetransfer.pairing.nfc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NfcPairingProtocolTest {
    @Test
    fun selectCommandTargetsOfflineTransferAid() {
        val command = NfcPairingProtocol.selectAidCommand()
        assertTrue(NfcPairingProtocol.isSelectAid(command))
    }

    @Test
    fun readCommandRoundTripsOffsetAndLength() {
        val command = NfcPairingProtocol.readCommand(offset = 0x1234, requested = 200)
        val parsed = NfcPairingProtocol.parseReadCommand(command)
        assertEquals(0x1234, parsed?.first)
        assertEquals(200, parsed?.second)
    }

    @Test
    fun responseStatusIsSeparatedFromPayload() {
        val payload = "OTFQR2|demo".toByteArray()
        val response = NfcPairingProtocol.appendStatus(payload)

        assertTrue(NfcPairingProtocol.hasSuccessStatus(response))
        assertArrayEquals(payload, NfcPairingProtocol.responseData(response))
    }
}
