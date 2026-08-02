package fm.rizx.player.playback

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Down-mixes one interleaved PCM buffer to mono samples in `-1..1`, calling [consume] per frame.
 *
 * Shared by the two things that listen to the audio — the Now Playing waveform and the automatic
 * equalizer's measurement — because both now read the **decoder's own output** rather than a buffer the
 * sink already normalised for them. That is what makes the encoding list matter: a tap placed after
 * `ResamplingAudioProcessor` only ever sees 16-bit, while a tap placed before the sink sees whatever the
 * decoder produced — 16-bit for most things, float for a FLAC decoded with the float path on, and 24/32
 * bit for a raw local file.
 *
 * [stride] skips frames for callers that don't need every one (the waveform reads every other sample;
 * the measurement reads all of them). Returns `false` for an encoding this doesn't know how to read —
 * compressed passthrough, big-endian PCM — so the caller can leave its counters alone.
 *
 * Runs on the audio thread: `inline`, so the lambda costs nothing, and no allocation beyond the buffer
 * view itself. The source buffer's position is never touched.
 */
@UnstableApi
internal inline fun ByteBuffer.forEachMonoSample(
    channels: Int,
    encoding: Int,
    stride: Int = 1,
    consume: (Float) -> Unit,
): Boolean {
    val ch = channels.coerceAtLeast(1)
    val step = stride.coerceAtLeast(1)
    val dup = duplicate().order(ByteOrder.LITTLE_ENDIAN)
    when (encoding) {
        C.ENCODING_PCM_16BIT -> {
            val shorts = dup.asShortBuffer()
            val frames = shorts.remaining() / ch
            var f = 0
            while (f < frames) {
                var sum = 0
                val base = f * ch
                for (c in 0 until ch) sum += shorts.get(base + c).toInt()
                consume((sum.toFloat() / ch) / 32_768f)
                f += step
            }
        }
        C.ENCODING_PCM_FLOAT -> {
            val floats = dup.asFloatBuffer()
            val frames = floats.remaining() / ch
            var f = 0
            while (f < frames) {
                var sum = 0f
                val base = f * ch
                for (c in 0 until ch) sum += floats.get(base + c)
                consume(sum / ch)
                f += step
            }
        }
        // Three bytes per sample, so there is no NIO view to borrow — read them by hand. Absolute `get`
        // ignores the buffer's position, hence the explicit origin.
        C.ENCODING_PCM_24BIT -> {
            val origin = dup.position()
            val frames = dup.remaining() / (3 * ch)
            var f = 0
            while (f < frames) {
                var sum = 0f
                val base = origin + f * 3 * ch
                for (c in 0 until ch) {
                    val at = base + c * 3
                    // The top byte keeps its sign, which is what sign-extends the 24-bit sample.
                    val value = (dup.get(at).toInt() and 0xFF) or
                        ((dup.get(at + 1).toInt() and 0xFF) shl 8) or
                        (dup.get(at + 2).toInt() shl 16)
                    sum += value / 8_388_608f
                }
                consume(sum / ch)
                f += step
            }
        }
        C.ENCODING_PCM_32BIT -> {
            val ints = dup.asIntBuffer()
            val frames = ints.remaining() / ch
            var f = 0
            while (f < frames) {
                var sum = 0f
                val base = f * ch
                for (c in 0 until ch) sum += ints.get(base + c) / 2_147_483_648f
                consume(sum / ch)
                f += step
            }
        }
        else -> return false
    }
    return true
}
