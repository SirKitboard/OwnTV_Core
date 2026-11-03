package tv.own.owntv.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/** The AUD-only H.264 samples dropped before MediaTek's decoder — and everything that must still reach it. */
class OwnTVVideoRendererTest {

    private fun sample(vararg bytes: Int): ByteBuffer = ByteBuffer.wrap(ByteArray(bytes.size) { bytes[it].toByte() })

    @Test
    fun `a lone AUD with a 4-byte start code is AUD-only`() {
        // Exactly the 6-byte sample Media3's H264Reader emits ahead of each picture on a feed with AUDs.
        assertTrue(isAudOnlyAvcSample(sample(0, 0, 0, 1, 0x09, 0xF0)))
    }

    @Test
    fun `a lone AUD with a 3-byte start code is AUD-only`() {
        assertTrue(isAudOnlyAvcSample(sample(0, 0, 1, 0x09, 0x10)))
    }

    @Test
    fun `an AUD followed by a slice is a picture`() {
        assertFalse(isAudOnlyAvcSample(sample(0, 0, 0, 1, 0x09, 0xF0, 0, 0, 0, 1, 0x41, 0x9A, 0x02)))
    }

    @Test
    fun `an IDR slice alone is a picture`() {
        assertFalse(isAudOnlyAvcSample(sample(0, 0, 0, 1, 0x65, 0x88, 0x84)))
    }

    @Test
    fun `parameter sets are never dropped`() {
        assertFalse(isAudOnlyAvcSample(sample(0, 0, 0, 1, 0x67, 0x64, 0x00, 0x28)))
    }

    @Test
    fun `bytes without a start code are kept`() {
        assertFalse(isAudOnlyAvcSample(sample(0x09, 0xF0, 0x00, 0x00)))
        assertFalse(isAudOnlyAvcSample(sample()))
    }

    @Test
    fun `a large sample is kept without scanning`() {
        // Starts with an AUD, but nothing that big is AUD-only.
        assertFalse(isAudOnlyAvcSample(sample(0, 0, 0, 1, 0x09, 0xF0, *IntArray(64))))
    }

    @Test
    fun `only the buffer's position to limit is read`() {
        // A picture's bytes before position and after limit must not decide the answer.
        val b = sample(0, 0, 0, 1, 0x65, 0, 0, 0, 1, 0x09, 0xF0, 0, 0, 0, 1, 0x41)
        b.position(5).limit(11)
        assertTrue(isAudOnlyAvcSample(b))
    }
}
