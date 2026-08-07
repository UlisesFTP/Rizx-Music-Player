package fm.rizx.player.playback.spatial

import fm.rizx.player.domain.model.SpatialAudioProfile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Constant-power panning, the law the Web Audio specification calls "equal-power".
 *
 * The alternative — `L = 1 − pan`, `R = pan` — is the obvious one and it is wrong: the two gains sum to
 * one but their *powers* sum to a half in the middle, so a sound sweeping past the listener drops about
 * 3 dB every time it crosses the centre. With a slow orbit that reads as the song breathing in and out.
 */
internal object PanLaw {
    /** @param pan −1 hard left, 0 centre, +1 hard right. */
    fun left(pan: Float): Float = cos((pan.coerceIn(-1f, 1f) + 1f) * 0.5f * HALF_PI)

    fun right(pan: Float): Float = sin((pan.coerceIn(-1f, 1f) + 1f) * 0.5f * HALF_PI)

    private const val HALF_PI = 1.5707964f
}

/** Interleaved stereo float DSP, processed in place. Nothing Android-shaped, so it unit-tests on the JVM. */
interface StereoPcmTransform {
    fun configure(sampleRateHz: Int)
    fun setProfile(profile: SpatialAudioProfile)

    /**
     * @param ramp fade the change in and out. True for playback, where switching the effect on must not
     *   be a click. False for an offline render, which has no listener to protect from a discontinuity
     *   and must be fully processed from its very first sample rather than easing in over a second.
     */
    fun setEnabled(enabled: Boolean, ramp: Boolean = true)

    /** Drops all filter/delay state and re-anchors the orbit to an absolute stream position. */
    fun reset(positionUs: Long)

    /** True once the effect is off *and* its fade-out has finished, so the caller can bypass entirely. */
    val silent: Boolean

    /**
     * @param frames interleaved L/R in −1..1, modified in place.
     * @param frameCount frames, not samples. Never changed — the output is exactly as long as the input.
     * @param positionUs absolute stream position of the first frame.
     */
    fun process(frames: FloatArray, frameCount: Int, positionUs: Long)
}

/**
 * The spatializer.
 *
 * Everything above the crossover is **taken out of the mix and put back where the orbit is** — not
 * added on top of it. An added copy leaves the original in place at full level, and that anchor is
 * louder than the copy, so the ear hears a static mix with a halo around it rather than a song that
 * travels. The low end never enters any of this and so is never moved.
 *
 * Two layers travel, on opposite points of the ring: the middle of the high band, and the recording's
 * own width. One thing moving is a sweep; two things moving in different places is a space.
 *
 * ```
 * L/R ─┬───────────────────── dry, minus the whole high band ─────────┐
 *      │                        ┌─ equal-power pan                    │
 *      │              ┌─ mid ───┼─ ITD (fractional delay)             │
 *      └─ crossover ──┤         ├─ head shadow + crossfeed            ├─ headroom ─ limiter ─ out
 *      (bass stays    │         ├─ front/back (pinna bell + damping)  │
 *       centred)      │         ├─ elevation (pinna notch sweep)      │
 *                     │         └─ ambience, leaning with the source  │
 *                     └─ side ─── counter-orbit, antiphase preserved  ┘
 * ```
 *
 * **Everything that can be precomputed is.** Trigonometry runs twice per block, not twice per frame:
 * the orbit is evaluated at the block's first and last frame and interpolated between, which over a
 * 21 ms block of an 8-second orbit is an error far below anything audible. Nothing allocates after
 * [configure], nothing blocks, and nothing here knows what a coroutine is.
 */
internal class SmartSpatialEngine : StereoPcmTransform {

    private var sampleRate = 0
    private var target = DEFAULT_PROFILE
    private var live = DEFAULT_PROFILE

    private var crossoverL = Biquad()
    private var crossoverR = Biquad()
    private var crossoverL2 = Biquad()
    private var crossoverR2 = Biquad()
    private var shadowL = OnePole()
    private var shadowR = OnePole()

    /**
     * The front/back cue, and the reason the orbit is a circle rather than a line.
     *
     * Panning, interaural delay and head shadow are all **left/right** cues: they place a sound on the
     * axis through the listener's ears and say nothing about which side of the head it is on. A source
     * directly behind produces the same delay and the same level difference as one directly in front —
     * which is why the orbit's whole back half was, to the ear, a repeat of its front half.
     *
     * What separates them is spectral, and it is the outer ear that does it: the pinna resonates around
     * 3–4 kHz for sound arriving from the front and shadows the same band, plus everything above it,
     * for sound arriving from behind. So [frontBackL]/[frontBackR] swing a bell from boost to cut
     * across the orbit, and [rearDampL]/[rearDampR] roll the top off as the source passes behind.
     */
    private var frontBackL = Biquad()
    private var frontBackR = Biquad()
    private var rearDampL = OnePole()
    private var rearDampR = OnePole()
    private var appliedFrontBackDb = Float.NaN

    /**
     * Height — the third dimension, and the one an orbit at ear level does not have.
     *
     * Azimuth is carried by timing and level, but the ear has nothing to compare for elevation: a
     * source above and a source in front reach both ears identically. What resolves it is a single
     * spectral feature, the **pinna notch** — a deep, narrow dip the folds of the outer ear carve into
     * the incoming sound, whose frequency *rises* as the source rises, from around 6 kHz below the
     * listener to past 10 kHz above them. Sweeping that notch is the whole cue, and it is why the
     * trajectory here is an inclined ring: the sound climbs as it passes behind and comes back down in
     * front, rather than sliding round a flat circle.
     */
    private var elevationL = Biquad()
    private var elevationR = Biquad()
    private var appliedElevationHz = Float.NaN

    /**
     * The same two direction cues for the width layer, at the opposite point of the ring.
     *
     * Without these it is spectrally flat while everything around it moves, and since it carries a
     * large share of the output that does not merely leave it undirected — it *dilutes* the cues on the
     * layer that does have them. Only one filter each is needed: the width is a single mono signal
     * right up until the balance splits it between the ears.
     */
    private var sideFrontBack = Biquad()
    private var sideElevation = Biquad()
    private var sideRearDamp = OnePole()
    private var appliedSideElevationHz = Float.NaN
    private var itdL = FractionalDelayLine(2)
    private var itdR = FractionalDelayLine(2)
    private var room = StereoRoom(48_000)
    private var limiter = PeakLimiter(48_000)
    private val roomOut = FloatArray(2)

    private var appliedBassHz = -1f
    private var appliedShadowHz = -1f
    private var appliedDecaySec = -1f

    /** Orbit angle in radians, accumulated. See [advancePhase] for why this is not derived from position. */
    private var phase = 0.0
    private var expectedPositionUs = Long.MIN_VALUE

    /** 0 = fully dry, 1 = the profile's wet mix. Ramped so a toggle is a fade, never a click. */
    private var envelope = 0f
    private var envelopeTarget = 0f

    /** Short- and long-term loudness, measured from the previous block, for [movementModulation]. */
    private var shortEnergy = 0f
    private var longEnergy = 0f

    override val silent: Boolean
        get() = envelopeTarget == 0f && envelope <= SILENT_THRESHOLD

    override fun configure(sampleRateHz: Int) {
        val rate = sampleRateHz.coerceIn(8_000, 384_000)
        if (rate != sampleRate) {
            sampleRate = rate
            val maxItdSamples = (SpatialAudioProfile.MAX_ITD_MS / 1000f * rate).toInt() + 2
            itdL = FractionalDelayLine(maxItdSamples)
            itdR = FractionalDelayLine(maxItdSamples)
            room = StereoRoom(rate)
            limiter = PeakLimiter(rate)
            appliedBassHz = -1f
            appliedShadowHz = -1f
            appliedDecaySec = -1f
        }
        applyCoefficients(force = true)
        reset(0L)
    }

    override fun setProfile(profile: SpatialAudioProfile) {
        target = profile.clamped()
        // Nothing is being heard yet, so there is nothing to glide *from*: adopt it outright. Otherwise
        // the first second and a half of the first song would play the character of a default nobody
        // chose. While the effect is running the glide stays — a refined analysis arriving mid-song, or
        // the next track's profile, should drift in rather than switch.
        if (silent) live = target
    }

    override fun setEnabled(enabled: Boolean, ramp: Boolean) {
        envelopeTarget = if (enabled) 1f else 0f
        if (!ramp) envelope = envelopeTarget
    }

    override fun reset(positionUs: Long) {
        crossoverL.reset()
        crossoverR.reset()
        crossoverL2.reset()
        crossoverR2.reset()
        shadowL.reset()
        shadowR.reset()
        frontBackL.reset()
        frontBackR.reset()
        rearDampL.reset()
        rearDampR.reset()
        elevationL.reset()
        elevationR.reset()
        sideFrontBack.reset()
        sideElevation.reset()
        sideRearDamp.reset()
        itdL.reset()
        itdR.reset()
        room.reset()
        limiter.reset()
        shortEnergy = 0f
        longEnergy = 0f
        phase = orbitPhaseAt(positionUs)
        expectedPositionUs = positionUs
    }

    override fun process(frames: FloatArray, frameCount: Int, positionUs: Long) {
        if (frameCount <= 0) return
        if (sampleRate == 0) configure(48_000)

        // A jump the sink did not tell us about (a seek that skipped `flush`). Re-anchor rather than let
        // the orbit drift away from the music for the rest of the song.
        if (expectedPositionUs != Long.MIN_VALUE &&
            abs(positionUs - expectedPositionUs) > REANCHOR_TOLERANCE_US
        ) {
            phase = orbitPhaseAt(positionUs)
        }
        expectedPositionUs = positionUs + frameCount * 1_000_000L / sampleRate

        val rampStep = if (envelopeTarget > envelope) {
            1f / (RAMP_IN_SEC * sampleRate)
        } else {
            -1f / (RAMP_OUT_SEC * sampleRate)
        }

        // **The orbit is evaluated in short segments, not once per buffer.** Straight-lining the
        // trajectory across a whole buffer is fine for a twenty-second orbit and not fine for a
        // six-second one: over a 170 ms buffer the sound now travels twenty degrees, and the chord
        // across that arc misses it by about two per cent — enough that the same song processed in
        // different buffer sizes would follow measurably different paths. Media3 does not promise a
        // buffer size, so the trajectory must not depend on it.
        var done = 0
        while (done < frameCount) {
            val segment = minOf(ORBIT_SEGMENT_FRAMES, frameCount - done)
            val segmentSec = segment.toDouble() / sampleRate

            // **The glide towards a new profile is measured in seconds, not in calls.** It used to move
            // a fixed 1.5% each time `process` ran, which made "how fast does a song settle into its
            // profile" a function of the buffer size Media3 happened to choose — the same defect the
            // orbit had, and the reason a single long call barely moved off the defaults at all.
            interpolateProfile((1.0 - exp(-segmentSec / PROFILE_GLIDE_SEC)).toFloat())
            applyCoefficients(force = false)

            val itdSamples = live.maxItdMs / 1000f * sampleRate
            // `sidePreservation` used to scale this down as well as feeding the width back in. Doing
            // both meant a wide recording was quietly penalised twice, which is most of why the effect
            // kept coming out timid on exactly the records that should show it off.
            val baseWet = live.wetMix
            val crossfeed = live.crossfeed
            // Per segment too, and for the same reason: computed once per buffer, how much the movement
            // breathes with the music would depend on how the audio happened to be chopped up.
            val panScale = live.panDepth * movementModulation()
            var energy = 0f
            val startPhase = phase
            val endPhase = advancePhase(segmentSec)
            val startPan = (sin(startPhase) * panScale).toFloat()
            val endPan = (sin(endPhase) * panScale).toFloat()
            val startRear = ((1.0 - cos(startPhase)) * 0.5).toFloat()
            val endRear = ((1.0 - cos(endPhase)) * 0.5).toFloat()
            val invSegment = 1f / segment

            // The bell's gain is what travels, so its coefficients are rebuilt per segment — from the
            // middle of the arc, not its start, or the filter would lag the sound it is describing.
            // Rebuilt only when it has actually moved: an orbit spends a long time near its extremes.
            val frontBackDb = PINNA_PRESENCE_DB * (1f - (startRear + endRear))
            if (abs(frontBackDb - appliedFrontBackDb) > 0.05f) {
                frontBackL.setPeaking(PINNA_PRESENCE_HZ, sampleRate, frontBackDb, PINNA_Q)
                frontBackR.setPeaking(PINNA_PRESENCE_HZ, sampleRate, frontBackDb, PINNA_Q)
                // Half a turn away, so its bell is the exact negative: in front when the centre is
                // behind, and behind when the centre is in front.
                sideFrontBack.setPeaking(PINNA_PRESENCE_HZ, sampleRate, -frontBackDb, PINNA_Q)
                appliedFrontBackDb = frontBackDb
            }

            // The ring is inclined, so the source climbs as it passes behind: elevation rides the same
            // front-to-back angle, and the pinna notch rides elevation.
            val elevation = (startRear + endRear) * 0.5f * ELEVATION_TILT
            val notchHz = ELEVATION_NOTCH_LOW_HZ +
                (ELEVATION_NOTCH_HIGH_HZ - ELEVATION_NOTCH_LOW_HZ) * elevation
            if (abs(notchHz - appliedElevationHz) > 20f) {
                elevationL.setPeaking(notchHz, sampleRate, ELEVATION_NOTCH_DB, ELEVATION_Q)
                elevationR.setPeaking(notchHz, sampleRate, ELEVATION_NOTCH_DB, ELEVATION_Q)
                appliedElevationHz = notchHz
            }
            val sideElevationValue = (1f - (startRear + endRear) * 0.5f) * ELEVATION_TILT
            val sideNotchHz = ELEVATION_NOTCH_LOW_HZ +
                (ELEVATION_NOTCH_HIGH_HZ - ELEVATION_NOTCH_LOW_HZ) * sideElevationValue
            if (abs(sideNotchHz - appliedSideElevationHz) > 20f) {
                sideElevation.setPeaking(sideNotchHz, sampleRate, ELEVATION_NOTCH_DB, ELEVATION_Q)
                appliedSideElevationHz = sideNotchHz
            }

            for (i in 0 until segment) {
            val idx = (done + i) shl 1
            val inL = frames[idx]
            val inR = frames[idx + 1]
            energy += inL * inL + inR * inR

            val t = i * invSegment
            val pan = startPan + (endPan - startPan) * t
            val rear = startRear + (endRear - startRear) * t

            // Bass stays home: a 24 dB/octave high-pass on each channel, then the middle of what is
            // left. This is the layer that travels; everything below the crossover is simply not in it.
            val highL = crossoverL2.process(crossoverL.process(inL))
            val highR = crossoverR2.process(crossoverR.process(inR))
            val moving = 0.5f * (highL + highR)
            // What is left of the recording's own width once the centre is taken away. Added back on
            // the far side of the effect, scaled by `sidePreservation` — at zero the whole band above
            // the crossover becomes the orbiting layer and nothing of the original placement survives.
            val side = 0.5f * (highL - highR)

            // Equal-power, never linear: linear panning loses about 3 dB in the middle, so the sound
            // would dip every time it crossed in front of the listener.
            val gainL = PanLaw.left(pan)
            val gainR = PanLaw.right(pan)

            // Positive pan means the source is to the right, so the LEFT ear is the far one.
            val farL = if (pan > 0f) pan else 0f
            val farR = if (pan < 0f) -pan else 0f

            itdL.write(moving)
            itdR.write(moving)
            var wetL = itdL.read(farL * itdSamples) * gainL
            var wetR = itdR.read(farR * itdSamples) * gainR

            // Head shadow as a crossfade into the filtered copy: at zero deflection the filter is
            // bypassed exactly, so a centred sound is not quietly dulled.
            wetL += (shadowL.process(wetL) - wetL) * farL
            wetR += (shadowR.process(wetR) - wetR) * farR
            wetL *= 1f - farL * SHADOW_DEPTH
            wetR *= 1f - farR * SHADOW_DEPTH

            // Front/back. The bell is always in circuit — its gain is what moves, from boost in front
            // through flat at the sides to cut behind — while the damping is crossfaded in by `rear`,
            // so a source in front is bit-for-bit unfiltered rather than merely close to it.
            wetL = elevationL.process(frontBackL.process(wetL))
            wetR = elevationR.process(frontBackR.process(wetR))
            wetL += (rearDampL.process(wetL) - wetL) * rear
            wetR += (rearDampR.process(wetR) - wetR) * rear
            // Behind the listener is also slightly further away than in front of them.
            val rearLevel = 1f - rear * REAR_LEVEL_DIP
            wetL *= rearLevel
            wetR *= rearLevel

            val fedL = wetL + crossfeed * wetR
            val fedR = wetR + crossfeed * wetL

            room.process(moving, roomOut)
            val ambience = live.reverbWet * (REAR_AMBIENCE_FLOOR + REAR_AMBIENCE_SPAN * rear)
            // **The space travels with the source.** A tail that stays put while the direct sound moves
            // is an anchor: it tells the ear the room is over *there* and the music is being swept
            // across it, which is what a sweep sounds like rather than an orbit. Reflections arrive
            // from whichever side the source is on, so the ambience leans that way too. Partial on
            // purpose — a room panned as hard as the source stops enveloping and becomes a second,
            // wetter copy of it, and envelopment is what puts the sound outside the head at all.
            val roomTilt = pan * ROOM_FOLLOW
            val ambienceL = ambience * (1f - roomTilt)
            val ambienceR = ambience * (1f + roomTilt)

            envelope = (envelope + rampStep).coerceIn(
                minOf(envelope, envelopeTarget),
                maxOf(envelope, envelopeTarget),
            )
            val wet = baseWet * envelope

            // **The whole band above the crossover is taken out of the mix and put back where the orbit
            // is** — not just its centre, and not as an addition.
            //
            // Adding a spatialized copy on top leaves the original sitting in place at full level, and
            // that anchor is louder than the copy, so the ear hears a static mix with a halo around it.
            // Lifting only the *centre* out is better but still leaves everything the record had
            // already panned — guitars, pads, backing vocals — nailed where it was, which on a wide
            // modern mix is most of the music. Lifting the whole band is what makes the song itself
            // travel rather than something inside it.
            //
            // The low end is untouched throughout: it was never in `high` to begin with.
            val liftedL = highL * wet
            val liftedR = highR * wet

            // **The record's own width is a second layer, and it orbits opposite the centre.**
            //
            // Everything above the crossover is lifted out, but only its *middle* was being sent
            // travelling; the difference signal — which is precisely the material the mixer placed away
            // from the centre, the guitars, pads and backing vocals — came back at a fraction of its
            // level and sat still. Most of the record's width was being discarded and what survived was
            // an anchor. So the whole mix arrived as one rigid block sliding around, which is what a
            // sweep sounds like rather than a place with things in it.
            //
            // Giving it the opposite point of the ring costs nothing and separates instruments that the
            // mix had *already* separated — unlike splitting by frequency, which would tear one voice's
            // body away from its consonants. And because it now moves, keeping much more of it no
            // longer anchors anything.
            //
            // It travels by **balance, not by gain**: the two ears' shares tilt opposite the centre
            // while their *difference* — which is what width actually is — stays exactly where the
            // record put it. Running this layer through the equal-power pan law instead cost it another
            // 3 to 6 dB of the very thing being preserved, which defeats the point.
            var keptSide = side * live.sidePreservation
            // Its own place in the ring means its own direction cues, half a turn from the centre's.
            keptSide = sideElevation.process(sideFrontBack.process(keptSide))
            val sideRear = 1f - rear
            keptSide += (sideRearDamp.process(keptSide) - keptSide) * sideRear
            keptSide *= 1f - sideRear * REAR_LEVEL_DIP
            val sideTilt = -pan * SIDE_ORBIT_DEPTH
            val sideL = keptSide * (1f + sideTilt)
            val sideR = keptSide * (1f - sideTilt)
            // Enabling the effect must not make the song louder — otherwise the toggle reads as a
            // volume control and the limiter ends up working permanently.
            val headroom = 1f / (1f + wet * HEADROOM_FACTOR)

            // The side layer keeps its opposite polarity between the ears — that antiphase *is* its
            // width — while its balance travels. Panned to one extreme it collapses onto that side,
            // which is the point: it is somewhere, and that somewhere moves.
            frames[idx] =
                (inL - liftedL + (fedL + sideL + roomOut[0] * ambienceL) * wet) * headroom
            frames[idx + 1] =
                (inR - liftedR + (fedR - sideR + roomOut[1] * ambienceR) * wet) * headroom
            // Only ever limit what this added. With nothing added there is nothing to catch, and a
            // limiter left running would quietly pull a modern master down by a decibel for no reason —
            // the effect would be changing the sound while switched off.
            if (wet > SILENT_THRESHOLD) limiter.process(frames, idx)
            }
            trackEnergy(sqrt(energy / (segment * 2f)), segment.toFloat() / sampleRate)
            done += segment
        }
    }

    /**
     * How far the movement is allowed to breathe with the music.
     *
     * A quiet passage with the same wide orbit as the chorus sounds like the effect is fighting the
     * song, so depth eases off when the level drops and returns when it comes back. Strictly bounded:
     * the profile is what decides the character, this only leans on it.
     */
    private fun movementModulation(): Float {
        if (longEnergy <= 1e-6f) return 1f
        val ratio = (shortEnergy / longEnergy - 1f).coerceIn(-1f, 1f)
        return 1f + live.movementResponse * MOVEMENT_SPAN * ratio
    }

    /**
     * Time-based rather than per-block, so that the same audio split into different buffer sizes
     * modulates identically. Media3 does not promise a constant block size, and a modulation that
     * depended on it would make the effect subtly different on every device.
     */
    private fun trackEnergy(rms: Float, blockSeconds: Float) {
        shortEnergy += (rms - shortEnergy) * (1f - exp(-blockSeconds / SHORT_ENERGY_TAU_SEC))
        longEnergy += (rms - longEnergy) * (1f - exp(-blockSeconds / LONG_ENERGY_TAU_SEC))
    }

    /** Radians for an absolute position, used only to re-anchor after a seek. */
    private fun orbitPhaseAt(positionUs: Long): Double {
        val seconds = positionUs / 1_000_000.0
        return TWO_PI * (seconds / live.orbitPeriodSec) % TWO_PI
    }

    /**
     * Advances and returns the orbit angle.
     *
     * **Accumulated rather than computed from the stream position, and that is not an accident.**
     * `2π · position / period` looks simpler and is a trap: the period is interpolated whenever the
     * profile changes, and two minutes into a song a period moving from 20 s to 22 s would drag the
     * angle by most of a full turn — the sound would leap across the listener's head. Accumulating
     * makes a period change a change of *speed*, which is what it should be, and [reset] re-anchors to
     * the real position whenever the listener seeks.
     */
    private fun advancePhase(blockSeconds: Double): Double {
        phase = (phase + TWO_PI * blockSeconds / live.orbitPeriodSec) % TWO_PI
        return phase
    }

    private fun interpolateProfile(step: Float) {
        if (live === target) return
        live = SpatialAudioProfile(
            label = target.label,
            orbitPeriodSec = glide(live.orbitPeriodSec, target.orbitPeriodSec, step),
            panDepth = glide(live.panDepth, target.panDepth, step),
            wetMix = glide(live.wetMix, target.wetMix, step),
            bassCenterHz = glide(live.bassCenterHz, target.bassCenterHz, step),
            maxItdMs = glide(live.maxItdMs, target.maxItdMs, step),
            farEarLowPassHz = glide(live.farEarLowPassHz, target.farEarLowPassHz, step),
            crossfeed = glide(live.crossfeed, target.crossfeed, step),
            reverbWet = glide(live.reverbWet, target.reverbWet, step),
            reverbDecaySec = glide(live.reverbDecaySec, target.reverbDecaySec, step),
            sidePreservation = glide(live.sidePreservation, target.sidePreservation, step),
            movementResponse = glide(live.movementResponse, target.movementResponse, step),
        )
        // Once it has arrived, stop rebuilding an identical object thousands of times a second.
        if (live == target) live = target
    }

    private fun glide(from: Float, to: Float, step: Float): Float =
        if (abs(to - from) < 1e-4f) to else from + (to - from) * step

    /** Filter coefficients are rebuilt only when the value behind them has actually moved. */
    private fun applyCoefficients(force: Boolean) {
        if (force || abs(live.bassCenterHz - appliedBassHz) > 0.5f) {
            crossoverL.setHighPass(live.bassCenterHz, sampleRate)
            crossoverR.setHighPass(live.bassCenterHz, sampleRate)
            crossoverL2.setHighPass(live.bassCenterHz, sampleRate)
            crossoverR2.setHighPass(live.bassCenterHz, sampleRate)
            appliedBassHz = live.bassCenterHz
        }
        if (force || abs(live.farEarLowPassHz - appliedShadowHz) > 5f) {
            shadowL.setCutoff(live.farEarLowPassHz, sampleRate)
            shadowR.setCutoff(live.farEarLowPassHz, sampleRate)
            appliedShadowHz = live.farEarLowPassHz
        }
        if (force) {
            // Fixed by anatomy rather than by the profile, so these are set once per sample rate.
            rearDampL.setCutoff(REAR_DAMP_HZ, sampleRate)
            rearDampR.setCutoff(REAR_DAMP_HZ, sampleRate)
            frontBackL.setPeaking(PINNA_PRESENCE_HZ, sampleRate, PINNA_PRESENCE_DB, PINNA_Q)
            frontBackR.setPeaking(PINNA_PRESENCE_HZ, sampleRate, PINNA_PRESENCE_DB, PINNA_Q)
            appliedFrontBackDb = PINNA_PRESENCE_DB
            elevationL.setPeaking(ELEVATION_NOTCH_LOW_HZ, sampleRate, ELEVATION_NOTCH_DB, ELEVATION_Q)
            elevationR.setPeaking(ELEVATION_NOTCH_LOW_HZ, sampleRate, ELEVATION_NOTCH_DB, ELEVATION_Q)
            appliedElevationHz = ELEVATION_NOTCH_LOW_HZ
            sideRearDamp.setCutoff(REAR_DAMP_HZ, sampleRate)
            sideFrontBack.setPeaking(PINNA_PRESENCE_HZ, sampleRate, -PINNA_PRESENCE_DB, PINNA_Q)
            sideElevation.setPeaking(ELEVATION_NOTCH_HIGH_HZ, sampleRate, ELEVATION_NOTCH_DB, ELEVATION_Q)
            appliedSideElevationHz = ELEVATION_NOTCH_HIGH_HZ
        }
        if (force || abs(live.reverbDecaySec - appliedDecaySec) > 0.01f) {
            room.setDecay(live.reverbDecaySec, sampleRate)
            appliedDecaySec = live.reverbDecaySec
        }
    }

    internal companion object {
        val DEFAULT_PROFILE = SpatialAudioProfile(
            label = "default",
            orbitPeriodSec = 7f,
            panDepth = 0.92f,
            wetMix = 0.88f,
            bassCenterHz = 130f,
            maxItdMs = 0.62f,
            farEarLowPassHz = 2_800f,
            crossfeed = 0.04f,
            reverbWet = 0.45f,
            reverbDecaySec = 2.4f,
            sidePreservation = 0.60f,
            movementResponse = 0.5f,
        )

        private const val TWO_PI = 2.0 * PI

        /** About 5 ms at 48 kHz — far shorter than any movement the ear can follow. */
        private const val ORBIT_SEGMENT_FRAMES = 256

        /** How much the far ear loses in level at full deflection. Never all of it. */
        private const val SHADOW_DEPTH = 0.70f

        /** Ambience at the front of the orbit, and how much more of it there is at the back. */
        private const val REAR_AMBIENCE_FLOOR = 0.55f
        private const val REAR_AMBIENCE_SPAN = 0.9f

        /**
         * The outer ear's frontal resonance. Boosted in front, cut behind — the single cue that tells
         * the two apart, since every other one here is symmetric about the ears.
         */
        private const val PINNA_PRESENCE_HZ = 3_500f
        private const val PINNA_PRESENCE_DB = 5.5f

        /** Broad on purpose: this is the shape of an ear, not a corrective EQ notch. */
        private const val PINNA_Q = 0.9f

        /** Where the top end goes as the source passes behind the listener. */
        private const val REAR_DAMP_HZ = 5_200f

        /** Behind the head is a little further from both ear canals than in front of it. */
        private const val REAR_LEVEL_DIP = 0.12f

        /**
         * How far the ring is inclined. Not vertical: the sound should pass over the listener's head,
         * not straight above it, and a notch parked at the top of its range stops reading as motion.
         */
        private const val ELEVATION_TILT = 0.8f

        /** The pinna notch at its lowest and highest — the sweep between them *is* the height cue. */
        private const val ELEVATION_NOTCH_LOW_HZ = 6_300f
        private const val ELEVATION_NOTCH_HIGH_HZ = 10_800f

        /** Deep and narrow, because that is what the fold of an ear does. A broad dip just sounds dull. */
        private const val ELEVATION_NOTCH_DB = -9f
        private const val ELEVATION_Q = 2.6f

        /** How much of the source's deflection the ambience follows. */
        private const val ROOM_FOLLOW = 0.35f

        /** How far the width layer leans, opposite the centre. Balance only — the width itself is kept. */
        private const val SIDE_ORBIT_DEPTH = 0.6f

        /**
         * Attenuation applied per unit of wet, so the toggle is roughly level-neutral. Kept modest —
         * the limiter is there to catch peaks, and pulling the whole song down is a poor way to make
         * room for an effect the listener has just asked to hear.
         */
        private const val HEADROOM_FACTOR = 0.20f

        /** Per-block glide toward the target profile — roughly a 1.5 s move at typical block sizes. */
        /**
         * Time constant for moving to a new profile. Long enough that a refined analysis arriving
         * mid-song is a drift rather than an event, short enough that the first seconds of a track are
         * not still playing the previous one's character.
         */
        private const val PROFILE_GLIDE_SEC = 1.5

        /** Fade times for the toggle, in seconds. Time-based, so block size cannot change them. */
        private const val RAMP_IN_SEC = 0.9f
        private const val RAMP_OUT_SEC = 0.6f

        /** Widest the movement may breathe with the music, either way. */
        private const val MOVEMENT_SPAN = 0.15f
        private const val SHORT_ENERGY_TAU_SEC = 0.15f
        private const val LONG_ENERGY_TAU_SEC = 6f

        /** Below this the added layer is inaudible and the sink can drop to a true zero-copy bypass. */
        private const val SILENT_THRESHOLD = 1e-4f

        /** Half a second of disagreement between our clock and the stream's means a seek happened. */
        private const val REANCHOR_TOLERANCE_US = 500_000L
    }
}
