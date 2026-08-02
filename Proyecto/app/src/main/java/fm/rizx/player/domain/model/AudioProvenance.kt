package fm.rizx.player.domain.model

import kotlinx.serialization.Serializable

/**
 * Where a playing file came from, and therefore **how much the app is entitled to claim about it**.
 *
 * This exists because "the container is a valid FLAC" and "this is a lossless master" are different
 * statements, and only the first one is checkable. A FLAC re-encoded from a 128 kbps AAC parses
 * perfectly, reports 16-bit/44.1 kHz honestly, and sounds like a 128 kbps AAC. Proving otherwise would
 * take decoding the audio and looking for a spectral cliff, which this app does not do.
 *
 * So the technical readout says what was measured, and this says who vouches for it — which for a
 * community index is nobody.
 */
@Serializable
enum class AudioProvenance {
    /** Straight from the provider that owns the recording (Audius, SoundCloud, YouTube, iTunes). */
    OFFICIAL_PROVIDER,

    /** A file already on the device — the local library scan. */
    USER_LOCAL,

    /** A server the user configured and controls. */
    USER_SERVER,

    /**
     * A third-party index the user chose to install. The container can be verified; the origin,
     * the licence and whether it was ever transcoded cannot. The UI must say so.
     */
    COMMUNITY_UNVERIFIED,
}
