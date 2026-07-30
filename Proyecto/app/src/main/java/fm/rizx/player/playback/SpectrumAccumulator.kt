package fm.rizx.player.playback

import fm.rizx.player.domain.usecase.AutoEqCurves
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * The long-window average spectrum of whatever is playing, per [AutoEqCurves.ANCHORS_HZ] anchor.
 *
 * This is the measurement that makes the automatic equalizer *per song* rather than per genre: it says
 * whether this particular recording is boomy, dull or ordinary for music, and the curve is nudged from
 * there ([AutoEqCurves.adapt]).
 *
 * Two decisions worth knowing:
 *
 * - **It averages, it doesn't watch.** A single frame of a song is meaningless — a bar of vocals alone
 *   would read as "no bass at all". Only after [MIN_FRAMES] non-silent frames (several seconds of music)
 *   does [measurement] answer at all, and what it answers is the mean over all of them.
 * - **Silence is skipped, not averaged in.** Intros, gaps and the fade of a crossfade would otherwise
 *   drag the whole average down and, worse, would do it unevenly across the spectrum.
 *
 * Pure Kotlin — no Android, no Media3 — so the whole measurement is unit-testable; [TrackSpectrum] is the
 * thin shell that feeds it real audio.
 */
internal class SpectrumAccumulator(
    private val fftSize: Int = FFT_SIZE,
    private val anchorsHz: IntArray = AutoEqCurves.ANCHORS_HZ,
) {

    /** Sample rate of the audio being fed in; set from the audio pipeline's `flush`. */
    var sampleRateHz: Int = DEFAULT_SAMPLE_RATE
        set(value) {
            if (value > 0 && value != field) {
                field = value
                mapBins()
            }
        }

    /** Non-silent frames counted since the last [reset]. */
    var frames: Int = 0
        private set

    private val re = FloatArray(fftSize)
    private val im = FloatArray(fftSize)
    private val window = Fft.hann(fftSize)

    /** Running sum of linear power per anchor, and the bin span each anchor covers. */
    private val power = DoubleArray(anchorsHz.size)
    private val binLo = IntArray(anchorsHz.size)
    private val binHi = IntArray(anchorsHz.size)

    init {
        mapBins()
    }

    fun reset() {
        frames = 0
        power.fill(0.0)
    }

    /**
     * Folds one window of mono samples into the average. Returns false when the frame was silence (and
     * therefore not counted). [samples] must be [fftSize] long.
     */
    fun addFrame(samples: FloatArray): Boolean {
        if (samples.size != fftSize) return false
        var sumSquares = 0.0
        for (i in 0 until fftSize) {
            val s = samples[i]
            sumSquares += (s * s).toDouble()
            re[i] = s * window[i]
            im[i] = 0f
        }
        if (sqrt(sumSquares / fftSize) < SILENCE_RMS) return false

        Fft.transform(re, im)
        for (a in anchorsHz.indices) {
            var sum = 0.0
            for (k in binLo[a]..binHi[a]) {
                sum += (re[k] * re[k] + im[k] * im[k]).toDouble()
            }
            power[a] += sum / (binHi[a] - binLo[a] + 1)
        }
        frames++
        return true
    }

    /**
     * The average level per anchor in dB, shifted to a mean of zero — so it describes the song's *balance*
     * and not how loud it happens to be. Null until enough non-silent frames have landed: no measurement
     * is a reason to leave the genre curve alone, never to guess at one.
     */
    fun measurement(): FloatArray? {
        if (frames < MIN_FRAMES) return null
        val db = FloatArray(anchorsHz.size) { a ->
            (10.0 * log10(power[a] / frames + FLOOR_POWER)).toFloat()
        }
        val mean = db.sum() / db.size
        return FloatArray(db.size) { db[it] - mean }
    }

    /**
     * Each anchor takes the bins within a half-octave either side — the same span the ear groups into one
     * "band". The lowest anchor reaches down to 20 Hz instead, because below ~44 Hz a 2048-point FFT has
     * barely two bins to offer and cutting the span there would measure almost nothing.
     */
    private fun mapBins() {
        val binWidth = sampleRateHz.toDouble() / fftSize
        val maxBin = fftSize / 2 - 1
        for (a in anchorsHz.indices) {
            val low = if (a == 0) MIN_HZ.toDouble() else anchorsHz[a] / HALF_OCTAVE
            val high = anchorsHz[a] * HALF_OCTAVE
            val lo = (low / binWidth).toInt().coerceIn(1, maxBin)
            val hi = (high / binWidth).toInt().coerceIn(lo, maxBin)
            binLo[a] = lo
            binHi[a] = hi
        }
    }

    companion object {
        /**
         * 2048 points: 21 Hz per bin at 44.1 kHz, which is the coarsest that still resolves the bottom
         * octave into more than one bin.
         */
        const val FFT_SIZE = 2048

        /**
         * Frames required before the measurement is trusted. At ~25 frames a second this is roughly fourteen
         * seconds of actual music — long enough that one verse, one breakdown or one loud chorus cannot
         * define the song, short enough that the refinement still lands while the listener is on the track.
         * The owner explicitly accepted a slower settle in exchange for getting it right.
         */
        const val MIN_FRAMES = 350

        private const val DEFAULT_SAMPLE_RATE = 44_100

        /** ≈ −46 dBFS. Below this a frame is a gap, not music. */
        private const val SILENCE_RMS = 0.005

        /** Keeps a genuinely empty band out of `log10(0)`. */
        private const val FLOOR_POWER = 1e-12

        /** √2 — half an octave. */
        private const val HALF_OCTAVE = 1.414

        private const val MIN_HZ = 20
    }
}
