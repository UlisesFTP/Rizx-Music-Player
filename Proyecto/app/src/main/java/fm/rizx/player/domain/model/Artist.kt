package fm.rizx.player.domain.model

import kotlinx.serialization.Serializable

/**
 * A full artist — the heavier counterpart to [ArtistRef], carrying [topTracks] and [albums]. Identity
 * is [source] (a [ProviderRef]). Pure Kotlin, Android-free. Fetched on demand via
 * `MetadataProvider.artistDetail`.
 */
@Serializable
data class Artist(
    val name: String,
    val bio: String? = null,
    val artwork: ArtworkSet? = null,
    val topTracks: List<Track> = emptyList(),
    val albums: List<AlbumRef> = emptyList(),
    val followers: Long? = null,
    val source: ProviderRef,
)
