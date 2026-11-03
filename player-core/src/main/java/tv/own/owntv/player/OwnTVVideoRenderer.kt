package tv.own.owntv.player

import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import java.nio.ByteBuffer

/**
 * Media3's video renderer, minus the empty H.264 samples [LivePreviewEngine]'s TS flags create.
 *
 * Live TS and HLS are extracted with `FLAG_DETECT_ACCESS_UNITS` (D-L2), so a feed *without* access-unit
 * delimiters still splits into pictures. On a feed *with* them, Media3's H264Reader then starts a sample
 * twice per picture — at the AUD, and again at the picture's first slice — so every frame reaches the
 * decoder as a 6-byte AUD-only buffer followed by the picture, both with the same timestamp. Software
 * decoders shrug it off. MediaTek's `c2.mtk.avc.decoder` (Google TV Streamer) keys its work by timestamp:
 * it outputs its first handful of pictures, then stops for good while the audio plays on — the "frozen on
 * the first frame" every B-frame H.264 channel showed on ExoPlayer there, and only on ExoPlayer.
 *
 * An AUD carries no picture data, so dropping a sample made of nothing else costs no decoder anything.
 */
@UnstableApi
open class OwnTVVideoRenderer(builder: Builder) : MediaCodecVideoRenderer(builder) {

    override fun shouldDiscardDecoderInputBuffer(buffer: DecoderInputBuffer): Boolean {
        if (super.shouldDiscardDecoderInputBuffer(buffer)) return true
        val data = buffer.data ?: return false
        if (codecInfo?.mimeType != MimeTypes.VIDEO_H264) return false
        // Not flipped yet at this point (Media3's own discard path reads it the same way).
        val sample = data.asReadOnlyBuffer().apply { flip() }
        if (!isAudOnlyAvcSample(sample)) return false
        buffer.clear()
        return true
    }
}

/** Largest sample worth scanning: an AUD is 6 bytes with its start code; a real picture is never this small. */
private const val MAX_AUD_ONLY_SAMPLE_BYTES = 16

private const val H264_NAL_UNIT_TYPE_AUD = 9

/**
 * Whether an Annex-B H.264 sample ([sample]'s position..limit) holds access-unit delimiters and nothing
 * else. Anything unparseable reads as "not AUD-only", so a doubtful sample is always decoded.
 */
internal fun isAudOnlyAvcSample(sample: ByteBuffer): Boolean {
    val start = sample.position()
    val end = sample.limit()
    if (end - start > MAX_AUD_ONLY_SAMPLE_BYTES) return false
    var sawNalUnit = false
    var i = start
    while (i + 3 < end) {
        if (sample.get(i) == 0.toByte() && sample.get(i + 1) == 0.toByte() && sample.get(i + 2) == 1.toByte()) {
            if (sample.get(i + 3).toInt() and 0x1F != H264_NAL_UNIT_TYPE_AUD) return false
            sawNalUnit = true
            i += 4
        } else {
            i++
        }
    }
    return sawNalUnit
}
