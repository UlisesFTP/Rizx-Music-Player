package fm.rizx.player.domain.usecase

import fm.rizx.player.domain.model.SoundGenre
import fm.rizx.player.domain.model.SpatialAnalysis
import fm.rizx.player.domain.model.SpatialAudioProfile

/**
 * Turns "what kind of song is this" into "how should it move".
 *
 * Three stages, in this order, and the last one is not optional:
 *
 * ```
 * genre baseline → what the recording actually measured → clamps
 * ```
 *
 * **Keyed on [SoundGenre], not on genre strings.** The app already normalises every provider's spelling
 * — "Hip-Hop/Rap", "hiphop", "Trap Latino" — into that enum for the automatic equaliser, so a second
 * table of aliases here would be a second thing to keep correct and a second thing to disagree with.
 *
 * These numbers are product judgement, not physics. What makes them safe is that they live in one pure
 * table with one clamp at the exit, rather than scattered through the player as `when` branches.
 */
object SmartSpatialProfiles {

    /** A starting point plus how many beats one orbit should take, when the tempo is known. */
    data class Preset(val profile: SpatialAudioProfile, val beatsPerOrbit: Int)

    fun baseline(genre: SoundGenre): Preset = PRESETS[genre] ?: PRESETS.getValue(SoundGenre.UNKNOWN)

    /**
     * The finished profile for a song.
     *
     * There is deliberately **no intensity control**. Three strengths meant three things to explain and
     * two of them were wrong for whoever picked them; what people actually want from this is for it to
     * work, so the presets below are simply tuned to be worth switching on.
     *
     * @param analysis what was measured from the recording; null while it is still being listened to,
     *   in which case the genre baseline is used as-is. Playback never waits for this.
     */
    fun profileFor(genre: SoundGenre, analysis: SpatialAnalysis?): SpatialAudioProfile {
        val preset = baseline(genre)
        val adapted =
            if (analysis == null) preset.profile else preset.profile.adaptedTo(analysis, preset.beatsPerOrbit)
        return adapted.copy(label = label(genre, analysis != null)).clamped()
    }

    private fun label(genre: SoundGenre, measured: Boolean): String =
        genre.name.lowercase() + if (measured) "+measured" else ""

    /**
     * What the recording itself says, applied as gentle nudges rather than a rewrite.
     *
     * Each rule exists because of a specific way the effect goes wrong:
     *
     * - **A wide mix gets less of everything.** It already has the space this would add, and pushing it
     *   further is where a record starts sounding hollow in the middle.
     * - **A bass-heavy mix raises its crossover and calms down.** Trap and reggaetón live below 175 Hz;
     *   moving that around costs the track its weight and stops it summing to mono cleanly.
     * - **A dense, heavily-limited master gets less ambience and a slower orbit.** There is no room left
     *   in it, and adding movement to a wall of sound reads as wobble.
     * - **The tempo sets the orbit's speed and nothing else** — and only when the estimate is confident.
     *   A wrong BPM would have the sound racing around the listener's head; an absent one just leaves the
     *   genre's own period in place.
     */
    private fun SpatialAudioProfile.adaptedTo(analysis: SpatialAnalysis, beatsPerOrbit: Int): SpatialAudioProfile {
        var profile = this

        val width = analysis.stereoWidth.coerceIn(0f, 1f)
        if (width > WIDE_THRESHOLD) {
            val excess = (width - WIDE_THRESHOLD) / (1f - WIDE_THRESHOLD)
            profile = profile.copy(
                panDepth = profile.panDepth * (1f - 0.35f * excess),
                wetMix = profile.wetMix * (1f - 0.30f * excess),
                sidePreservation = (profile.sidePreservation + 0.45f * excess),
                crossfeed = profile.crossfeed * (1f + 0.5f * excess),
            )
        }

        val low = analysis.lowEnergy.coerceIn(0f, 1f)
        if (low > BASS_THRESHOLD) {
            val excess = (low - BASS_THRESHOLD) / (1f - BASS_THRESHOLD)
            profile = profile.copy(
                bassCenterHz = profile.bassCenterHz + 45f * excess,
                panDepth = profile.panDepth * (1f - 0.15f * excess),
                reverbWet = profile.reverbWet * (1f - 0.35f * excess),
            )
        }

        // Low crest factor means peaks and average have been squeezed together — a modern loud master.
        val dense = analysis.crestFactorDb in 0.1f..DENSE_CREST_DB || analysis.onsetRate > DENSE_ONSET_RATE
        profile = if (dense) {
            profile.copy(
                reverbWet = profile.reverbWet * 0.7f,
                orbitPeriodSec = profile.orbitPeriodSec * 1.15f,
                movementResponse = profile.movementResponse * 0.7f,
            )
        } else if (analysis.crestFactorDb > SPARSE_CREST_DB) {
            profile.copy(
                reverbWet = profile.reverbWet * 1.2f,
                orbitPeriodSec = profile.orbitPeriodSec * 1.1f,
            )
        } else {
            profile
        }

        val bpm = analysis.tempoBpm
        if (bpm != null && bpm > 0f && analysis.tempoConfidence >= TEMPO_CONFIDENCE) {
            profile = profile.copy(orbitPeriodSec = beatsPerOrbit * 60f / bpm)
        }

        return profile
    }

    private fun profile(
        orbitPeriodSec: Float,
        panDepth: Float,
        wetMix: Float,
        bassCenterHz: Float,
        reverbWet: Float,
        maxItdMs: Float = 0.35f,
        farEarLowPassHz: Float = 4_000f,
        // Crossfeed is deliberate crosstalk — a little of each ear's signal in the other, which is what
        // speakers do in a room and what headphones do not. It exists to take the edge off hard-panned
        // material, and it is also the one thing here working *against* the effect: bleed is exactly
        // what destroys interaural cues. Kept small enough to soften without undoing.
        crossfeed: Float = 0.03f,
        reverbDecaySec: Float = 0.6f,
        // How much of the recording's own width is carried through. It used to be low because anything
        // kept sat still and anchored the image; now that this layer orbits opposite the centre, a low
        // value just throws the mix's width away for nothing.
        sidePreservation: Float = 0.60f,
        movementResponse: Float = 0.5f,
    ) = SpatialAudioProfile(
        label = "",
        orbitPeriodSec = orbitPeriodSec,
        panDepth = panDepth,
        wetMix = wetMix,
        bassCenterHz = bassCenterHz,
        maxItdMs = maxItdMs,
        farEarLowPassHz = farEarLowPassHz,
        crossfeed = crossfeed,
        reverbWet = reverbWet,
        reverbDecaySec = reverbDecaySec,
        sidePreservation = sidePreservation,
        movementResponse = movementResponse,
    )

    /**
     * One row per genre the app can recognise. The families follow how the music is *mixed* rather than
     * how it is marketed: what matters here is where the weight sits and how much room the master left.
     */
    private val PRESETS: Map<SoundGenre, Preset> = mapOf(
        SoundGenre.POP to Preset(profile(5.5f, 0.95f, 0.90f, 130f, 0.46f, reverbDecaySec = 2.4f), 8),
        SoundGenre.KPOP_JPOP to Preset(profile(5.0f, 0.97f, 0.92f, 140f, 0.46f, reverbDecaySec = 2.3f), 8),
        SoundGenre.ELECTRONIC to Preset(profile(4.0f, 1.0f, 0.96f, 150f, 0.52f, reverbDecaySec = 3.0f), 8),
        SoundGenre.HIPHOP to Preset(profile(5.5f, 0.90f, 0.84f, 175f, 0.38f, reverbDecaySec = 2.0f), 8),
        SoundGenre.REGGAETON to Preset(profile(5.0f, 0.92f, 0.86f, 175f, 0.40f, reverbDecaySec = 2.1f), 8),
        SoundGenre.REGGAE_DANCEHALL to Preset(profile(5.5f, 0.92f, 0.86f, 170f, 0.44f, reverbDecaySec = 2.4f), 8),
        SoundGenre.LATIN_TROPICAL to Preset(profile(5.0f, 0.95f, 0.90f, 150f, 0.46f, reverbDecaySec = 2.3f), 8),
        SoundGenre.LATIN_REGIONAL to Preset(profile(6.0f, 0.90f, 0.84f, 130f, 0.46f, reverbDecaySec = 2.4f), 8),
        SoundGenre.RNB_SOUL to Preset(profile(6.0f, 0.90f, 0.86f, 140f, 0.48f, reverbDecaySec = 2.5f), 8),
        SoundGenre.ROCK to Preset(profile(6.0f, 0.92f, 0.86f, 135f, 0.42f, reverbDecaySec = 2.5f), 8),
        SoundGenre.INDIE_ALT to Preset(profile(6.0f, 0.95f, 0.88f, 130f, 0.46f, reverbDecaySec = 2.6f), 8),
        SoundGenre.METAL to Preset(profile(7.0f, 0.88f, 0.80f, 145f, 0.36f, reverbDecaySec = 2.1f), 16),
        SoundGenre.COUNTRY to Preset(profile(7.0f, 0.86f, 0.80f, 115f, 0.46f, reverbDecaySec = 2.5f), 16),
        SoundGenre.ACOUSTIC_FOLK to Preset(
            profile(8.0f, 0.85f, 0.78f, 105f, 0.48f, farEarLowPassHz = 3_600f, reverbDecaySec = 2.7f),
            16,
        ),
        SoundGenre.JAZZ to Preset(
            profile(8.0f, 0.86f, 0.80f, 110f, 0.50f, farEarLowPassHz = 3_600f, reverbDecaySec = 2.7f),
            16,
        ),
        // A concert hall: slower and wider than a club, and the tail is most of the character.
        SoundGenre.CLASSICAL to Preset(
            profile(11.0f, 0.80f, 0.72f, 100f, 0.58f, maxItdMs = 0.55f, farEarLowPassHz = 3_800f, reverbDecaySec = 3.3f),
            16,
        ),
        SoundGenre.SOUNDTRACK to Preset(profile(9.0f, 0.82f, 0.76f, 110f, 0.58f, reverbDecaySec = 3.3f), 16),
        SoundGenre.LOFI_CHILL to Preset(
            profile(8.5f, 0.97f, 0.94f, 110f, 0.62f, reverbDecaySec = 3.0f, sidePreservation = 0.35f),
            16,
        ),
        // Speech does not want to be moved around the listener's head; it wants to stay in front of
        // them and be understood. This stays close to off, on purpose — an arena is the last place you
        // want a podcast.
        SoundGenre.SPOKEN to Preset(
            profile(30f, 0.20f, 0.14f, 150f, 0.03f, maxItdMs = 0.15f, reverbDecaySec = 0.4f, movementResponse = 0.1f),
            32,
        ),
        SoundGenre.UNKNOWN to Preset(profile(6.0f, 0.92f, 0.86f, 130f, 0.44f, reverbDecaySec = 2.4f), 8),
    )

    /** Above this share of side energy, the recording is already doing the job itself. */
    private const val WIDE_THRESHOLD = 0.45f

    /** Above this share of energy below the crossover, the track is carried by its low end. */
    private const val BASS_THRESHOLD = 0.45f

    private const val DENSE_CREST_DB = 8f
    private const val SPARSE_CREST_DB = 14f
    private const val DENSE_ONSET_RATE = 6f

    /** Below this, the tempo estimate is a guess and the orbit keeps the genre's own period. */
    const val TEMPO_CONFIDENCE = 0.5f
}
