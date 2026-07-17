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
