package org.techwithkaushik.termuxxwayland

import java.io.IOException

/**
 * Decodes the bridge's bounded RGBA8888 frame payload into Android-independent pixels.
 * Wire metadata: big-endian u16 width, big-endian u16 height, format=1, then 3 reserved zero bytes.
 */
internal object RgbaFrameCodec {
    internal data class Frame(val width: Int, val height: Int, val pixels: IntArray)

    fun decode(payload: ByteArray): Frame {
        if (payload.size < 8) throw IOException("Truncated frame metadata")
        val width = ((payload[0].toInt() and 0xff) shl 8) or (payload[1].toInt() and 0xff)
        val height = ((payload[2].toInt() and 0xff) shl 8) or (payload[3].toInt() and 0xff)
        val format = payload[4].toInt() and 0xff
        if (width !in 1..640 || height !in 1..360 || format != RGBA8888 ||
            payload[5] != 0.toByte() || payload[6] != 0.toByte() || payload[7] != 0.toByte()
        ) throw IOException("Unsupported or invalid frame metadata")

        val expectedBytes = 8L + width.toLong() * height.toLong() * BYTES_PER_PIXEL
        if (expectedBytes != payload.size.toLong()) {
            throw IOException("Frame pixel length does not match its dimensions")
        }

        val pixels = IntArray(width * height)
        var offset = 8
        for (index in pixels.indices) {
            val red = payload[offset++].toInt() and 0xff
            val green = payload[offset++].toInt() and 0xff
            val blue = payload[offset++].toInt() and 0xff
            val alpha = payload[offset++].toInt() and 0xff
            pixels[index] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
        }
        return Frame(width, height, pixels)
    }

    private const val RGBA8888 = 1
    private const val BYTES_PER_PIXEL = 4L
}
