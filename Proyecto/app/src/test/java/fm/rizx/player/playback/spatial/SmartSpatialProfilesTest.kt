package fm.rizx.player.playback.spatial

import fm.rizx.player.domain.model.SoundGenre
import fm.rizx.player.domain.model.SpatialAnalysis
import fm.rizx.player.domain.model.SpatialAudioProfile
import fm.rizx.player.domain.usecase.SmartSpatialProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The part of the feature that decides *how* a song should move, and the safety rail underneath it.
 *
 * The clamp test is the one that matters most: everything else here is judgement that can be retuned,
 * but a profile that escapes its ranges is a delay long enough to hear as an echo, or a wet mix loud
 * enough to bury the record.
 */
class SmartSpatialProfilesTest {

    private fun analysis(
        width: Float = 0.2f,
        low: Float = 0.3f,
        crest: Float = 12f,
        onsets: Float = 3f,
        bpm: Float? = null,
        confidence: Float = 0f,
    ) = SpatialAnalysis(
        tempoBpm = bpm,
        tempoConfidence = confidence,
        stereoWidth = width,
        lowEnergy = low,
        crestFactorDb = crest,
        onsetRate = onsets,
    )

    @Test
    fun `every genre stays inside the safe ranges`() {
        for (genre in SoundGenre.entries) {
            for (measured in listOf(null, analysis(width = 0.9f, low = 0.9f), analysis(width = 0f, low = 0f))) {
                val profile = SmartSpatialProfiles.profileFor(genre, measured)
                assertEquals("$genre", profile, profile.clamped())
                assertTrue("$genre itd ${profile.maxItdMs}", profile.maxItdMs <= SpatialAudioProfile.MAX_ITD_MS)
                assertTrue("$genre wet ${profile.wetMix}", profile.wetMix <= SpatialAudioProfile.MAX_WET_MIX)
                assertTrue("$genre bass ${profile.bassCenterHz}", profile.bassCenterHz >= SpatialAudioProfile.MIN_BASS_HZ)
            }
        }
    }

    @Test
    fun `an unrecognised genre still gets a profile`() {
        val profile = SmartSpatialProfiles.profileFor(SoundGenre.UNKNOWN, null)
        assertNotNull(profile)
        assertTrue(profile.wetMix > 0f)
        assertTrue(profile.orbitPeriodSec > 0f)
    }

    @Test
    fun `music is moved enough to be worth switching on`() {
        // There is no strength control any more, so the presets themselves are the contract: if these
        // drift back down, the effect goes back to being something you have to strain to notice.
        for (genre in listOf(SoundGenre.POP, SoundGenre.ELECTRONIC, SoundGenre.ROCK, SoundGenre.HIPHOP)) {
            val profile = SmartSpatialProfiles.profileFor(genre, null)
            assertTrue("$genre wet ${profile.wetMix}", profile.wetMix >= 0.75f)
            assertTrue("$genre pan ${profile.panDepth}", profile.panDepth >= 0.85f)
            // A full turn inside ten seconds — slower than that stops reading as movement.
            assertTrue("$genre orbit ${profile.orbitPeriodSec}", profile.orbitPeriodSec <= 10f)
            assertTrue("$genre reverb ${profile.reverbWet}", profile.reverbWet >= 0.3f)
        }
    }

    @Test
    fun `a bass-heavy master protects its low end`() {
        val plain = SmartSpatialProfiles.profileFor(SoundGenre.HIPHOP, analysis(low = 0.2f))
        val heavy = SmartSpatialProfiles.profileFor(SoundGenre.HIPHOP, analysis(low = 0.9f))

        // The crossover climbs, so more of the weight stays centred, and the movement calms down.
        assertTrue(heavy.bassCenterHz > plain.bassCenterHz)
        assertTrue(heavy.panDepth < plain.panDepth)
        assertTrue(heavy.reverbWet < plain.reverbWet)
    }

    @Test
    fun `an already-wide mix is left more of itself`() {
        val narrow = SmartSpatialProfiles.profileFor(SoundGenre.ROCK, analysis(width = 0.1f))
        val wide = SmartSpatialProfiles.profileFor(SoundGenre.ROCK, analysis(width = 0.95f))

        assertTrue(wide.panDepth < narrow.panDepth)
        assertTrue(wide.wetMix <= narrow.wetMix)
        assertTrue(wide.sidePreservation > narrow.sidePreservation)
    }

    @Test
    fun `a dense modern master gets less ambience`() {
        val open = SmartSpatialProfiles.profileFor(SoundGenre.POP, analysis(crest = 16f))
        val squashed = SmartSpatialProfiles.profileFor(SoundGenre.POP, analysis(crest = 5f))

        assertTrue(squashed.reverbWet < open.reverbWet)
    }

    @Test
    fun `a confident tempo sets the orbit and an unsure one does not`() {
        val baseline = SmartSpatialProfiles.profileFor(SoundGenre.POP, analysis())
        val confident = SmartSpatialProfiles.profileFor(SoundGenre.POP, analysis(bpm = 128f, confidence = 0.9f))
        val unsure = SmartSpatialProfiles.profileFor(SoundGenre.POP, analysis(bpm = 128f, confidence = 0.1f))

        // Pop orbits every 8 beats: 8 × 60 / 128 = 3.75 s — about two bars, which is fast enough to be
        // heard as travel rather than as width.
        assertEquals(3.75f, confident.orbitPeriodSec, 0.2f)
        // A guess must not be allowed to spin the effect up — a wrong BPM is worse than no BPM.
        assertEquals(baseline.orbitPeriodSec, unsure.orbitPeriodSec, 0.001f)
    }

    @Test
    fun `speech is left almost alone`() {
        val spoken = SmartSpatialProfiles.profileFor(SoundGenre.SPOKEN, null)
        val pop = SmartSpatialProfiles.profileFor(SoundGenre.POP, null)

        // A podcast is the one thing here that must stay in front of the listener and be understood.
        assertTrue(spoken.panDepth < pop.panDepth)
        assertTrue(spoken.wetMix < pop.wetMix)
    }

    @Test
    fun `the label says whether the recording was actually measured`() {
        val guessed = SmartSpatialProfiles.profileFor(SoundGenre.JAZZ, null)
        val measured = SmartSpatialProfiles.profileFor(SoundGenre.JAZZ, analysis())

        assertEquals("jazz", guessed.label)
        assertEquals("jazz+measured", measured.label)
    }
}

/** Tempo estimation, against click tracks it should get right and noise it should refuse. */
class TempoEstimatorTest {

    private val estimator = TempoEstimator()
    private val framesPerSecond = 46.875f // 48 kHz in 1024-frame hops

    /**
     * An onset envelope with a spike every beat, as a steady click track would produce.
     *
     * The beat positions are accumulated in floating point rather than by an integer modulo: at 180 BPM
     * the period is 15.6 frames, and truncating it to 15 would be testing a 187 BPM track.
     */
    private fun clicks(bpm: Float, seconds: Float = 20f): FloatArray {
        val frames = (framesPerSecond * seconds).toInt()
        val period = framesPerSecond * 60f / bpm
        val envelope = FloatArray(frames) { 0.02f }
        var at = 0f
        while (at < frames) {
            envelope[at.toInt()] = 1f
            at += period
        }
        return envelope
    }

    @Test
    fun `it reads a steady click track, folded into the range people count in`() {
        // 60 and 180 come back doubled and halved respectively: autocorrelation cannot tell a beat from
        // every other beat, so the answer is folded to one canonical octave.
        for ((played, expected) in listOf(60f to 120f, 90f to 90f, 120f to 120f, 150f to 75f, 180f to 90f)) {
            val tempo = estimator.estimate(clicks(played), framesPerSecond)
            assertNotNull("no tempo for $played BPM", tempo)
            assertEquals("$played BPM", expected, tempo!!.bpm, expected * 0.06f)
            assertTrue("$played BPM was unconvincing (${tempo.confidence})", tempo.confidence > 0.3f)
        }
    }

    @Test
    fun `noise does not get to set the tempo`() {
        var seed = 12345L
        val noise = FloatArray(1000) {
            seed = (seed * 25214903917L + 11L) and 0xFFFFFFFFFFFFL
            ((seed shr 20).toInt() and 0xFF) / 255f
        }
        val tempo = estimator.estimate(noise, framesPerSecond)
        // Either nothing, or something it openly does not believe — both leave the genre's orbit alone.
        assertTrue(tempo == null || tempo.confidence < 0.5f)
    }

    @Test
    fun `silence and short clips are refused outright`() {
        assertEquals(null, estimator.estimate(FloatArray(600), framesPerSecond))
        assertEquals(null, estimator.estimate(clicks(120f, seconds = 0.5f), framesPerSecond))
    }
}

