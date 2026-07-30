package fm.rizx.player.domain.usecase

import fm.rizx.player.domain.model.EqBandRange
import fm.rizx.player.domain.model.SoundGenre
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AutoEqCurvesTest {

    /** The five bands an AOSP device reports (the emulator, and most phones). */
    private val fiveBand = listOf(
        EqBandRange(0, centerHz = 60, lowHz = 0, highHz = 120),
        EqBandRange(1, centerHz = 230, lowHz = 120, highHz = 460),
        EqBandRange(2, centerHz = 910, lowHz = 460, highHz = 1_800),
        EqBandRange(3, centerHz = 3_600, lowHz = 1_800, highHz = 7_000),
        EqBandRange(4, centerHz = 14_000, lowHz = 7_000, highHz = 20_000),
    )

    /** A ten-band device, one band per anchor. */
    private val tenBand = AutoEqCurves.ANCHORS_HZ.mapIndexed { i, hz ->
        EqBandRange(i, centerHz = hz, lowHz = (hz * 0.71).toInt(), highHz = (hz * 1.41).toInt())
    }

    private fun curve(genre: SoundGenre, bands: List<EqBandRange> = fiveBand) =
        AutoEqCurves.toBandsDb(AutoEqCurves.anchorCurve(genre), bands)

    @Test
    fun `an unknown genre shapes nothing at all`() {
        // No genre means the app knows nothing about the song, and silence is the honest answer.
        curve(SoundGenre.UNKNOWN).forEach { assertEquals(0f, it, 0.001f) }
    }

    @Test
    fun `every genre keeps the overall level where it was`() {
        SoundGenre.entries.forEach { genre ->
            val mean = curve(genre).sum() / fiveBand.size
            // Mean-zero, except where the boost trim has deliberately pulled the whole curve down.
            assertTrue("$genre mean $mean should not raise the level", mean <= 0.001f)
        }
    }

    @Test
    fun `no genre may boost a band past the cap, on any device`() {
        listOf(fiveBand, tenBand).forEach { bands ->
            SoundGenre.entries.forEach { genre ->
                val peak = curve(genre, bands).max()
                assertTrue("$genre peaks at $peak dB", peak <= AutoEqCurves.MAX_BOOST_DB + 0.001f)
            }
        }
    }

    @Test
    fun `reggaeton lifts the bass and clears the low mid`() {
        val c = curve(SoundGenre.REGGAETON)
        assertTrue("60 Hz should be lifted: $c", c[0] > 0f)
        assertTrue("230 Hz should be cut: $c", c[1] < 0f)
        assertTrue("air above the mud: $c", c[4] > c[1])
    }

    @Test
    fun `metal pushes presence over the low mid`() {
        val c = curve(SoundGenre.METAL)
        assertTrue("3.6 kHz over 910 Hz: $c", c[3] > c[2])
        assertTrue("910 Hz is where metal gets scooped: $c", c[2] < 0f)
    }

    @Test
    fun `spoken audio cuts the bottom and brings the words forward`() {
        val c = curve(SoundGenre.SPOKEN)
        assertTrue("rumble out: $c", c[0] < 0f)
        assertTrue("speech band over rumble: $c", c[2] > c[0] && c[3] > c[0])
    }

    @Test
    fun `a band is averaged over its range, not sampled at its center`() {
        // Same center, different widths: the wide band must be pulled towards its neighbours, which is the
        // whole reason the resampler reads getBandFreqRange instead of getCenterFreq alone.
        val narrow = EqBandRange(0, centerHz = 250, lowHz = 240, highHz = 260)
        val wide = EqBandRange(1, centerHz = 250, lowHz = 62, highHz = 1_000)
        val values = AutoEqCurves.toBandsDb(AutoEqCurves.anchorCurve(SoundGenre.REGGAETON), listOf(narrow, wide))

        assertTrue("the wide band sees the bass either side: $values", values[1] > values[0])
    }

    @Test
    fun `a device reporting an unusable band range falls back to its center`() {
        val broken = EqBandRange(0, centerHz = 60, lowHz = 0, highHz = 0)
        val sane = EqBandRange(1, centerHz = 60, lowHz = 55, highHz = 65)
        val values = AutoEqCurves.toBandsDb(AutoEqCurves.anchorCurve(SoundGenre.HIPHOP), listOf(broken, sane))

        assertEquals("both describe 60 Hz", values[0], values[1], 0.2f)
    }

    @Test
    fun `millibels stay inside whatever range the device allows`() {
        val db = curve(SoundGenre.ELECTRONIC)
        val mb = AutoEqCurves.toMillibels(db, minMillibel = -300, maxMillibel = 300)

        assertTrue(mb.all { it in -300..300 })
        // And an ordinary ±1500 mB device gets the real values, rounded.
        val wide = AutoEqCurves.toMillibels(db, minMillibel = -1_500, maxMillibel = 1_500)
        wide.forEachIndexed { i, value -> assertEquals(db[i] * 100f, value.toFloat(), 1f) }
    }

    @Test
    fun `a boomy song gets less of its genre's bass, a dull one more air`() {
        val genre = AutoEqCurves.anchorCurve(SoundGenre.REGGAETON)
        // Measured: 10 dB more bass than music usually has, 10 dB less treble.
        val boomy = FloatArray(genre.size) { i -> AutoEqCurves.MUSIC_REFERENCE_DB[i] + if (i < 2) 10f else -10f }
        val adapted = AutoEqCurves.adapt(SoundGenre.REGGAETON, boomy)

        assertTrue("bass pulled back: ${adapted.toList()}", adapted[0] < genre[0])
        assertTrue("treble lifted: ${adapted.toList()}", adapted[9] > genre[9])
        adapted.forEachIndexed { i, value ->
            assertTrue(
                "correction $i is bounded",
                abs(value - genre[i]) <= AutoEqCurves.ADAPT_MAX_DB + 0.001f,
            )
        }
    }

    @Test
    fun `families whose spectrum is legitimately different opt out of the correction`() {
        // A string quartet really does have less low end than a pop master; "correcting" that would add
        // bass nobody recorded.
        val measured = FloatArray(AutoEqCurves.ANCHORS_HZ.size) { -20f }
        listOf(SoundGenre.CLASSICAL, SoundGenre.SPOKEN).forEach { genre ->
            assertTrue(
                "$genre must keep its own balance",
                AutoEqCurves.adapt(genre, measured).contentEquals(AutoEqCurves.anchorCurve(genre)),
            )
            assertTrue(!AutoEqCurves.decide(genre, null, measured, fiveBand).adapted)
        }
        // Pop does take it.
        assertNotEquals(
            AutoEqCurves.anchorCurve(SoundGenre.POP).toList(),
            AutoEqCurves.adapt(SoundGenre.POP, measured).toList(),
        )
    }

    @Test
    fun `no measurement, or a malformed one, leaves the genre curve alone`() {
        val pop = AutoEqCurves.anchorCurve(SoundGenre.POP)
        assertTrue(AutoEqCurves.adapt(SoundGenre.POP, null).contentEquals(pop))
        assertTrue(AutoEqCurves.adapt(SoundGenre.POP, FloatArray(3)).contentEquals(pop))

        val decision = AutoEqCurves.decide(SoundGenre.POP, "Pop", FloatArray(3), fiveBand)
        assertTrue("a rejected measurement must not be reported as adapted", !decision.adapted)
    }

    @Test
    fun `the decision carries the catalogue's own wording and one value per band`() {
        val decision = AutoEqCurves.decide(SoundGenre.LATIN_REGIONAL, "Música Mexicana", null, fiveBand)

        assertEquals("Música Mexicana", decision.label)
        assertEquals(SoundGenre.LATIN_REGIONAL, decision.genre)
        assertEquals(fiveBand.size, decision.curveDb.size)
    }

    @Test
    fun `a device with no bands is not a crash`() {
        assertEquals(emptyList<Float>(), AutoEqCurves.toBandsDb(AutoEqCurves.anchorCurve(SoundGenre.POP), emptyList()))
        assertEquals(emptyList<Int>(), AutoEqCurves.toMillibels(emptyList(), -1_500, 1_500))
    }

    @Test
    fun `a ten-band device follows the anchor shape it was written against`() {
        val c = curve(SoundGenre.REGGAETON, tenBand)

        assertTrue("62 Hz over 500 Hz: $c", c[1] > c[4])
        assertTrue("16 kHz over 1 kHz: $c", c[9] > c[5])
    }
}
