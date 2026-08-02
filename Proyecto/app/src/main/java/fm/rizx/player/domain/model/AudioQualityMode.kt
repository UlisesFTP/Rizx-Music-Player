package fm.rizx.player.domain.model

/**
 * How hard the app should work for audio quality.
 *
 * This replaces a boolean (`hiResOutput`) that had quietly grown two jobs: it forced ExoPlayer's 32-bit
 * float output path *and* told the YouTube provider to pick the best codec. A third state can't be
 * expressed as a flag, so it became an enum — but the first two values keep the boolean's exact
 * behaviour, so nothing about existing playback changes.
 *
 * The ordering is deliberate: each mode is a superset of the one before it.
 */
enum class AudioQualityMode {
    /** The conservative path. Never consults a lossless index. */
    STANDARD,

    /**
     * Best compressed stream the providers can offer — Opus 160 k/48 kHz over AAC 128 k/44.1 kHz — plus
     * the float output path. What the old `hiResOutput = true` meant. Still never consults an index.
     */
    BEST_AVAILABLE,

    /**
     * Try for a real FLAC first, and fall back to [BEST_AVAILABLE] the moment one can't be *verified*.
     *
     * Never reached by migration: a mode that fetches from a source the user installed themselves has to
     * be chosen, not inherited from a boolean that meant something else.
     */
    LOSSLESS_PREFERRED,
    ;

    /** Whether a lossless index may be consulted at all. The single gate for "off does no requests". */
    val allowsLossless: Boolean get() = this == LOSSLESS_PREFERRED

    /** Whether providers should pick their best-sounding rendition. True for everything above standard. */
    val prefersBestCompressed: Boolean get() = this != STANDARD
}
