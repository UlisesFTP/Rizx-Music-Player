package fm.rizx.player.domain.usecase

import fm.rizx.player.domain.model.AutoEqDecision
import fm.rizx.player.domain.model.EqBandRange
import fm.rizx.player.domain.model.SoundGenre
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * The whole sound of the automatic equalizer, as pure arithmetic: genre → curve → this device's bands.
 *
 * Three ideas hold it together.
 *
 * **1. Curves are written against fixed anchors, not against the device.** [ANCHORS_HZ] is the ISO
 * octave ladder; a genre is one row of *relative* weights there. Devices disagree wildly about how many
 * equalizer bands they expose (five on AOSP, ten on some phones) and at which frequencies, so a table
 * written in device bands would be a table written for one phone.
 *
 * **2. A band is a range, not a point.** [toBandsDb] averages the interpolated curve over each band's
 * real edges. On a 5-band device the second band spans about 120–450 Hz: answering for that whole octave
 * with the value at its 230 Hz center is how a "cut the low-mid mud" intent turns into a notch that
 * misses the mud.
 *
 * **3. Nothing may get louder overall.** Every curve is shifted to a **mean of zero** and then trimmed
 * so no single band is boosted more than [MAX_BOOST_DB]. Mean-zero keeps the perceived volume where it
 * was — otherwise "equalized" would just mean "louder", which always sounds better and is always a lie —
 * and the trim makes digital clipping arithmetically impossible on a loud master. Shaping still has its
 * full range, because shape is *relative*: a −5 dB cut moves the balance exactly as much as a +5 dB
 * boost, without spending any headroom.
 */
object AutoEqCurves {

    /** ISO octave centers, the frequencies the genre table is written against. */
    val ANCHORS_HZ = intArrayOf(31, 62, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000)

    /**
     * How many dB a weight of 1.0 is worth — the owner's "Marcado" choice. One constant is the whole
     * intensity knob: halving it halves every genre's character.
     */
    const val INTENSITY_DB = 6f

    /**
     * The most any single band may be *boosted* after mean-zeroing. Kept to three quarters of the
     * intensity: enough that a lift is audible, low enough that no master can be pushed into clipping.
     */
    const val MAX_BOOST_DB = 4.5f

    /** How much of the song's own deviation from [MUSIC_REFERENCE_DB] is corrected. */
    const val ADAPT = 0.35f

    /** Cap on that correction, so one strange measurement can never rewrite the genre's intent. */
    const val ADAPT_MAX_DB = 3f

    /**
     * The average spectral tilt of mixed music: bass-heavy, treble-light, falling steadily above ~500 Hz.
     *
     * It is a **reference, not a target** — the curve never tries to make a song look like this. It is
     * the yardstick that says whether *this* recording is unusually boomy or unusually dull for music,
     * which is the only honest basis for a per-song correction. Mean-zeroed at use, so absolute level
     * (i.e. how loud the song is) cannot leak into the comparison.
     */
    val MUSIC_REFERENCE_DB = floatArrayOf(6f, 6f, 4f, 2f, 0f, -1f, -3f, -5f, -7f, -9f)

    /**
     * Genre → relative weights at [ANCHORS_HZ], in −1..+1, multiplied by [INTENSITY_DB].
     *
     * Read a row as an intention rather than a measurement. The recurring negative around 250–500 Hz is
     * the low-mid congestion that makes a dense mix sound like a cardboard box; the recurring positive at
     * 4–16 kHz is what people call air. [SoundGenre.UNKNOWN] is deliberately silent: with no genre, the
     * app has nothing to say about the song, and saying nothing is the correct answer.
     */
    private val GENRE_SHAPES: Map<SoundGenre, FloatArray> = mapOf(
        //                                31    62   125   250   500    1k    2k    4k    8k   16k
        SoundGenre.REGGAETON to floatArrayOf(1.0f, 1.0f, 0.5f, -.6f, -.4f, -.3f, 0.1f, 0.4f, 0.6f, 0.7f),
        SoundGenre.HIPHOP to floatArrayOf(0.7f, 0.9f, 0.4f, -.5f, -.3f, 0.0f, 0.2f, 0.4f, 0.4f, 0.3f),
        SoundGenre.ELECTRONIC to floatArrayOf(0.8f, 0.9f, 0.3f, -.4f, -.4f, -.2f, 0.1f, 0.4f, 0.6f, 0.7f),
        SoundGenre.REGGAE_DANCEHALL to floatArrayOf(0.8f, 0.9f, 0.4f, -.3f, -.3f, -.1f, 0.1f, 0.2f, 0.2f, 0.2f),
        SoundGenre.POP to floatArrayOf(0.3f, 0.6f, 0.3f, -.3f, -.2f, 0.0f, 0.2f, 0.4f, 0.5f, 0.4f),
        SoundGenre.ROCK to floatArrayOf(0.1f, 0.5f, 0.2f, -.4f, -.2f, 0.1f, 0.4f, 0.5f, 0.3f, 0.2f),
        SoundGenre.METAL to floatArrayOf(0.0f, 0.4f, 0.1f, -.5f, -.5f, -.2f, 0.3f, 0.6f, 0.4f, 0.2f),
        SoundGenre.INDIE_ALT to floatArrayOf(0.2f, 0.4f, 0.2f, -.2f, -.1f, 0.0f, 0.1f, 0.3f, 0.4f, 0.4f),
        // Banda and corridos live on brass and bajo sexto: clarity in the 2–4 kHz range, and no sub lift
        // that would only muddy an acoustic bass.
        SoundGenre.LATIN_REGIONAL to floatArrayOf(0.1f, 0.4f, 0.4f, -.2f, -.3f, -.1f, 0.3f, 0.4f, 0.3f, 0.2f),
        SoundGenre.LATIN_TROPICAL to floatArrayOf(0.2f, 0.5f, 0.3f, -.3f, -.2f, 0.0f, 0.2f, 0.4f, 0.5f, 0.4f),
        SoundGenre.RNB_SOUL to floatArrayOf(0.4f, 0.6f, 0.4f, -.1f, -.1f, 0.1f, 0.2f, 0.3f, 0.3f, 0.3f),
        // Idol pop is already mastered bright; the dip at 2–4 kHz keeps that brightness from turning harsh.
        SoundGenre.KPOP_JPOP to floatArrayOf(0.3f, 0.6f, 0.3f, -.3f, -.2f, 0.0f, 0.1f, 0.2f, 0.5f, 0.5f),
        SoundGenre.SOUNDTRACK to floatArrayOf(0.5f, 0.6f, 0.2f, -.2f, -.2f, 0.0f, 0.1f, 0.2f, 0.4f, 0.4f),
        SoundGenre.ACOUSTIC_FOLK to floatArrayOf(0.0f, 0.2f, 0.3f, -.2f, -.1f, 0.1f, 0.2f, 0.3f, 0.3f, 0.3f),
        SoundGenre.COUNTRY to floatArrayOf(0.1f, 0.3f, 0.2f, -.2f, 0.0f, 0.1f, 0.3f, 0.4f, 0.3f, 0.2f),
        SoundGenre.JAZZ to floatArrayOf(0.2f, 0.3f, 0.2f, 0.0f, 0.0f, 0.1f, 0.1f, 0.2f, 0.3f, 0.3f),
        SoundGenre.CLASSICAL to floatArrayOf(0.2f, 0.2f, 0.1f, 0.0f, 0.0f, 0.0f, 0.0f, 0.1f, 0.2f, 0.3f),
        // Lo-fi is *made* of tape hiss and vinyl noise, so the top end is left alone rather than lifted.
        SoundGenre.LOFI_CHILL to floatArrayOf(0.4f, 0.5f, 0.3f, 0.0f, -.1f, 0.0f, 0.0f, 0.1f, 0.0f, -.1f),
        // Speech: rumble out, 1–4 kHz forward. The only row that cuts the bottom, because the only one
        // whose subject is words rather than music.
        SoundGenre.SPOKEN to floatArrayOf(-.8f, -.6f, -.2f, 0.0f, 0.2f, 0.4f, 0.5f, 0.4f, 0.0f, -.2f),
        SoundGenre.UNKNOWN to FloatArray(ANCHORS_HZ.size),
    )

    /**
     * How much per-song correction each family accepts.
     *
     * [MUSIC_REFERENCE_DB] describes *mainstream mixed music*, so it is the wrong yardstick for families
     * whose spectrum is legitimately different: a string quartet genuinely has less low end than a pop
     * master, and "correcting" that would add bass nobody put there. Those families keep their own
     * balance; everything else takes the full correction.
     */
    private val ADAPT_WEIGHTS: Map<SoundGenre, Float> = mapOf(
        SoundGenre.CLASSICAL to 0f,
        SoundGenre.SPOKEN to 0f,
        SoundGenre.JAZZ to 0.4f,
        SoundGenre.ACOUSTIC_FOLK to 0.5f,
        SoundGenre.LOFI_CHILL to 0.5f,
    )

    /** The genre's own curve at [ANCHORS_HZ], in dB. */
    fun anchorCurve(genre: SoundGenre): FloatArray {
        val shape = GENRE_SHAPES[genre] ?: GENRE_SHAPES.getValue(SoundGenre.UNKNOWN)
        return FloatArray(shape.size) { shape[it] * INTENSITY_DB }
    }

    /**
     * The genre curve refined by what the song actually sounds like.
     *
     * [measuredDb] is the song's average level per anchor (any absolute scale — it is mean-zeroed here).
     * A song louder than the reference at some anchor gets a negative correction there and vice versa, so
     * an already-boomy track receives less of its genre's bass lift and a dull one a little more air.
     * `null` (or a family that opts out) returns the genre curve untouched: no measurement is a reason to
     * do nothing, never a reason to guess.
     */
    fun adapt(genre: SoundGenre, measuredDb: FloatArray?): FloatArray {
        val base = anchorCurve(genre)
        val weight = ADAPT_WEIGHTS[genre] ?: 1f
        if (measuredDb == null || measuredDb.size != base.size || weight <= 0f) return base
        val measured = meanZero(measuredDb)
        val reference = meanZero(MUSIC_REFERENCE_DB)
        return FloatArray(base.size) { i ->
            val correction = ADAPT * weight * (reference[i] - measured[i])
            base[i] + correction.coerceIn(-ADAPT_MAX_DB, ADAPT_MAX_DB)
        }
    }

    /**
     * An anchor curve resampled onto this device's [bands], mean-zeroed, then boost-trimmed.
     *
     * Each band takes the **average** of the curve across the frequencies it actually covers (see the
     * class note). A band whose reported range is unusable falls back to its center frequency.
     */
    fun toBandsDb(anchorDb: FloatArray, bands: List<EqBandRange>): List<Float> {
        if (bands.isEmpty()) return emptyList()
        val raw = bands.map { band ->
            val low = band.lowHz.coerceAtLeast(MIN_HZ)
            val high = band.highHz.coerceAtMost(MAX_HZ)
            if (high > low) averageOver(anchorDb, low, high) else valueAt(anchorDb, band.centerHz.toFloat())
        }
        return trimBoost(meanZero(raw.toFloatArray()).toList())
    }

    /** Band levels in millibels for the device's `bandLevelRange`. */
    fun toMillibels(bandDb: List<Float>, minMillibel: Int, maxMillibel: Int): List<Int> =
        bandDb.map { (it * 100f).roundToInt().coerceIn(minMillibel, maxMillibel) }

    /**
     * Everything at once: the decision the runtime applies. [label] is the catalogue's own genre wording,
     * carried through for display.
     */
    fun decide(
        genre: SoundGenre,
        label: String?,
        measuredDb: FloatArray?,
        bands: List<EqBandRange>,
    ): AutoEqDecision {
        // Exactly the conditions [adapt] itself acts on, so the flag can't claim a refinement that the
        // curve didn't get (a wrong-sized measurement, or a family that opts out).
        val adapted = measuredDb != null &&
            measuredDb.size == ANCHORS_HZ.size &&
            (ADAPT_WEIGHTS[genre] ?: 1f) > 0f
        return AutoEqDecision(
            genre = genre,
            label = label,
            curveDb = toBandsDb(adapt(genre, measuredDb), bands),
            adapted = adapted,
        )
    }

    // ---- Internals ----------------------------------------------------------------------------------

    /** The curve at an arbitrary frequency: linear between anchors **in log frequency**, flat outside. */
    private fun valueAt(anchorDb: FloatArray, hz: Float): Float {
        val f = hz.coerceIn(MIN_HZ.toFloat(), MAX_HZ.toFloat())
        if (f <= ANCHORS_HZ.first()) return anchorDb.first()
        if (f >= ANCHORS_HZ.last()) return anchorDb.last()
        val upper = ANCHORS_HZ.indexOfFirst { it >= f }
        val lower = upper - 1
        val span = log10(ANCHORS_HZ[upper].toDouble()) - log10(ANCHORS_HZ[lower].toDouble())
        val t = ((log10(f.toDouble()) - log10(ANCHORS_HZ[lower].toDouble())) / span).toFloat()
        return anchorDb[lower] + (anchorDb[upper] - anchorDb[lower]) * t
    }

    /** Mean of the curve over [lowHz]..[highHz], sampled log-spaced (equal weight per octave). */
    private fun averageOver(anchorDb: FloatArray, lowHz: Int, highHz: Int): Float {
        val logLow = log10(lowHz.toDouble())
        val logHigh = log10(highHz.toDouble())
        var sum = 0f
        for (i in 0 until BAND_SAMPLES) {
            val t = (i + 0.5) / BAND_SAMPLES
            sum += valueAt(anchorDb, Math.pow(10.0, logLow + (logHigh - logLow) * t).toFloat())
        }
        return sum / BAND_SAMPLES
    }

    /** The curve shifted so its mean is 0 dB — same shape, no change in overall level. */
    private fun meanZero(values: FloatArray): FloatArray {
        if (values.isEmpty()) return values
        val mean = values.sum() / values.size
        return FloatArray(values.size) { values[it] - mean }
    }

    /** Shifts the whole curve down until nothing is boosted more than [MAX_BOOST_DB]. */
    private fun trimBoost(values: List<Float>): List<Float> {
        val peak = values.maxOrNull() ?: return values
        val excess = peak - MAX_BOOST_DB
        return if (excess > 0f) values.map { it - excess } else values
    }

    /** Frequencies the curve is defined over. Devices report band edges of 0 Hz and 24 kHz+; both clamp. */
    private const val MIN_HZ = 20
    private const val MAX_HZ = 20_000

    /** Samples per band. Five is plenty for a curve this smooth and keeps the whole pass free. */
    private const val BAND_SAMPLES = 5
}
