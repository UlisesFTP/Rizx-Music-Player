package fm.rizx.player.playback.spatial

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Estimates a song's tempo from an onset envelope, and says how much it believes itself.
 *
 * The classical recipe — onset envelope, autocorrelation, pick the peak — without any of the libraries
 * that implement it, because none of them belong in a music player's APK for one number.
 *
 * **The confidence matters more than the number.** The tempo only sets how fast the sound travels
 * around the listener, so being wrong is not a small error: a doubled BPM halves the orbit and the
 * effect starts to spin. Anything the estimator is not sure about is reported as unsure, and the caller
 * keeps the genre's own period instead.
 *
 * Pure Kotlin: give it an envelope and it gives back a number, so it can be tested against click tracks
 * without an audio device anywhere in sight.
 */
internal class TempoEstimator {

    data class Tempo(val bpm: Float, val confidence: Float)

    /**
     * @param envelope one value per analysis frame — how much the spectrum grew since the frame before.
     * @param framesPerSecond how many envelope values make up a second.
     */
    fun estimate(envelope: FloatArray, framesPerSecond: Float): Tempo? {
        if (envelope.size < MIN_FRAMES || framesPerSecond <= 0f) return null

        // Remove the running level so a loud section does not read as a beat, and keep only the rises:
        // an onset is energy appearing, and energy disappearing is not one.
        val flux = FloatArray(envelope.size)
        var mean = 0f
        for (v in envelope) mean += v
        mean /= envelope.size
        var energy = 0f
        for (i in envelope.indices) {
            val v = envelope[i] - mean
            flux[i] = if (v > 0f) v else 0f
            energy += flux[i] * flux[i]
        }
        if (energy <= 1e-9f) return null

        val minLag = (framesPerSecond * 60f / MAX_BPM).toInt().coerceAtLeast(1)
        val maxLag = (framesPerSecond * 60f / MIN_BPM).toInt().coerceAtMost(flux.size / 2)
        if (maxLag <= minLag) return null

        val scores = FloatArray(maxLag - minLag + 1)
        var bestLag = -1
        var best = 0f
        var total = 0f
        for (lag in minLag..maxLag) {
            var sum = 0f
            for (i in lag until flux.size) sum += flux[i] * flux[i - lag]
            // Longer lags overlap over fewer samples; without this the slowest tempo always wins.
            val score = sum / (flux.size - lag)
            scores[lag - minLag] = score
            total += score
            if (score > best) {
                best = score
                bestLag = lag
            }
        }
        if (bestLag <= 0 || best <= 0f) return null

        // **Take the fundamental, not a harmonic.** A beat every N frames also correlates at 2N, 3N and
        // 4N, and the length normalisation above nudges the taller of those to the front — which is how
        // a 180 BPM track came back as 125. If a whole fraction of the winning lag correlates nearly as
        // strongly, that is the real beat and this is one of its multiples.
        for (divisor in 4 downTo 2) {
            val candidate = bestLag / divisor
            if (candidate < minLag) continue
            val score = scores[candidate - minLag]
            if (score >= best * HARMONIC_TOLERANCE) {
                bestLag = candidate
                best = score
                break
            }
        }

        val count = scores.size

        val average = total / count
        // How far the winner stands above the crowd. A periodic signal has one tall peak; noise has a
        // field of equal bumps, and this stays near zero for it.
        val confidence = if (average <= 1e-9f) 0f else ((best / average - 1f) / PEAK_PROMINENCE).coerceIn(0f, 1f)

        val bpm = framesPerSecond * 60f / bestLag
        return Tempo(normalise(bpm), confidence)
    }

    /**
     * Folds an octave error back into the range people actually count in.
     *
     * Autocorrelation cannot tell a beat from every other beat, so a 150 BPM song is as likely to come
     * back as 75. Neither is wrong, but the orbit is calibrated in beats, so they have to mean the same
     * thing every time.
     */
    private fun normalise(bpm: Float): Float {
        var value = bpm
        while (value < PREFERRED_MIN) value *= 2f
        while (value > PREFERRED_MAX) value /= 2f
        return value
    }

    private companion object {
        const val MIN_BPM = 60f
        const val MAX_BPM = 200f

        /** Where a listener would tap along, so half- and double-time both fold to here. */
        const val PREFERRED_MIN = 70f
        const val PREFERRED_MAX = 140f

        /** Enough envelope frames to hold several bars; below this any peak is a coincidence. */
        const val MIN_FRAMES = 64

        /** How far above the average a peak must stand to count as fully confident. */
        const val PEAK_PROMINENCE = 1.5f

        /** How strongly a submultiple must correlate before it is believed to be the real beat. */
        const val HARMONIC_TOLERANCE = 0.75f
    }
}

/** Root-mean-square of a window — shared by the analyzer's level measurements. */
internal fun rmsOf(values: FloatArray, count: Int): Float {
    if (count <= 0) return 0f
    var sum = 0.0
    for (i in 0 until count) sum += values[i].toDouble() * values[i]
    return sqrt(sum / count).toFloat()
}

/** Peak magnitude of a window. */
internal fun peakOf(values: FloatArray, count: Int): Float {
    var peak = 0f
    for (i in 0 until count) {
        val v = abs(values[i])
        if (v > peak) peak = v
    }
    return peak
}
