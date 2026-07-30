package fm.rizx.player.playback

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Listens to the song that is playing and reports its average spectrum, for the automatic equalizer.
 *
 * The audio comes from a [TeeAudioProcessor] in ExoPlayer's own pipeline — the app's own decoded PCM, so
 * **no `RECORD_AUDIO` permission** is involved, exactly like the Now Playing waveform. Two properties of
 * that tap matter here:
 *
 * - It sits **before** the session's `Equalizer` effect, so what is measured is the *original* recording.
 *   Measuring after the effect would be a feedback loop: the curve would keep correcting its own output
 *   until it ran into the rails.
 * - The audio thread only down-mixes and copies into a ring ([handleBuffer] stays cheap). The FFT and the
 *   averaging run on a background loop — a 2048-point transform on the audio thread is exactly how you
 *   earn a dropout.
 *
 * [measurement] holds null until a song has been listened to for long enough, then the mean-zero level per
 * [fm.rizx.player.domain.usecase.AutoEqCurves.ANCHORS_HZ] anchor. [reset] is called on every track change.
 */
@UnstableApi
@Singleton
class TrackSpectrum @Inject constructor() {

    private val accumulator = SpectrumAccumulator()

    private val _measurement = MutableStateFlow<FloatArray?>(null)
    val measurement: StateFlow<FloatArray?> = _measurement.asStateFlow()

    // Ring of recent mono samples: written by the audio thread, read by the analysis loop.
    private val ring = FloatArray(SpectrumAccumulator.FFT_SIZE * 2)
    @Volatile private var writeIndex = 0
    @Volatile private var channels = 2
    @Volatile private var encoding = C.ENCODING_PCM_16BIT

    /**
     * Total samples accepted, ever. Monotonic on purpose: the analysis loop compares it against what it has
     * already looked at, and resetting it per song would leave that comparison permanently in the past —
     * the second song of a session would then never get measured.
     */
    @Volatile private var written = 0L
    private var analysed = 0L

    /** True only while a song is actually being listened to; see [reset] and [stop]. */
    @Volatile private var measuring = false

    /** Samples to ignore after a [reset] — see [WARMUP_MS]. */
    @Volatile private var skipSamples = 0L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val frame = FloatArray(SpectrumAccumulator.FFT_SIZE)

    /** The sink handed to a `TeeAudioProcessor`; `PlaybackService` inserts it into the audio pipeline. */
    val sink: TeeAudioProcessor.AudioBufferSink = object : TeeAudioProcessor.AudioBufferSink {
        override fun flush(sampleRateHz: Int, channelCount: Int, pcmEncoding: Int) {
            channels = channelCount.coerceAtLeast(1)
            encoding = pcmEncoding
            accumulator.sampleRateHz = sampleRateHz
            skipSamples = (sampleRateHz.toLong() * WARMUP_MS) / 1_000
        }

        override fun handleBuffer(buffer: ByteBuffer) = append(buffer)
    }

    init {
        scope.launch {
            while (isActive) {
                // One frame per tick, and only when the ring has moved on by a whole window — so the same
                // audio is never counted twice and a paused player accumulates nothing.
                val seen = written
                if (measuring && seen - analysed >= frame.size) {
                    analysed = seen
                    snapshot(frame)
                    accumulator.addFrame(frame)
                    accumulator.measurement()?.let {
                        _measurement.value = it
                        measuring = false // this song's answer is in
                    }
                }
                delay(FRAME_MS)
            }
        }
    }

    /** Starts listening to a new song: everything measured so far belonged to the previous one. */
    fun reset() {
        accumulator.reset()
        _measurement.value = null
        skipSamples = (accumulator.sampleRateHz.toLong() * WARMUP_MS) / 1_000
        analysed = written
        measuring = true
    }

    /**
     * Stops listening — for a song whose curve is already known. Without this, a track playing from cache
     * would still be analysed for nothing, and the answer would be waiting in [measurement] for whoever
     * asked next.
     */
    fun stop() {
        measuring = false
        _measurement.value = null
        accumulator.reset()
    }

    private fun append(buffer: ByteBuffer) {
        // The tap keeps running for the waveform; this side only costs anything while a song is being
        // listened to.
        if (!measuring) return
        val ch = channels
        val dup = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                val shorts = dup.asShortBuffer()
                val frames = shorts.remaining() / ch
                for (f in 0 until frames) {
                    var sum = 0
                    val base = f * ch
                    for (c in 0 until ch) sum += shorts.get(base + c).toInt()
                    push((sum.toFloat() / ch) / 32_768f)
                }
            }
            // Hi-Res output makes the whole pipeline float; the measurement has to read both.
            C.ENCODING_PCM_FLOAT -> {
                val floats = dup.asFloatBuffer()
                val frames = floats.remaining() / ch
                for (f in 0 until frames) {
                    var sum = 0f
                    val base = f * ch
                    for (c in 0 until ch) sum += floats.get(base + c)
                    push(sum / ch)
                }
            }
            else -> return
        }
    }

    private fun push(sample: Float) {
        if (skipSamples > 0) { skipSamples--; return }
        ring[writeIndex] = sample
        writeIndex = (writeIndex + 1) % ring.size
        written++
    }

    /** Copies the most recent window out of the ring, oldest sample first. */
    private fun snapshot(out: FloatArray) {
        val end = writeIndex
        val start = (end - out.size + ring.size) % ring.size
        for (i in out.indices) out[i] = ring[(start + i) % ring.size]
    }

    private companion object {
        /** How often the analysis loop takes a window — ~25 a second, matching the visualizer's cadence. */
        const val FRAME_MS = 40L

        /**
         * Skipped after a track change, and eight seconds rather than two for a reason found on device: a
         * corrido opens with a *narración* — a spoken voice through a telephone filter, no bass at all and
         * a 30 dB hump at 1 kHz. Measured, that intro asks for a curve that is wrong for the rest of the
         * song. Intros are the least typical part of a track, so the measurement starts after one.
         */
        const val WARMUP_MS = 8_000L
    }
}
