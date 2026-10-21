package org.techwithkaushik.termuxxwayland

import java.io.IOException

/**
 * Decodes the bridge's bounded RGBA8888 frame payload into Android-independent pixels.
 * Wire metadata: big-endian u16 width, big-endian u16 height, format=1, then 3 reserved zero bytes.
 */
internal object RgbaFrameCodec {
    internal data class Frame(val width: Int, val height: Int, val pixels: ByteArray, val pixelOffset: Int = 0)

    fun decode(payload: ByteArray): Frame {
        if (payload.size < 8) throw IOException("Truncated frame metadata")
        val width = ((payload[0].toInt() and 0xff) shl 8) or (payload[1].toInt() and 0xff)
        val height = ((payload[2].toInt() and 0xff) shl 8) or (payload[3].toInt() and 0xff)
        val format = payload[4].toInt() and 0xff
        if (width !in 1..4096 || height !in 1..4096 || format != RGBA8888 ||
            payload[5] != 0.toByte() || payload[6] != 0.toByte() || payload[7] != 0.toByte()
        ) throw IOException("Unsupported or invalid frame metadata")

        val expectedBytes = 8L + width.toLong() * height.toLong() * BYTES_PER_PIXEL
        if (expectedBytes != payload.size.toLong()) {
            throw IOException("Frame pixel length does not match its dimensions")
        }

        // Retain the received packet and pass the pixel offset to JNI. This avoids
        // allocating and copying a second full-size byte array for every frame.
        return Frame(width, height, payload, pixelOffset = 8)
    }

    private const val RGBA8888 = 1
    private const val BYTES_PER_PIXEL = 4L
}
