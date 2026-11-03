package tv.own.owntv.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/** The picture-less H.264 samples held back from MediaTek's decoder — and everything that must still reach it. */
class OwnTVVideoRendererTest {

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
    private fun sample(vararg b: Int): ByteBuffer = ByteBuffer.wrap(bytes(*b))

    @Test
    fun `a lone AUD with a 4-byte start code is picture-less`() {
        // The 6-byte sample Media3's H264Reader emits ahead of each picture on a feed with AUDs.
        assertTrue(isPictureLessAvcSample(sample(0, 0, 0, 1, 0x09, 0xF0)))
    }

    @Test
    fun `a lone AUD with a 3-byte start code is picture-less`() {
        assertTrue(isPictureLessAvcSample(sample(0, 0, 1, 0x09, 0x10)))
    }

    @Test
    fun `an AUD with picture timing SEI is picture-less`() {
        // The traced feed carries AUD + pic_timing SEI ahead of every slice.
        assertTrue(isPictureLessAvcSample(sample(0, 0, 0, 1, 0x09, 0xF0, 0, 0, 0, 1, 0x06, 0x01, 0x04, 0x00, 0x02, 0x10, 0x80)))
    }

    @Test
    fun `a keyframe's headers without the IDR are picture-less`() {
        // AUD, SEI, SPS, PPS — the first half of a split keyframe.
        assertTrue(
            isPictureLessAvcSample(
                sample(0, 0, 0, 1, 0x09, 0xF0, 0, 0, 1, 0x06, 0x00, 0x01, 0x80, 0, 0, 1, 0x67, 0x64, 0x00, 0x28, 0, 0, 1, 0x68, 0xCE),
            ),
        )
    }

    @Test
    fun `an AUD followed by a slice is a picture`() {
        assertFalse(isPictureLessAvcSample(sample(0, 0, 0, 1, 0x09, 0xF0, 0, 0, 0, 1, 0x41, 0x9A, 0x02)))
    }

    @Test
    fun `an IDR slice alone is a picture`() {
        assertFalse(isPictureLessAvcSample(sample(0, 0, 0, 1, 0x65, 0x88, 0x84)))
    }

    @Test
    fun `bytes without a start code are kept`() {
        assertFalse(isPictureLessAvcSample(sample(0x09, 0xF0, 0x00, 0x00)))
        assertFalse(isPictureLessAvcSample(sample()))
    }

    @Test
    fun `a sample too big to be headers is kept without scanning`() {
        val big = ByteArray(MAX_PICTURE_LESS_SAMPLE_BYTES + 1).also { it[3] = 1; it[4] = 0x09 }
        assertFalse(isPictureLessAvcSample(ByteBuffer.wrap(big)))
    }

    @Test
    fun `only the buffer's position to limit is read`() {
        // A picture's bytes before position and after limit must not decide the answer.
        val b = sample(0, 0, 0, 1, 0x65, 0, 0, 0, 1, 0x09, 0xF0, 0, 0, 0, 1, 0x41)
        b.position(5).limit(11)
        assertTrue(isPictureLessAvcSample(b))
    }

    @Test
    fun `held-back headers go back in front of the picture`() {
        // A codec input buffer as Media3 leaves it before queueing: written up to position, not flipped.
        val data = ByteBuffer.allocateDirect(32)
        data.put(bytes(0, 0, 1, 0x41, 0x9A))
        val header = bytes(0, 0, 0, 1, 0x09, 0xF0)
        assertTrue(prependInPlace(data, header, ByteArray(5)))
        assertEquals(11, data.position())
        val out = ByteArray(11).also { data.flip(); data.get(it) }
        assertArrayEquals(bytes(0, 0, 0, 1, 0x09, 0xF0, 0, 0, 1, 0x41, 0x9A), out)
    }

    @Test
    fun `no room means the picture is left as it was`() {
        val data = ByteBuffer.allocate(8)
        data.put(bytes(0, 0, 1, 0x41, 0x9A))
        assertFalse(prependInPlace(data, bytes(0, 0, 0, 1, 0x09, 0xF0), ByteArray(5)))
        assertEquals(5, data.position())
        val out = ByteArray(5).also { data.flip(); data.get(it) }
        assertArrayEquals(bytes(0, 0, 1, 0x41, 0x9A), out)
    }
}
