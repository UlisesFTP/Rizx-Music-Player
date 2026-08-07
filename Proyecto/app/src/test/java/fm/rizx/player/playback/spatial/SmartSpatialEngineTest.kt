package fm.rizx.player.playback.spatial

import fm.rizx.player.domain.model.SpatialAudioProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The spatializer against every way it could quietly ruin somebody's music.
 *
 * A DSP bug does not throw. It ships, and the songs just sound slightly wrong — a decibel quieter, a
 * touch duller, a vocal that thins out on a mono speaker — and nobody can point at what changed. So
 * these tests assert the *properties* the effect must never violate rather than sampling the output and
 * hoping it looks sensible.
 *
 * Everything is synthetic: tones, an impulse, seeded noise. No commercial audio enters the repository.
 */
class SmartSpatialEngineTest {

    private val rate = 48_000

    private fun engine(profile: SpatialAudioProfile = SmartSpatialEngine.DEFAULT_PROFILE, on: Boolean = true) =
        SmartSpatialEngine().apply {
            configure(rate)
            setProfile(profile)
            setEnabled(on)
        }

    // -- passthrough and safety -----------------------------------------------------------------

    @Test
    fun `switched off it is bit-exact passthrough, even for a hot master`() {
        val input = noise(4096, amplitude = 0.99f)
        val frames = input.copyOf()

        SmartSpatialEngine().apply { configure(rate) }.process(frames, 2048, 0L)

        // Not "close enough": identical. A limiter left running with nothing to limit would shave a
        // decibel off a loud record while the effect was switched off.
        assertTrue(input.contentEquals(frames))
    }

    @Test
    fun `it reports itself silent so the caller can bypass entirely`() {
        val idle = SmartSpatialEngine().apply { configure(rate) }
        assertTrue(idle.silent)

        val running = engine()
        running.process(FloatArray(2048), 1024, 0L)
        assertTrue(!running.silent)
    }

    @Test
    fun `the output is never longer, shorter, NaN or out of range`() {
        val engine = engine()
        val frames = noise(8192, amplitude = 0.95f)
        val frameCount = 4096

        engine.process(frames, frameCount, 0L)

        for (i in 0 until frameCount * 2) {
            assertTrue("sample $i is ${frames[i]}", frames[i].isFinite())
            assertTrue("sample $i is ${frames[i]}", abs(frames[i]) <= 1f)
        }
        // Anything past frameCount must be untouched — the engine may not write outside what it was given.
        for (i in frameCount * 2 until frames.size) assertEquals(0f, frames[i], 0f)
    }

    @Test
    fun `a loud correlated source stays under the ceiling`() {
        val engine = engine(SmartSpatialEngine.DEFAULT_PROFILE.copy(wetMix = 0.38f))
        val frames = tone(1_000.0, seconds = 2.0, amplitude = 0.99f)

        engine.process(frames, frames.size / 2, 0L)

        assertTrue(frames.all { abs(it) <= 1f })
    }

    @Test
    fun `the same input twice gives the same output`() {
        val a = noise(4096).also { engine().process(it, 2048, 0L) }
        val b = noise(4096).also { engine().process(it, 2048, 0L) }
        assertTrue(a.contentEquals(b))
    }

    @Test
    fun `splitting the audio into smaller buffers changes nothing audible`() {
        // Media3 does not promise a constant buffer size, so the effect must not depend on one.
        // Both engines are warmed first: the profile glides once per block, so comparing a cold engine
        // fed one big block against a cold engine fed thirty-two small ones would be measuring the
        // glide's step count rather than the DSP.
        val warm = { e: SmartSpatialEngine -> repeat(200) { e.process(FloatArray(512), 256, it * 5_333L) } }

        val whole = noise(16_384)
        val split = whole.copyOf()

        engine().also(warm).process(whole, 8192, 0L)

        val piecemeal = engine().also(warm)
        var offset = 0
        while (offset < 8192) {
            val chunk = minOf(256, 8192 - offset)
            val slice = split.copyOfRange(offset * 2, (offset + chunk) * 2)
            piecemeal.process(slice, chunk, offset * 1_000_000L / rate)
            slice.copyInto(split, offset * 2)
            offset += chunk
        }

        // The residue is the orbit's per-block linear interpolation landing on different breakpoints.
        // It scales with how fast the sound travels, and at −45 dB it is far below anything audible.
        val worst = (0 until 16_384).maxOf { abs(whole[it] - split[it]) }
        assertTrue("largest divergence was $worst", worst < 6e-3f)
    }

    // -- the spatial properties -----------------------------------------------------------------

    @Test
    fun `panning is constant power, so a sound does not dip as it crosses the centre`() {
        for (pan in listOf(-1f, -0.5f, 0f, 0.25f, 1f)) {
            val power = PanLaw.left(pan) * PanLaw.left(pan) + PanLaw.right(pan) * PanLaw.right(pan)
            assertEquals("pan $pan", 1.0, power.toDouble(), 1e-4)
        }
        assertEquals(1.0, PanLaw.left(-1f).toDouble(), 1e-4)
        assertEquals(0.0, PanLaw.right(-1f).toDouble(), 1e-4)
        assertEquals(0.0, PanLaw.left(1f).toDouble(), 1e-4)
        assertEquals(1.0, PanLaw.right(1f).toDouble(), 1e-4)
    }

    @Test
    fun `the interaural delay can never reach echo territory`() {
        val absurd = SmartSpatialEngine.DEFAULT_PROFILE.copy(maxItdMs = 12f).clamped()
        assertEquals(SpatialAudioProfile.MAX_ITD_MS, absurd.maxItdMs, 0f)
        // Past roughly 0.7 ms the ear stops hearing a direction and starts hearing a second arrival.
        assertTrue(SpatialAudioProfile.MAX_ITD_MS <= 0.7f)
    }

    @Test
    fun `bass is not panned around the listener`() {
        // A 60 Hz tone, identical in both channels, under a 150 Hz crossover. Whatever the orbit is
        // doing, the two outputs must stay together — panned sub-bass wrecks both the stereo image and
        // anything played through a single speaker.
        val engine = engine(
            SmartSpatialEngine.DEFAULT_PROFILE.copy(bassCenterHz = 150f, panDepth = 0.8f, wetMix = 0.38f),
        )
        val frames = tone(60.0, seconds = 2.0, amplitude = 0.7f)
        engine.process(frames, frames.size / 2, 0L)

        val worst = (0 until frames.size / 2).maxOf { abs(frames[it * 2] - frames[it * 2 + 1]) }
        assertTrue("channels diverged by $worst", worst < 0.02f)
    }

    @Test
    fun `summing to mono does not swallow the vocal`() {
        // The classic failure of a naive widener: it sounds huge on headphones and the singer vanishes
        // on a phone speaker.
        //
        // The bar is 0.5 — exactly −6 dB — and that number is not a taste call, it is the floor that
        // constant-power panning imposes on its own. Panned hard to one side, `(gainL + gainR) / 2` is
        // `(1 + 0) / 2`; at the centre it is `(0.707 + 0.707) / 2`. So a signal that genuinely travels
        // from ear to ear *must* lose up to 6 dB when the two ears are added back together, and the
        // whole high band travels now, not just the middle of it.
        //
        // What the bar catches is loss the panning does not explain: an interaural delay allowed to grow
        // until it is half a wavelength (0.62 ms is already 201° at 900 Hz), a head shadow applied to
        // the wrong ear, or a room tail arriving in opposition. Any of those pulls this under 0.5 and
        // the voice really is being cancelled rather than moved.
        val engine = engine(SmartSpatialEngine.DEFAULT_PROFILE.copy(wetMix = 0.6f, panDepth = 0.9f))
        val input = tone(900.0, seconds = 3.0, amplitude = 0.6f)
        val frames = input.copyOf()
        engine.process(frames, frames.size / 2, 0L)

        val before = monoRms(input)
        val after = monoRms(frames)
        assertTrue("mono level fell from $before to $after", after > before * 0.5f)
    }

    @Test
    fun `a source behind the listener sounds different from one in front`() {
        // Panning, interaural delay and head shadow are all symmetric front-to-back: they place a sound
        // on the axis through the ears and say nothing about which side of the head it is on. Without a
        // spectral difference the back half of every orbit is, to the ear, a replay of the front half —
        // the orbit collapses from a circle to a line. This asserts the cue that separates them.
        val orbitSec = 4f
        val engine = engine(SmartSpatialEngine.DEFAULT_PROFILE.copy(orbitPeriodSec = orbitSec))

        // **Mono input on purpose.** The width layer orbits half a turn away from the centre, so it is
        // bright exactly when the centre is dark; measuring the *total* treble would see the two cancel
        // and conclude there was no cue at all. A mono source has no width layer, which leaves the one
        // under test alone.
        val source = noise(rate * 12, amplitude = 0.4f)
        val frames = FloatArray(rate * 12 * 2)
        for (i in 0 until rate * 12) {
            frames[i * 2] = source[i]
            frames[i * 2 + 1] = source[i]
        }
        engine.process(frames, frames.size / 2, 0L)

        // Two full orbits in, so the fade-in is long over. Phase 0 is in front of the listener; half an
        // orbit later the same trajectory is directly behind them.
        val front = trebleLevel(frames, atSec = 2.0 * orbitSec)
        val behind = trebleLevel(frames, atSec = 2.5 * orbitSec)

        // It measures about 0.68 — roughly 3.3 dB of treble, on top of the bell's 11 dB swing through
        // the presence band and the level dip. The bar sits where half that cue would still fail.
        assertTrue("front $front vs behind $behind", behind < front * 0.85f)
    }

    @Test
    fun `the ambience travels with the source instead of anchoring it`() {
        // A tail that stays put while the direct sound moves tells the ear the room is over *there* and
        // the music is being swept across it — which is what a sweep sounds like rather than an orbit.
        // So at the extremes of the pan the two channels' ambience must be measurably unequal.
        val orbitSec = 4f
        val loud = SmartSpatialEngine.DEFAULT_PROFILE.copy(orbitPeriodSec = orbitSec, reverbWet = 0.7f)
        val engine = engine(loud)
        val frames = noise(rate * 2 * 12, amplitude = 0.4f)
        engine.process(frames, frames.size / 2, 0L)

        // A quarter and three quarters of the way round: hard right, then hard left.
        val right = channelBalance(frames, atSec = 2.25 * orbitSec)
        val left = channelBalance(frames, atSec = 2.75 * orbitSec)

        // Whatever the sign convention, the two extremes must lean opposite ways and actually lean.
        assertTrue("balance right $right vs left $left", right * left < 0f)
        assertTrue("barely leaned: $right / $left", abs(right) > 0.1f && abs(left) > 0.1f)
    }

    @Test
    fun `the recording's own width survives and travels with the effect`() {
        // The side signal is everything the engineer deliberately placed away from the centre — the
        // guitars, the pads, the backing vocals. It used to be mostly discarded, and the fraction that
        // came back sat still while the middle orbited, so a wide record arrived narrower than it left
        // and the one thing that did move was a single rigid block.
        val orbitSec = 4f
        val engine = engine(SmartSpatialEngine.DEFAULT_PROFILE.copy(orbitPeriodSec = orbitSec))

        // Pure side: the same content in antiphase, which is as wide as two channels can be. The mid is
        // exactly zero, so nothing here comes from the centre layer or the room.
        val source = noise(rate * 12, amplitude = 0.5f)
        val input = FloatArray(rate * 12 * 2)
        for (i in 0 until rate * 12) {
            input[i * 2] = source[i]
            input[i * 2 + 1] = -source[i]
        }
        val frames = input.copyOf()
        engine.process(frames, frames.size / 2, 0L)

        // About 46% of it comes through, against roughly 21% when this layer was discarded and pinned.
        // The bar is set below the new figure but well above the old one, so the regression it guards
        // against is exactly the one that happened.
        assertTrue(
            "width ${sideRms(frames)} of ${sideRms(input)}",
            sideRms(frames) > sideRms(input) * 0.4f,
        )

        // And it has a place in the ring, opposite the centre, so the extremes lean opposite ways.
        val quarter = channelBalance(frames, atSec = 2.25 * orbitSec)
        val threeQuarters = channelBalance(frames, atSec = 2.75 * orbitSec)
        assertTrue("width sat still: $quarter / $threeQuarters", quarter * threeQuarters < 0f)
    }

    @Test
    fun `the two channels never end up persistently out of phase`() {
        val engine = engine(SmartSpatialEngine.DEFAULT_PROFILE.copy(wetMix = 0.6f, panDepth = 0.9f))
        val frames = noise(48_000 * 2 * 3)
        engine.process(frames, frames.size / 2, 0L)

        // A large room's tail *is* decorrelated between the ears — that is what makes it sound like a
        // room rather than a delay — so some negative correlation is the effect working. What this
        // rules out is the pathological case: the two channels locked in opposition, which cancels to
        // silence the moment anything sums them.
        assertTrue("correlation was ${correlation(frames)}", correlation(frames) > -0.45f)
    }

    // -- state, ramps and resets ----------------------------------------------------------------

    @Test
    fun `reset clears the ambience tail`() {
        val engine = engine(SmartSpatialEngine.DEFAULT_PROFILE.copy(reverbWet = 0.4f, reverbDecaySec = 2.5f))
        // Loud enough and long enough to survive the fade-in: the room has a 42 ms pre-delay and its
        // first tap lands at 54 ms, so a couple of buffers would be listening to silence by design.
        val impulse = FloatArray(48_000).apply { for (i in 0 until 400) { this[i] = 0.9f; this[i + 1] = 0.9f } }
        engine.process(impulse, 24_000, 0L)

        val ringing = FloatArray(48_000)
        engine.process(ringing, 24_000, 500_000L)
        assertTrue("nothing was ringing to begin with", ringing.any { abs(it) > 1e-6f })

        engine.reset(0L)
        val afterReset = FloatArray(48_000)
        engine.process(afterReset, 24_000, 0L)
        assertTrue("the previous song's tail survived a reset", afterReset.all { abs(it) < 1e-6f })
    }

    @Test
    fun `toggling the effect is a fade, not a click`() {
        val engine = engine(on = false)
        val settle = tone(200.0, seconds = 0.5)
        engine.process(settle, settle.size / 2, 0L)

        engine.setEnabled(true)
        val frames = tone(200.0, seconds = 1.0)
        engine.process(frames, frames.size / 2, 500_000L)

        // A discontinuity would show up as a step far larger than the waveform's own slope.
        val reference = tone(200.0, seconds = 1.0)
        val naturalStep = (2 until reference.size / 2).maxOf { abs(reference[it * 2] - reference[(it - 1) * 2]) }
        val actualStep = (2 until frames.size / 2).maxOf { abs(frames[it * 2] - frames[(it - 1) * 2]) }
        assertTrue("step $actualStep against a natural $naturalStep", actualStep < naturalStep * 3f)
    }

    @Test
    fun `switching profile mid-song glides instead of jumping`() {
        val engine = engine(SmartSpatialEngine.DEFAULT_PROFILE.copy(orbitPeriodSec = 30f, panDepth = 0.2f))
        val warm = tone(300.0, seconds = 2.0)
        engine.process(warm, warm.size / 2, 0L)

        // The orbit period is the dangerous one: derived from absolute position it would leap most of a
        // turn when the period changed. Accumulated, it can only change speed.
        engine.setProfile(SmartSpatialEngine.DEFAULT_PROFILE.copy(orbitPeriodSec = 9f, panDepth = 0.8f))
        val frames = tone(300.0, seconds = 1.0)
        engine.process(frames, frames.size / 2, 2_000_000L)

        val reference = tone(300.0, seconds = 1.0)
        val naturalStep = (2 until reference.size / 2).maxOf { abs(reference[it * 2] - reference[(it - 1) * 2]) }
        val actualStep = (2 until frames.size / 2).maxOf { abs(frames[it * 2] - frames[(it - 1) * 2]) }
        assertTrue("step $actualStep against a natural $naturalStep", actualStep < naturalStep * 3f)
    }

    @Test
    fun `it survives every sample rate it may be handed`() {
        for (sampleRate in listOf(44_100, 48_000, 88_200, 96_000, 192_000)) {
            val engine = SmartSpatialEngine().apply {
                configure(sampleRate)
                setProfile(SmartSpatialEngine.DEFAULT_PROFILE)
                setEnabled(true)
            }
            val frames = noise(8192)
            engine.process(frames, 4096, 0L)
            assertTrue("$sampleRate Hz produced a bad sample", frames.all { it.isFinite() && abs(it) <= 1f })
        }
    }

    @Test
    fun `silence in, silence out`() {
        val frames = FloatArray(8192)
        engine().process(frames, 4096, 0L)
        assertTrue(frames.all { abs(it) < 1e-6f })
    }

    // -- synthetic signals ----------------------------------------------------------------------

    /** Interleaved stereo sine, identical in both channels. */
    private fun tone(hz: Double, seconds: Double, amplitude: Float = 0.5f): FloatArray {
        val frames = (rate * seconds).toInt()
        return FloatArray(frames * 2) { i ->
            val frame = i / 2
            (amplitude * sin(2 * PI * hz * frame / rate)).toFloat()
        }
    }

    /** Deterministic pseudo-noise — a seeded LCG, so a failure is always reproducible. */
    private fun noise(samples: Int, amplitude: Float = 0.5f): FloatArray {
        var seed = 0x5DEECE66DL
        return FloatArray(samples) {
            seed = (seed * 25214903917L + 11L) and 0xFFFFFFFFFFFFL
            amplitude * ((seed shr 16).toInt() / Int.MAX_VALUE.toFloat())
        }
    }

    /**
     * How much treble a quarter-second window holds, as the RMS of the sample-to-sample difference.
     *
     * That difference *is* a high-pass — it is the crudest one there is — which is all this needs: the
     * question is only whether one window has more top end than another, not what its spectrum is.
     */
    private fun trebleLevel(frames: FloatArray, atSec: Double, seconds: Double = 0.25): Float {
        val start = (rate * atSec).toInt().coerceAtLeast(1)
        val count = (rate * seconds).toInt()
        var sum = 0.0
        for (i in start until start + count) {
            val l = (frames[i * 2] - frames[(i - 1) * 2]).toDouble()
            val r = (frames[i * 2 + 1] - frames[(i - 1) * 2 + 1]).toDouble()
            sum += l * l + r * r
        }
        return sqrt(sum / (count * 2)).toFloat()
    }

    /** Which channel a window sits in, as `(R − L) / (R + L)` of RMS. 0 is centred, ±1 is one side only. */
    private fun channelBalance(frames: FloatArray, atSec: Double, seconds: Double = 0.2): Float {
        val start = (rate * atSec).toInt()
        val count = (rate * seconds).toInt()
        var l = 0.0
        var r = 0.0
        for (i in start until start + count) {
            l += frames[i * 2].toDouble() * frames[i * 2]
            r += frames[i * 2 + 1].toDouble() * frames[i * 2 + 1]
        }
        val left = sqrt(l / count)
        val right = sqrt(r / count)
        return if (left + right < 1e-9) 0f else ((right - left) / (right + left)).toFloat()
    }

    /** How much of a signal is *width* rather than centre: the RMS of the L/R difference. */
    private fun sideRms(frames: FloatArray): Float {
        var sum = 0.0
        for (i in 0 until frames.size / 2) {
            val side = 0.5f * (frames[i * 2] - frames[i * 2 + 1])
            sum += side.toDouble() * side
        }
        return sqrt(sum / (frames.size / 2)).toFloat()
    }

    private fun monoRms(frames: FloatArray): Float {
        var sum = 0.0
        for (i in 0 until frames.size / 2) {
            val mono = 0.5f * (frames[i * 2] + frames[i * 2 + 1])
            sum += mono.toDouble() * mono
        }
        return sqrt(sum / (frames.size / 2)).toFloat()
    }

    private fun correlation(frames: FloatArray): Float {
        var lr = 0.0
        var ll = 0.0
        var rr = 0.0
        for (i in 0 until frames.size / 2) {
            val l = frames[i * 2].toDouble()
            val r = frames[i * 2 + 1].toDouble()
            lr += l * r
            ll += l * l
            rr += r * r
        }
        val denominator = sqrt(ll * rr)
        return if (denominator < 1e-12) 1f else (lr / denominator).toFloat()
    }
}
