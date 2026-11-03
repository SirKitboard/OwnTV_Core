package tv.own.owntv.player

import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import java.nio.ByteBuffer

/**
 * Media3's video renderer, with the H.264 access units [LivePreviewEngine]'s TS flags split in two put
 * back together.
 *
 * Live TS and HLS are extracted with `FLAG_DETECT_ACCESS_UNITS` (D-L2), so a feed *without* access-unit
 * delimiters still splits into pictures. On a feed *with* them, Media3's H264Reader starts a sample twice
 * per picture — at the AUD, and again at the picture's first slice. Whatever sits between the two (the AUD,
 * SEI, and on a keyframe the SPS and PPS) reaches the decoder as a sample of its own, with the same
 * timestamp as the picture behind it. Software decoders shrug it off. MediaTek's `c2.mtk.avc.decoder`
 * (Google TV Streamer) keys its work by timestamp: it takes the picture-less sample, files the real picture
 * as a duplicate ("work already in workStash"), outputs its first handful of pictures and then stops for
 * good while the audio plays on — frozen on the first frame, on ExoPlayer only.
 *
 * A picture-less sample is held back instead of queued, and its bytes go in front of the next sample: the
 * access unit exactly as the stream carried it, so the in-band SPS/PPS still reach the decoder.
 */
@UnstableApi
open class OwnTVVideoRenderer(builder: Builder) : MediaCodecVideoRenderer(builder) {

    /** NAL units held back from the last picture-less sample, waiting for the picture they belong to. */
    private var pendingHeader: ByteArray? = null
    private var scratch = ByteArray(0)

    override fun shouldDiscardDecoderInputBuffer(buffer: DecoderInputBuffer): Boolean {
        if (super.shouldDiscardDecoderInputBuffer(buffer)) return true
        val data = buffer.data ?: return false
        if (codecInfo?.mimeType != MimeTypes.VIDEO_H264 || buffer.isEncrypted) {
            pendingHeader = null
            return false
        }
        // Not flipped yet at this point (Media3's own discard path reads it the same way).
        val sample = data.asReadOnlyBuffer().apply { flip() }
        if (isPictureLessAvcSample(sample)) {
            val bytes = ByteArray(sample.remaining()).also { sample.get(it) }
            val held = pendingHeader
            pendingHeader = if (held == null || held.size + bytes.size > MAX_PICTURE_LESS_SAMPLE_BYTES) bytes else held + bytes
            buffer.clear()
            return true
        }
        val header = pendingHeader ?: return false
        pendingHeader = null
        if (scratch.size < data.position()) scratch = ByteArray(data.position())
        // No room in the codec's buffer: the picture goes alone, as it did before this renderer existed.
        prependInPlace(data, header, scratch)
        return false
    }

    override fun resetCodecStateForFlush() {
        super.resetCodecStateForFlush()
        pendingHeader = null
    }
}

/** Largest sample worth holding back: AUD, SEI, SPS and PPS together are a few hundred bytes at most. */
internal const val MAX_PICTURE_LESS_SAMPLE_BYTES = 64 * 1024

/**
 * Whether an Annex-B H.264 sample ([sample]'s position..limit) holds NAL units but no coded slice (types
 * 1–5): only an AUD, SEI, SPS, PPS and the like. Anything unparseable, or too big to be headers, reads as
 * "a picture", so a doubtful sample is always decoded as it came.
 */
internal fun isPictureLessAvcSample(sample: ByteBuffer): Boolean {
    val start = sample.position()
    val end = sample.limit()
    if (end - start > MAX_PICTURE_LESS_SAMPLE_BYTES) return false
    var sawNalUnit = false
    var i = start
    while (i + 3 < end) {
        if (sample.get(i) == 0.toByte() && sample.get(i + 1) == 0.toByte() && sample.get(i + 2) == 1.toByte()) {
            if ((sample.get(i + 3).toInt() and 0x1F) in 1..5) return false
            sawNalUnit = true
            i += 4
        } else {
            i++
        }
    }
    return sawNalUnit
}

/**
 * Put [prefix] in front of the bytes already written to [data] (0 until its position, not yet flipped),
 * leaving the position at the new end. [scratch] must hold the existing bytes. False, with [data]
 * untouched, when the buffer has no room for both.
 */
internal fun prependInPlace(data: ByteBuffer, prefix: ByteArray, scratch: ByteArray): Boolean {
    val size = data.position()
    if (size + prefix.size > data.limit() || scratch.size < size) return false
    data.duplicate().apply { flip() }.get(scratch, 0, size)
    data.position(0)
    data.put(prefix)
    data.put(scratch, 0, size)
    return true
}
