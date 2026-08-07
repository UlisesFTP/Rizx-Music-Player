package fm.rizx.player.domain.model

import kotlinx.serialization.Serializable

/**
 * Adaptive stereo spatialization — "Audio 8D" in the UI, spatial audio everywhere else.
 *
 * **What this is, precisely.** An illusion built from panning, interaural delay, head-shadow filtering
 * and a little ambience, and it works through headphones. It is not eight of anything, not Dolby Atmos,
 * not multichannel, not HRTF, and not a quality improvement: a finished stereo master cannot have its
 * voice, drums and guitars moved independently without stem separation, so what moves here is the
 * *centre content derived from the mix*, with the recording's own stereo image left alone underneath.
 * The names in this file stay technical so the same engine can later carry other modes without the
 * whole architecture being renamed after a marketing term.
 */
enum class SpatialAudioMode {
    OFF,
    SMART_8D,
}

/**
 * Everything the DSP engine needs to know, and nothing about *how* it was decided.
 *
 * Every field has a hard range enforced by [clamped]. The ranges are not decoration: an interaural
 * delay past ~0.65 ms stops reading as direction and starts reading as an echo, a wet mix past ~0.4
 * buries the mix it is supposed to be moving, and a bass crossover below ~90 Hz lets the sub get panned,
 * which wrecks both stability and mono compatibility.
 */
@Serializable
data class SpatialAudioProfile(
    /** Which preset produced this, for the state readout and for debugging. Not user-facing. */
    val label: String,
    /** Seconds for one full orbit. Tempo-derived when the estimate is confident. */
    val orbitPeriodSec: Float,
    /** How far left/right the moving layer travels, 0..1. */
    val panDepth: Float,
    /** How much spatialized signal is added to the untouched original. */
    val wetMix: Float,
    /** Crossover: below this the audio stays centred and dry. */
    val bassCenterHz: Float,
    /** Interaural time difference at full deflection, milliseconds. */
    val maxItdMs: Float,
    /** Low-pass applied to the far ear at full deflection. */
    val farEarLowPassHz: Float,
    /** Leak between ears, so the image is not unnaturally separated. */
    val crossfeed: Float,
    /** Ambience level. */
    val reverbWet: Float,
    /** Ambience decay, seconds. */
    val reverbDecaySec: Float,
    /** 0..1 — how much of the recording's own width to leave alone. High for already-wide mixes. */
    val sidePreservation: Float,
    /** 0..1 — how much short-term energy is allowed to modulate the movement. */
    val movementResponse: Float,
) {
    /**
     * The same profile with every field forced inside its safe range.
     *
     * Called at the end of *every* path that produces a profile — preset, intensity multiplier,
     * measured adaptation, cache read — so no route into the engine can deliver a value that makes the
     * effect unsafe. A cached profile from an older build is corrected here rather than trusted.
     */
    fun clamped(): SpatialAudioProfile = copy(
        orbitPeriodSec = orbitPeriodSec.coerceIn(MIN_ORBIT_SEC, MAX_ORBIT_SEC),
        panDepth = panDepth.coerceIn(0f, MAX_PAN_DEPTH),
        wetMix = wetMix.coerceIn(0f, MAX_WET_MIX),
        bassCenterHz = bassCenterHz.coerceIn(MIN_BASS_HZ, MAX_BASS_HZ),
        maxItdMs = maxItdMs.coerceIn(0f, MAX_ITD_MS),
        farEarLowPassHz = farEarLowPassHz.coerceIn(MIN_FAR_EAR_HZ, MAX_FAR_EAR_HZ),
        crossfeed = crossfeed.coerceIn(0f, MAX_CROSSFEED),
        reverbWet = reverbWet.coerceIn(0f, MAX_REVERB_WET),
        reverbDecaySec = reverbDecaySec.coerceIn(MIN_REVERB_DECAY_SEC, MAX_REVERB_DECAY_SEC),
        sidePreservation = sidePreservation.coerceIn(0f, 1f),
        movementResponse = movementResponse.coerceIn(0f, 1f),
    )

    companion object {
        /**
         * A full turn in three and a half seconds is fast. It is meant to be: below about six seconds
         * the movement stops reading as "wide" and starts reading as *travelling*, which is the whole
         * point of the effect and the thing a twenty-second orbit cannot deliver.
         */
        const val MIN_ORBIT_SEC = 3.5f
        const val MAX_ORBIT_SEC = 45f
        const val MAX_PAN_DEPTH = 1.0f

        /**
         * How much of the mix above the crossover is lifted out and sent travelling. Not a "wetness" in
         * the effects-send sense: at 1.0 **everything above the bass leaves its place in the mix** and
         * orbits the listener, and only the low end stays where the record put it.
         */
        const val MAX_WET_MIX = 1.0f
        const val MIN_BASS_HZ = 90f
        const val MAX_BASS_HZ = 200f

        /**
         * The hard ceiling on interaural delay. Human localisation tops out around 0.7 ms; past that the
         * ear stops hearing a *direction* and starts hearing a second arrival of the same sound, which
         * comb-filters against the first and makes the mix hollow and phasey. **This is not the knob
         * that makes the effect sound like an arena** — that is the ambience, which has a pre-delay and
         * a real tail. Nothing may raise this.
         */
        const val MAX_ITD_MS = 0.65f

        const val MIN_FAR_EAR_HZ = 1_800f
        const val MAX_FAR_EAR_HZ = 7_000f
        const val MAX_CROSSFEED = 0.12f
        const val MAX_REVERB_WET = 0.80f
        const val MIN_REVERB_DECAY_SEC = 0.25f

        /** Three and a half seconds of tail is a large hall or an arena, which is the point. */
        const val MAX_REVERB_DECAY_SEC = 3.50f
    }
}

/**
 * What was measured from the recording itself, upstream of the effect.
 *
 * Everything here is optional in spirit: a track can be recognised, played and spatialized on its genre
 * baseline alone. This only ever *refines*.
 */
@Serializable
data class SpatialAnalysis(
    /** Null when no tempo could be estimated with enough confidence to act on. */
    val tempoBpm: Float? = null,
    val tempoConfidence: Float = 0f,
    val rmsDb: Float = 0f,
    /** Peak-to-RMS in dB. Low means a dense, heavily-limited master. */
    val crestFactorDb: Float = 0f,
    /** -1..1. Near 1 is nearly mono; near 0 is wide; negative means out-of-phase content. */
    val stereoCorrelation: Float = 1f,
    /** 0..1, side energy relative to mid. */
    val stereoWidth: Float = 0f,
    val lowEnergy: Float = 0f,
    val midEnergy: Float = 0f,
    val highEnergy: Float = 0f,
    val spectralCentroidHz: Float? = null,
    /** Onsets per second — a density proxy. */
    val onsetRate: Float = 0f,
)

/** Why the effect is enabled but not currently doing anything. */
enum class SpatialInactiveReason {
    NONE,
    MODE_OFF,

    /** Mono or more than two channels: v1 spatializes plain stereo only. */
    UNSUPPORTED_CHANNEL_LAYOUT,
    UNSUPPORTED_PCM,

    /** Android is already applying spatial audio and "avoid double spatialization" is on. */
    SYSTEM_SPATIALIZER_ACTIVE,

    /**
     * Playing through the built-in speaker. The effect is built on interaural cues, which only exist
     * when each ear gets its own channel; over a speaker the same processing reads as phase weirdness
     * rather than space, so it waits for headphones instead of degrading the sound.
     */
    SPEAKER_OUTPUT,
    PROCESSOR_ERROR,
}

/** The single thing the UI renders. */
data class SpatialAudioState(
    val mode: SpatialAudioMode = SpatialAudioMode.OFF,
    /** Enabled *and* actually processing. False with a [inactiveReason] whenever a gate is closed. */
    val active: Boolean = false,
    val inactiveReason: SpatialInactiveReason = SpatialInactiveReason.MODE_OFF,
    val profileLabel: String? = null,
    /** True once the recording itself has been measured, as opposed to running on its genre baseline. */
    val analysisReady: Boolean = false,
) {
    val enabled: Boolean get() = mode != SpatialAudioMode.OFF

    /** Enabled, but a gate is holding it — the case the UI words as "waiting". */
    val waiting: Boolean get() = enabled && !active
}
