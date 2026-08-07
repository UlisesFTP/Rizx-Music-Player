package fm.rizx.player.data.download

import fm.rizx.player.domain.model.SpatialAudioProfile
import fm.rizx.player.playback.spatial.StereoPcmTransform
import java.io.OutputStream

/**
 * Applies the spatializer to PCM on its way into an MP3 encoder.
 *
 * **Why a decorator rather than a step in the transcode loop.** [Mp3Transcoder] hands its encoder every
 * decoded buffer already, and that loop is shared with the plain MP3 download — putting a branch inside
 * it would make the ordinary path carry the 8D path's weight and give both of them one place to break.
 * As a decorator the loop never learns this exists.
 *
 * It also lands the **mono→stereo** case for free, which is why the seam is here and not further out.
 * A mono source has nowhere for a spatializer to put anything, so it is widened to two identical
 * channels and the delegate is built for two regardless of what arrived. Live playback bypasses mono
 * instead, because there the channel count was already announced to the audio sink and cannot change;
 * a file has no such promise to keep.
 *
 * **The effect does not fade in here.** During playback the ramp exists so switching the effect on is
 * not a click; a render has no listener mid-fade, and a file whose first second is untreated would just
 * be a file with a mistake at the start.
 *
 * Deterministic: the same source, profile and sample rate produce the same bytes every time, because
 * the orbit is driven by the frame count rather than by a clock.
 */
class SpatialMp3Encoder(
    private val engine: StereoPcmTransform,
    profile: SpatialAudioProfile,
    sampleRateHz: Int,
    /** Channels arriving from the decoder. The [delegate] is always fed two. */
    private val sourceChannels: Int,
    private val delegate: Mp3Encoder,
) : Mp3Encoder {

    private val sampleRate = sampleRateHz

    init {
        // This order is load-bearing. `configure` resets the engine, and the profile is adopted outright
        // rather than glided into only while nothing is sounding yet — so enabling it must come last, or
        // the render would open with a second and a half of some other song's character.
        engine.configure(sampleRateHz)
        engine.setProfile(profile)
        engine.setEnabled(true, ramp = false)
    }

    private var stereo = FloatArray(0)
    private var out16 = ShortArray(0)
    private var framesDone = 0L

    override fun encode(pcm: ShortArray, frames: Int, out: OutputStream) {
        if (frames <= 0) return
        val samples = frames * 2
        if (stereo.size < samples) {
            stereo = FloatArray(samples)
            out16 = ShortArray(samples)
        }

        // Both directions scale by the same power of two, never by 32767: dividing by one value and
        // multiplying by another loses the low bit of every sample, which is a dither nobody asked for.
        if (sourceChannels == 1) {
            for (i in 0 until frames) {
                val s = pcm[i] * INV_FULL_SCALE
                stereo[i * 2] = s
                stereo[i * 2 + 1] = s
            }
        } else {
            for (i in 0 until samples) stereo[i] = pcm[i] * INV_FULL_SCALE
        }

        engine.process(stereo, frames, framesDone * 1_000_000L / sampleRate)
        framesDone += frames

        for (i in 0 until samples) {
            val v = (stereo[i] * FULL_SCALE).toInt()
            out16[i] = v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        delegate.encode(out16, frames, out)
    }

    override fun finish(out: OutputStream) = delegate.finish(out)

    private companion object {
        const val FULL_SCALE = 32_768f
        const val INV_FULL_SCALE = 1f / FULL_SCALE
    }
}
