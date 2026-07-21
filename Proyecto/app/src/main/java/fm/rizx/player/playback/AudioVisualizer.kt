package fm.rizx.player.playback

import androidx.media3.common.C
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.common.util.UnstableApi
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
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Real-time audio spectrum for the Now Playing waveform. A [TeeAudioProcessor] taps the **decoded PCM**
 * from the ExoPlayer audio pipeline — it's the app's own audio, so this needs **no `RECORD_AUDIO`
 * permission** (unlike `android.media.audiofx.Visualizer`). The audio-thread hot path ([handleBuffer])
 * only down-mixes and copies a window (cheap); a background loop runs a small FFT ~[FRAME_MS]-spaced,
 * groups the magnitude spectrum into [BAR_COUNT] log-spaced bands, compresses + smooths them, and
 * publishes normalized 0..1 [levels]. Idle/paused → the bars decay to a calm baseline.
 */
@UnstableApi
@Singleton
class AudioVisualizer @Inject constructor() {

    private val _levels = MutableStateFlow(FloatArray(BAR_COUNT))
    val levels: StateFlow<FloatArray> = _levels.asStateFlow()

    // Ring of the most-recent down-mixed mono samples, written by the audio thread, read by the loop.
    private val ring = FloatArray(FFT_SIZE)
    @Volatile private var writeIndex = 0
    @Volatile private var bufferCounter = 0L
    @Volatile private var channels = 2
    @Volatile private var encoding = C.ENCODING_PCM_16BIT

    private val hann = FloatArray(FFT_SIZE) { 0.5f * (1f - cos((2.0 * Math.PI * it / (FFT_SIZE - 1)).toFloat())) }
    private val bandLo = IntArray(BAR_COUNT)
    private val bandHi = IntArray(BAR_COUNT)
    private val smoothed = FloatArray(BAR_COUNT)

    /** Scratch for one frame's normalised band magnitudes, and the level they're measured against. */
    private val band = FloatArray(BAR_COUNT)
    private var reference = AGC_FLOOR

    /**
     * Spectral tilt. Music loses energy towards the treble, so without compensation the right-hand third
     * of the spectrum sits dead flat while the bass end does all the moving. Boosting progressively with
     * frequency spreads the animation across the full width. Bands above the source's cutoff stay still
     * either way — there's nothing there to lift.
     */
    private val tilt = FloatArray(BAR_COUNT) { 1f + TILT_TOP_BOOST * (it.toFloat() / (BAR_COUNT - 1)) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The sink handed to a [TeeAudioProcessor]; `PlaybackService` inserts it into the audio pipeline. */
    val sink: TeeAudioProcessor.AudioBufferSink = object : TeeAudioProcessor.AudioBufferSink {
        override fun flush(sampleRateHz: Int, channelCount: Int, pcmEncoding: Int) {
            channels = channelCount.coerceAtLeast(1)
            encoding = pcmEncoding
        }

        override fun handleBuffer(buffer: ByteBuffer) = appendBuffer(buffer)
    }

    init {
        // Log-spaced band edges over FFT bins 1..N/2, so bass/mid/treble spread across the bars.
        val maxBin = FFT_SIZE / 2
        val minBin = 1.0
        for (b in 0 until BAR_COUNT) {
            val lo = minBin * Math.pow(maxBin / minBin, b.toDouble() / BAR_COUNT)
            val hi = minBin * Math.pow(maxBin / minBin, (b + 1.0) / BAR_COUNT)
            bandLo[b] = lo.toInt().coerceIn(1, maxBin - 1)
            bandHi[b] = hi.toInt().coerceIn(bandLo[b] + 1, maxBin)
        }
        val re = FloatArray(FFT_SIZE)
        val im = FloatArray(FFT_SIZE)
        var lastSeen = -1L
        scope.launch {
            while (isActive) {
                val counter = bufferCounter
                if (counter != lastSeen) {
                    lastSeen = counter
                    computeBands(re, im)
                } else {
                    for (b in 0 until BAR_COUNT) smoothed[b] = max(IDLE, smoothed[b] * IDLE_DECAY)
                }
                _levels.value = smoothed.copyOf()
                delay(FRAME_MS)
            }
        }
    }

    private fun appendBuffer(buffer: ByteBuffer) {
        val ch = channels
        val dup = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                val sb = dup.asShortBuffer()
                val frames = sb.remaining() / ch
                var f = 0
                while (f < frames) {
                    var sum = 0
                    val base = f * ch
                    for (c in 0 until ch) sum += sb.get(base + c).toInt()
                    push((sum.toFloat() / ch) / 32768f)
                    f += DOWNSAMPLE
                }
            }
            C.ENCODING_PCM_FLOAT -> {
                val fb = dup.asFloatBuffer()
                val frames = fb.remaining() / ch
                var f = 0
                while (f < frames) {
                    var sum = 0f
                    val base = f * ch
                    for (c in 0 until ch) sum += fb.get(base + c)
                    push(sum / ch)
                    f += DOWNSAMPLE
                }
            }
            else -> return
        }
        bufferCounter++
    }

    private fun push(sample: Float) {
        ring[writeIndex] = sample
        writeIndex = (writeIndex + 1) % FFT_SIZE
    }

    private fun computeBands(re: FloatArray, im: FloatArray) {
        val start = writeIndex
        for (j in 0 until FFT_SIZE) {
            re[j] = ring[(start + j) % FFT_SIZE] * hann[j]
            im[j] = 0f
        }
        fft(re, im)

        // Per-band peak, brought back to roughly unit scale. The FFT is unnormalised, so a full-scale
        // tone lands near FFT_SIZE/2 rather than 1 — feeding that straight into the log curve is what
        // pinned every bar to the ceiling.
        var frameMax = 0f
        for (b in 0 until BAR_COUNT) {
            var peak = 0f
            for (k in bandLo[b] until bandHi[b]) {
                val mag = sqrt(re[k] * re[k] + im[k] * im[k])
                if (mag > peak) peak = mag
            }
            val magnitude = peak / HALF_SIZE * tilt[b]
            band[b] = magnitude
            if (magnitude > frameMax) frameMax = magnitude
        }

        // Adaptive reference: track the recent loudest band and normalise against it, plus [HEADROOM] of
        // slack so even the tallest bar usually stops short of the top. Quiet passages still read (the
        // reference falls with them) without silence amplifying noise (it can't fall below [AGC_FLOOR]).
        reference = maxOf(frameMax, reference * AGC_DECAY).coerceAtLeast(AGC_FLOOR)
        val scale = reference * HEADROOM

        for (b in 0 until BAR_COUNT) {
            val normalized = (band[b] / scale).coerceIn(0f, 1f)
            val level = (ln(1f + normalized * GAIN) / LOG_NORM).coerceIn(0f, 1f)
            val prev = smoothed[b]
            // Fast attack, slow release — the classic lively spectrum feel.
            smoothed[b] = if (level >= prev) level else prev * RELEASE + level * (1f - RELEASE)
        }
    }

    /** In-place iterative radix-2 Cooley–Tukey FFT ([FFT_SIZE] must be a power of two). */
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j or bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wr = cos(ang).toFloat()
            val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f
                var ci = 0f
                val half = len / 2
                for (k in 0 until half) {
                    val a = i + k
                    val bIdx = a + half
                    val vr = re[bIdx] * cr - im[bIdx] * ci
                    val vi = re[bIdx] * ci + im[bIdx] * cr
                    re[bIdx] = re[a] - vr; im[bIdx] = im[a] - vi
                    re[a] += vr; im[a] += vi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }

    companion object {
        const val BAR_COUNT = 56
        private const val FFT_SIZE = 512
        private const val HALF_SIZE = FFT_SIZE / 2f
        private const val DOWNSAMPLE = 2
        private const val FRAME_MS = 40L // ~25 fps updates

        /**
         * Curve of the level → bar-height mapping. A little perceptual lift so quiet detail still shows,
         * but far gentler than a broadcast-style compressor: high values flatten everything towards the
         * top, which is exactly the "always maxed" look this replaced.
         */
        private const val GAIN = 5f
        private val LOG_NORM = ln(1f + GAIN)

        /** How fast the adaptive reference falls (per frame). ~0.5 s to follow a drop in level. */
        private const val AGC_DECAY = 0.94f

        /** Floor for the reference, so near-silence doesn't get amplified into full-height noise. */
        private const val AGC_FLOOR = 0.02f

        /** Slack above the loudest band, so the tallest bar normally sits below the ceiling. */
        private const val HEADROOM = 1.25f

        /** Extra gain applied to the highest band by the spectral tilt (lowest band keeps 1x). */
        private const val TILT_TOP_BOOST = 9f

        private const val RELEASE = 0.80f
        private const val IDLE = 0.05f
        private const val IDLE_DECAY = 0.86f
    }
}
