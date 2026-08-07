package fm.rizx.player.playback.spatial

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import fm.rizx.player.domain.model.SpatialAnalysis
import fm.rizx.player.playback.Fft
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Listens to the recording — the real one, before the spatializer touches it — and works out what kind
 * of mix it is.
 *
 * **A third tap rather than a second use of [fm.rizx.player.playback.TrackSpectrum].** That one already
 * runs an FFT off the audio thread and would have been free to reuse, except that it sums the channels
 * to mono first. Stereo width and correlation are the two measurements that most change what the
 * spatializer should do, and they are precisely what a downmix destroys.
 *
 * The audio callback does the cheapest possible work: decode, add up four running sums, copy into a
 * ring buffer. Everything with a transform in it happens on [Dispatchers.Default]. Nothing here can
 * make playback wait.
 */
@UnstableApi
@Singleton
class SpatialTrackAnalyzer @Inject constructor() {

    private val _analysis = MutableStateFlow<SpatialAnalysis?>(null)

    /** Null until the recording has been listened to for long enough to say anything about it. */
    val analysis: StateFlow<SpatialAnalysis?> = _analysis.asStateFlow()

    @Volatile private var channels = 2
    @Volatile private var encoding = 0
    @Volatile private var sampleRate = 48_000

    // Running sums, written by the audio thread and read by the analysis loop. Statistics, not
    // decisions: a torn read costs one buffer's worth of accuracy and nothing else.
    @Volatile private var sumLeft = 0.0
    @Volatile private var sumRight = 0.0
    @Volatile private var sumProduct = 0.0
    @Volatile private var sumSquares = 0.0
    @Volatile private var peak = 0f
    @Volatile private var counted = 0L

    private val ring = FloatArray(RING_FRAMES)
    @Volatile private var written = 0L
    private var scratch = FloatArray(0)

    private var scope: CoroutineScope? = null

    val sink: TeeAudioProcessor.AudioBufferSink = object : TeeAudioProcessor.AudioBufferSink {
        override fun flush(sampleRateHz: Int, channelCount: Int, pcmEncoding: Int) {
            sampleRate = sampleRateHz
            channels = channelCount
            encoding = pcmEncoding
        }

        override fun handleBuffer(buffer: ByteBuffer) = accumulate(buffer)
    }

    /** Starts listening to a new song. Any measurement of the previous one is dropped. */
    fun reset() {
        stop()
        sumLeft = 0.0
        sumRight = 0.0
        sumProduct = 0.0
        sumSquares = 0.0
        peak = 0f
        counted = 0L
        written = 0L
        _analysis.value = null
        val created = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = created
        created.launch { measure() }
    }

    fun stop() = stopScope()

    private fun accumulate(buffer: ByteBuffer) {
        val ch = channels
        val enc = encoding
        if (ch < 1 || !PcmFrameCodec.supports(enc)) return
        val bytesPerSample = PcmFrameCodec.bytesPerSample(enc)
        val frames = buffer.remaining() / (bytesPerSample * ch)
        if (frames <= 0) return

        val needed = frames * ch
        if (scratch.size < needed) scratch = FloatArray(needed)
        PcmFrameCodec.read(buffer, buffer.position(), scratch, needed, enc)

        var left = 0.0
        var right = 0.0
        var product = 0.0
        var squares = 0.0
        var localPeak = peak
        var index = written.toInt() % RING_FRAMES
        for (f in 0 until frames) {
            val base = f * ch
            val l = scratch[base]
            val r = if (ch > 1) scratch[base + 1] else l
            left += l.toDouble() * l
            right += r.toDouble() * r
            product += l.toDouble() * r
            val mono = 0.5f * (l + r)
            squares += mono.toDouble() * mono
            if (mono > localPeak) localPeak = mono else if (-mono > localPeak) localPeak = -mono
            ring[index] = mono
            index = (index + 1) % RING_FRAMES
        }

        sumLeft += left
        sumRight += right
        sumProduct += product
        sumSquares += squares
        peak = localPeak
        counted += frames
        written += frames
    }

    /**
     * Skips the intro, listens for a while, then publishes once.
     *
     * The warm-up is not politeness: intros are frequently nothing like the song — a lone piano in front
     * of a wall-of-sound chorus, a filtered build-up before a drop — and a profile built from one would
     * be wrong for the four minutes that follow.
     */
    private suspend fun measure() {
        delay(WARMUP_MS)
        val startedAt = written

        val window = FloatArray(FFT_SIZE)
        val real = FloatArray(FFT_SIZE)
        val imaginary = FloatArray(FFT_SIZE)
        val hann = Fft.hann(FFT_SIZE)
        val magnitudes = FloatArray(FFT_SIZE / 2)
        var previous = FloatArray(FFT_SIZE / 2)
        val onsets = ArrayList<Float>(ONSET_CAPACITY)

        var low = 0.0
        var mid = 0.0
        var high = 0.0
        var centroidWeighted = 0.0
        var centroidTotal = 0.0
        var spectra = 0
        var consumed = startedAt

        val deadline = System.nanoTime() + WINDOW_MS * 1_000_000L
        while (currentScopeActive() && System.nanoTime() < deadline) {
            if (written - consumed < HOP_FRAMES) {
                delay(TICK_MS)
                continue
            }
            consumed += HOP_FRAMES
            snapshot(window)

            for (i in 0 until FFT_SIZE) {
                real[i] = window[i] * hann[i]
                imaginary[i] = 0f
            }
            Fft.transform(real, imaginary)

            var flux = 0f
            for (bin in magnitudes.indices) {
                val magnitude = sqrt(real[bin] * real[bin] + imaginary[bin] * imaginary[bin])
                magnitudes[bin] = magnitude
                val rise = magnitude - previous[bin]
                if (rise > 0f) flux += rise
                val hz = bin.toFloat() * sampleRate / FFT_SIZE
                when {
                    hz < LOW_HZ -> low += magnitude.toDouble()
                    hz < MID_HZ -> mid += magnitude.toDouble()
                    else -> high += magnitude.toDouble()
                }
                centroidWeighted += hz.toDouble() * magnitude
                centroidTotal += magnitude.toDouble()
            }
            onsets += flux
            previous = magnitudes.copyOf()
            spectra++
        }

        if (spectra == 0 || counted == 0L) return

        val bandTotal = (low + mid + high).takeIf { it > 1e-9 } ?: return
        val framesPerSecond = sampleRate.toFloat() / HOP_FRAMES
        val tempo = TempoEstimator().estimate(onsets.toFloatArray(), framesPerSecond)

        val rms = sqrt(sumSquares / counted).toFloat()
        val leftPower = sqrt(sumLeft / counted).toFloat()
        val rightPower = sqrt(sumRight / counted).toFloat()
        val correlation = if (leftPower <= 1e-9f || rightPower <= 1e-9f) {
            1f
        } else {
            (sumProduct / counted / (leftPower.toDouble() * rightPower)).toFloat().coerceIn(-1f, 1f)
        }

        _analysis.value = SpatialAnalysis(
            tempoBpm = tempo?.bpm,
            tempoConfidence = tempo?.confidence ?: 0f,
            rmsDb = if (rms <= 1e-6f) -120f else 20f * log10(rms),
            crestFactorDb = if (rms <= 1e-6f) 0f else 20f * log10((peak / rms).coerceAtLeast(1f)),
            // Correlation runs +1 (mono) to −1 (out of phase); width is the useful half of that, so a
            // dead-centre mix reads 0 and a genuinely wide one approaches 1.
            stereoCorrelation = correlation,
            stereoWidth = ((1f - correlation) / 2f).coerceIn(0f, 1f),
            lowEnergy = (low / bandTotal).toFloat(),
            midEnergy = (mid / bandTotal).toFloat(),
            highEnergy = (high / bandTotal).toFloat(),
            spectralCentroidHz = if (centroidTotal <= 1e-9) null else (centroidWeighted / centroidTotal).toFloat(),
            onsetRate = onsets.count { it > 0f } / (WINDOW_MS / 1000f),
        )
    }

    private fun currentScopeActive(): Boolean = scope?.isActive == true
    private fun stopScope() {
        scope?.cancel()
        scope = null
    }

    /** Copies the newest [FFT_SIZE] frames out of the ring, oldest first. */
    private fun snapshot(out: FloatArray) {
        val end = (written % RING_FRAMES).toInt()
        var index = ((end - FFT_SIZE) % RING_FRAMES + RING_FRAMES) % RING_FRAMES
        for (i in 0 until FFT_SIZE) {
            out[i] = ring[index]
            index = (index + 1) % RING_FRAMES
        }
    }

    private companion object {
        const val FFT_SIZE = 2048
        const val HOP_FRAMES = 1024
        const val RING_FRAMES = 8192

        /** Past the intro, which is routinely nothing like the song. */
        const val WARMUP_MS = 6_000L
        const val WINDOW_MS = 16_000L
        const val TICK_MS = 30L
        const val ONSET_CAPACITY = 1024

        const val LOW_HZ = 250f
        const val MID_HZ = 4_000f
    }
}
