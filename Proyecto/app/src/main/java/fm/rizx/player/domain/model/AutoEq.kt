package fm.rizx.player.domain.model

import kotlinx.serialization.Serializable

/**
 * The genre families the automatic equalizer knows how to shape.
 *
 * A **family**, not a catalogue genre: Deezer says "Rap/Hip Hop", Apple says "Hip-Hop/Rap" and in
 * Spanish "Reggaetón y hip-hop", and all of them want the same curve. Catalogue strings are folded onto
 * these by [fm.rizx.player.domain.usecase.GenreClassifier]; [UNKNOWN] is the honest answer when nothing
 * matched, and it shapes nothing at all.
 *
 * Serialized **by name** (it is cached per track), so reordering this list later can never silently turn
 * one family's stored curve into another's.
 */
@Serializable
enum class SoundGenre {
    REGGAETON,
    HIPHOP,
    ELECTRONIC,
    REGGAE_DANCEHALL,
    POP,
    ROCK,
    METAL,
    INDIE_ALT,
    LATIN_REGIONAL,
    LATIN_TROPICAL,
    RNB_SOUL,
    KPOP_JPOP,
    SOUNDTRACK,
    ACOUSTIC_FOLK,
    COUNTRY,
    JAZZ,
    CLASSICAL,
    LOFI_CHILL,
    SPOKEN,
    UNKNOWN,
}

/**
 * One band of the device's equalizer, as the auto EQ needs to see it.
 *
 * [lowHz]/[highHz] are the band's real edges (`Equalizer.getBandFreqRange`), which matter because a
 * 5-band device's second band spans roughly 120–450 Hz: shaping it from its 230 Hz *center* alone would
 * be answering a question about a whole octave with a single point. [centerHz] is the fallback for a
 * device that reports an unusable range.
 */
data class EqBandRange(val index: Int, val centerHz: Int, val lowHz: Int, val highHz: Int)

/**
 * What the automatic equalizer decided for one track.
 *
 * [curveDb] is per **device band**, in dB, already mean-zeroed and boost-trimmed. [label] is the
 * catalogue's own genre name ("Música Mexicana") kept for display — showing what the source said needs
 * no translation table, and it is also the user's way to see *why* a song sounds the way it does.
 * [adapted] is true once the song's own measured spectrum has refined the genre curve.
 */
data class AutoEqDecision(
    val genre: SoundGenre,
    val label: String?,
    val curveDb: List<Float>,
    val adapted: Boolean,
)
