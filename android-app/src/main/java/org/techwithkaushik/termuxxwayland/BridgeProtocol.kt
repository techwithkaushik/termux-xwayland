package org.techwithkaushik.termuxxwayland

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException

internal enum class BridgeMessageKind(val wireValue: Int) {
    HELLO(1), WELCOME(2), FRAME(3), INPUT(4), RESIZE(5),
    CLIPBOARD(6), PING(7), PONG(8), CLOSE(9);

    companion object {
        fun fromWire(value: Int): BridgeMessageKind =
            values().firstOrNull { it.wireValue == value }
                ?: throw IOException("Unknown bridge message kind")
    }
}

internal data class BridgeMessage(
    val kind: BridgeMessageKind,
    val payload: ByteArray
)

internal object BridgeProtocol {
    private val magic = byteArrayOf('T'.code.toByte(), 'X'.code.toByte(), 'W'.code.toByte(), 'B'.code.toByte())
    private const val VERSION = 1
    private const val HEADER_SIZE = 12
    private const val MAX_PAYLOAD_SIZE = 32 * 1024 * 1024

    fun parseToken(hex: String): ByteArray {
        if (hex.length != 64 || hex.any { it.digitToIntOrNull(16) == null }) {
            throw IllegalArgumentException("Session token must be 64 hexadecimal characters")
        }
        return ByteArray(32) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    fun write(output: OutputStream, kind: BridgeMessageKind, payload: ByteArray = byteArrayOf()) {
        require(payload.size <= MAX_PAYLOAD_SIZE) { "Bridge payload too large" }
        synchronized(output) {
            val data = DataOutputStream(output)
            data.write(magic)
            data.writeByte(VERSION)
            data.writeByte(kind.wireValue)
            data.writeShort(0)
            data.writeInt(payload.size)
            data.write(payload)
            data.flush()
        }
    }

    fun read(input: InputStream): BridgeMessage {
        val data = DataInputStream(input)
        val header = ByteArray(HEADER_SIZE)
        data.readFully(header)
        if (!header.copyOfRange(0, 4).contentEquals(magic)) {
            throw IOException("Invalid bridge magic")
        }
        if ((header[4].toInt() and 0xff) != VERSION) {
            throw IOException("Unsupported bridge version")
        }
        if (header[6].toInt() != 0 || header[7].toInt() != 0) {
            throw IOException("Reserved bridge header bits must be zero")
        }
        val kind = BridgeMessageKind.fromWire(header[5].toInt() and 0xff)
        val length = ((header[8].toLong() and 0xff) shl 24) or
            ((header[9].toLong() and 0xff) shl 16) or
            ((header[10].toLong() and 0xff) shl 8) or
            (header[11].toLong() and 0xff)
        if (length > MAX_PAYLOAD_SIZE) throw IOException("Bridge payload exceeds limit")
        val payload = ByteArray(length.toInt())
        data.readFully(payload)
        return BridgeMessage(kind, payload)
    }
}
