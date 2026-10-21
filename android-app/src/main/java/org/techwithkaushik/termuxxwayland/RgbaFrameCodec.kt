package org.techwithkaushik.termuxxwayland

import java.io.IOException

/**
 * Validates the bridge's bounded RGBA8888 payload before native presentation.
 * Wire metadata: big-endian u16 width/height, format=1, then 3 reserved zero bytes.
 */
internal object RgbaFrameCodec {
    internal data class FrameInfo(val width: Int, val height: Int)
    internal data class Frame(val width: Int, val height: Int, val pixels: IntArray)

    fun validate(payload: ByteArray): FrameInfo {
        if (payload.size < HEADER_SIZE) throw IOException("Truncated frame metadata")
        val width = ((payload[0].toInt() and 0xff) shl 8) or (payload[1].toInt() and 0xff)
        val height = ((payload[2].toInt() and 0xff) shl 8) or (payload[3].toInt() and 0xff)
        val format = payload[4].toInt() and 0xff
        if (width !in 1..MAX_WIDTH || height !in 1..MAX_HEIGHT || format != RGBA8888 ||
            payload[5] != 0.toByte() || payload[6] != 0.toByte() || payload[7] != 0.toByte()
        ) throw IOException("Unsupported or invalid frame metadata")

        val expectedBytes = HEADER_SIZE.toLong() +
            width.toLong() * height.toLong() * BYTES_PER_PIXEL
        if (expectedBytes != payload.size.toLong()) {
            throw IOException("Frame pixel length does not match its dimensions")
        }
        return FrameInfo(width, height)
    }

    /** Kept for JVM unit tests and diagnostics; live rendering uses validate() and native Surface. */
    fun decode(payload: ByteArray): Frame {
        val info = validate(payload)
        val pixels = IntArray(info.width * info.height)
        var offset = HEADER_SIZE
        for (index in pixels.indices) {
            val red = payload[offset++].toInt() and 0xff
            val green = payload[offset++].toInt() and 0xff
            val blue = payload[offset++].toInt() and 0xff
            val alpha = payload[offset++].toInt() and 0xff
            pixels[index] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
        }
        return Frame(info.width, info.height, pixels)
    }

    private const val HEADER_SIZE = 8
    private const val MAX_WIDTH = 640
    private const val MAX_HEIGHT = 360
    private const val RGBA8888 = 1
    private const val BYTES_PER_PIXEL = 4L
}
