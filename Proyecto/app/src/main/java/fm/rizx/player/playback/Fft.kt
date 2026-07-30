package fm.rizx.player.playback

import kotlin.math.cos
import kotlin.math.sin

/**
 * In-place iterative radix-2 Cooley–Tukey FFT, shared by the two things in this app that look at the
 * spectrum of the audio: the Now Playing waveform ([AudioVisualizer]) and the automatic equalizer's
 * per-song measurement ([TrackSpectrum]).
 *
 * Extracted rather than copied: it is thirty lines of index arithmetic where a transcription slip is
 * invisible until the spectrum is subtly wrong. Deliberately **unnormalised** — a full-scale tone lands
 * near `size / 2`, not 1 — so every caller must scale for itself.
 */
internal object Fft {

    /** Transforms [re]/[im] in place. Both arrays must be the same power-of-two length. */
    fun transform(re: FloatArray, im: FloatArray) {
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
                    val b = a + half
                    val vr = re[b] * cr - im[b] * ci
                    val vi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - vr; im[b] = im[a] - vi
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

    /** A Hann window of [size], precomputed by the caller once and reused per frame. */
    fun hann(size: Int) = FloatArray(size) { 0.5f * (1f - cos((2.0 * Math.PI * it / (size - 1)).toFloat())) }
}
