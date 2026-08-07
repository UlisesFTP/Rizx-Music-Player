package fm.rizx.player.playback.spatial

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Converts between a decoder's PCM buffer and plain floats, **per channel**.
 *
 * `PcmDownMix.forEachMonoSample` already decodes the same four encodings, but it sums the channels on
 * the way out — which is exactly right for a spectrum analyser and exactly wrong here, since stereo
 * width is half of what the spatializer reads and all of what it writes. So the decode arithmetic is
 * the same and the shape is not: this keeps the channels apart, and it can write as well as read.
 *
 * Absolute gets and puts throughout, so a caller's buffer position is never disturbed by a read — the
 * discipline the tapping sink already relies on.
 */
@UnstableApi
internal object PcmFrameCodec {

    fun supports(encoding: Int): Boolean = bytesPerSample(encoding) > 0

    fun bytesPerSample(encoding: Int): Int = when (encoding) {
        C.ENCODING_PCM_16BIT -> 2
        C.ENCODING_PCM_24BIT -> 3
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
        else -> 0
    }

    /**
     * Decodes [sampleCount] interleaved samples starting at absolute byte [offset] into [out], scaled
     * to −1..1.
     */
    fun read(source: ByteBuffer, offset: Int, out: FloatArray, sampleCount: Int, encoding: Int) {
        val buffer = source.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        when (encoding) {
            C.ENCODING_PCM_16BIT ->
                for (i in 0 until sampleCount) out[i] = buffer.getShort(offset + i * 2) / 32_768f

            C.ENCODING_PCM_24BIT ->
                for (i in 0 until sampleCount) {
                    val at = offset + i * 3
                    // Little-endian three-byte word; the top byte carries the sign, so it is read as a
                    // signed byte and the lower two as unsigned.
                    val value = (buffer.get(at).toInt() and 0xFF) or
                        ((buffer.get(at + 1).toInt() and 0xFF) shl 8) or
                        (buffer.get(at + 2).toInt() shl 16)
                    out[i] = value / 8_388_608f
                }

            C.ENCODING_PCM_32BIT ->
                for (i in 0 until sampleCount) out[i] = buffer.getInt(offset + i * 4) / 2_147_483_648f

            C.ENCODING_PCM_FLOAT ->
                for (i in 0 until sampleCount) out[i] = buffer.getFloat(offset + i * 4)
        }
    }

    /**
     * Encodes [sampleCount] samples back into [target] starting at absolute byte 0.
     *
     * **Scaled by the same power of two [read] divides by, then clamped** — rather than by the
     * asymmetric maximum. Multiplying back by `2^n − 1` is the more common spelling and it loses the
     * bottom bit of every sample on the way home, so audio that merely passed through would come out
     * quietly dithered. Clamping instead keeps the round trip exact and costs only the single loudest
     * possible sample, which is a value no real recording sits at.
     */
    fun write(samples: FloatArray, sampleCount: Int, target: ByteBuffer, encoding: Int) {
        val buffer = target.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        when (encoding) {
            C.ENCODING_PCM_16BIT ->
                for (i in 0 until sampleCount) {
                    val value = (samples[i] * 32_768f).roundToInt().coerceIn(-32_768, 32_767)
                    buffer.putShort(i * 2, value.toShort())
                }

            C.ENCODING_PCM_24BIT ->
                for (i in 0 until sampleCount) {
                    val at = i * 3
                    val value = (samples[i] * 8_388_608f).roundToInt().coerceIn(-8_388_608, 8_388_607)
                    buffer.put(at, (value and 0xFF).toByte())
                    buffer.put(at + 1, ((value shr 8) and 0xFF).toByte())
                    buffer.put(at + 2, ((value shr 16) and 0xFF).toByte())
                }

            C.ENCODING_PCM_32BIT ->
                for (i in 0 until sampleCount) {
                    // Through a Long: 2^31 is outside Int's range, and rounding a full-scale sample
                    // straight to Int would wrap it to the largest *negative* number.
                    val value = (samples[i].toDouble() * 2_147_483_648.0)
                        .roundToLong()
                        .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
                    buffer.putInt(i * 4, value.toInt())
                }

            C.ENCODING_PCM_FLOAT ->
                for (i in 0 until sampleCount) buffer.putFloat(i * 4, samples[i].coerceIn(-1f, 1f))
        }
    }
}
