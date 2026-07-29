package fm.rizx.player.domain.model

import kotlinx.serialization.Serializable

/**
 * Lightweight references returned in search results and nested in heavier entities (kept light to
 * avoid recursive, heavy object graphs). All carry a [ProviderRef] [source] for identity.
 * See NUCLEAR_UPSTREAM_STUDY.md §2.
 */

/** A credited artist on a track/album, including roles (e.g. "main", "featured"). */
@Serializable
data class ArtistCredit(
    val name: String,
    val roles: List<String> = emptyList(),
    val source: ProviderRef? = null,
)

@Serializable
data class ArtistRef(
    val name: String,
    val disambiguation: String? = null,
    val artwork: ArtworkSet? = null,
    val source: ProviderRef,
    /**
     * How many people follow this profile, when the catalogue says.
     *
     * Not decoration: catalogues carry **several entries under one artist's name** — the real one plus
     * the thin duplicates a distributor created for a feature credit — and they are told apart by
     * nothing else. Search rank does not do it (Deezer returns a 27-follower "The Weeknd" above the
     * 14.6M one), so whoever picks between same-name candidates picks by this.
     */
    val followers: Long? = null,
)

@Serializable
data class AlbumRef(
    val title: String,
    val artists: List<ArtistRef> = emptyList(),
    val artwork: ArtworkSet? = null,
    val source: ProviderRef,
)

@Serializable
data class PlaylistRef(
    val id: String,
    val name: String,
    val artwork: ArtworkSet? = null,
    val source: ProviderRef,
    /** Number of tracks, when the source reports it (Deezer `nb_tracks`, YouTube stream count). */
    val trackCount: Int? = null,
)
