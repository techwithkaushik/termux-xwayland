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
        assertEquals(0x78123456, frame.pixels[0])
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
