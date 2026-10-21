package org.techwithkaushik.termuxxwayland

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RgbaFrameCodecTest {
    @Test
    fun decodesRgbaPixelsAndDimensions() {
        val payload = byteArrayOf(
            0, 1, 0, 1, 1, 0, 0, 0,
            0x12, 0x34, 0x56, 0x78
        )
        val frame = RgbaFrameCodec.decode(payload)
        assertEquals(1, frame.width)
        assertEquals(1, frame.height)
        assertEquals(payload.size, frame.pixels.size)
        assertEquals(8, frame.pixelOffset)
        assertEquals(0x12, frame.pixels[frame.pixelOffset].toInt() and 0xff)
        assertEquals(0x34, frame.pixels[frame.pixelOffset + 1].toInt() and 0xff)
        assertEquals(0x56, frame.pixels[frame.pixelOffset + 2].toInt() and 0xff)
        assertEquals(0x78, frame.pixels[frame.pixelOffset + 3].toInt() and 0xff)
    }

    @Test
    fun rejectsTruncatedMetadataAndMismatchedPixelLength() {
        assertThrows(IOException::class.java) { RgbaFrameCodec.decode(byteArrayOf(0, 1)) }
        val shortPixels = byteArrayOf(0, 1, 0, 1, 1, 0, 0, 0, 1, 2, 3)
        assertThrows(IOException::class.java) { RgbaFrameCodec.decode(shortPixels) }
    }

    @Test
    fun rejectsInvalidDimensionsFormatAndReservedBytes() {
        assertThrows(IOException::class.java) {
            RgbaFrameCodec.decode(byteArrayOf(0, 0, 0, 1, 1, 0, 0, 0))
        }
        assertThrows(IOException::class.java) {
            RgbaFrameCodec.decode(byteArrayOf(0, 1, 0, 1, 2, 0, 0, 0, 0, 0, 0, 0))
        }
        assertThrows(IOException::class.java) {
            RgbaFrameCodec.decode(byteArrayOf(0, 1, 0, 1, 1, 1, 0, 0, 0, 0, 0, 0))
        }
    }
}
