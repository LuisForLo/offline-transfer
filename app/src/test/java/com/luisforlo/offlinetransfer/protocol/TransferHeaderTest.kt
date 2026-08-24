package com.luisforlo.offlinetransfer.protocol

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class TransferHeaderTest {
    @Test
    fun roundTripPreservesHeader() {
        val expected = TransferHeader(
            fileName = "foto.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 8_593_771,
            sha256 = "0123456789abcdef",
        )
        val bytes = ByteArrayOutputStream().also { stream ->
            DataOutputStream(stream).use(expected::writeTo)
        }.toByteArray()

        val actual = DataInputStream(ByteArrayInputStream(bytes)).use(TransferHeader::readFrom)
        assertEquals(expected, actual)
    }
}
