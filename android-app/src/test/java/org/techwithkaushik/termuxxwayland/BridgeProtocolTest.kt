package org.techwithkaushik.termuxxwayland

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BridgeProtocolTest {
    @Test
    fun roundTripsEmptyAndNonEmptyMessages() {
        val cases = listOf(
            BridgeMessage(BridgeMessageKind.PING, byteArrayOf()),
            BridgeMessage(BridgeMessageKind.HELLO, "session".toByteArray()),
            BridgeMessage(BridgeMessageKind.FRAME, ByteArray(4096) { (it % 251).toByte() })
        )

        cases.forEach { expected ->
            val encoded = ByteArrayOutputStream()
            BridgeProtocol.write(encoded, expected.kind, expected.payload)
            val actual = BridgeProtocol.read(ByteArrayInputStream(encoded.toByteArray()))
            assertEquals(expected.kind, actual.kind)
            assertArrayEquals(expected.payload, actual.payload)
        }
    }

    @Test
    fun acceptsOnlyExactly32ByteHexTokens() {
        assertArrayEquals(ByteArray(32) { 0xab.toByte() }, BridgeProtocol.parseToken("ab".repeat(32)))
        assertThrows(IllegalArgumentException::class.java) { BridgeProtocol.parseToken("abcd") }
        assertThrows(IllegalArgumentException::class.java) { BridgeProtocol.parseToken("zz".repeat(32)) }
    }

    @Test
    fun rejectsInvalidMagicAndVersion() {
        val valid = encodedHeader(kind = BridgeMessageKind.PING.wireValue)
        val badMagic = valid.copyOf().also { it[0] = 0 }
        assertThrows(IOException::class.java) { BridgeProtocol.read(ByteArrayInputStream(badMagic)) }

        val badVersion = valid.copyOf().also { it[4] = 2 }
        assertThrows(IOException::class.java) { BridgeProtocol.read(ByteArrayInputStream(badVersion)) }
    }

    @Test
    fun rejectsUnknownKindAndNonZeroReservedBits() {
        val unknownKind = encodedHeader(kind = 255)
        assertThrows(IOException::class.java) { BridgeProtocol.read(ByteArrayInputStream(unknownKind)) }

        val reserved = encodedHeader(kind = BridgeMessageKind.PING.wireValue).also { it[6] = 1 }
        assertThrows(IOException::class.java) { BridgeProtocol.read(ByteArrayInputStream(reserved)) }
    }

    @Test
    fun rejectsOversizedPayloadBeforeReadingPayload() {
        val header = encodedHeader(kind = BridgeMessageKind.FRAME.wireValue)
        header[8] = 0x01
        header[9] = 0x00
        header[10] = 0x00
        header[11] = 0x01
        assertThrows(IOException::class.java) { BridgeProtocol.read(ByteArrayInputStream(header)) }
    }

    private fun encodedHeader(kind: Int): ByteArray =
        ByteArrayOutputStream().also {
            BridgeProtocol.write(it, BridgeMessageKind.PING)
        }.toByteArray().also { bytes -> bytes[5] = kind.toByte() }
}
