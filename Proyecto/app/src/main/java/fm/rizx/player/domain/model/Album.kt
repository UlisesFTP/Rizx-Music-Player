package fm.rizx.player.domain.model

import kotlinx.serialization.Serializable

/**
 * A full album — the heavier counterpart to [AlbumRef], carrying the actual [tracks]. Identity is
 * [source] (a [ProviderRef]); no `id` of its own (NUCLEAR_UPSTREAM_STUDY.md §2). Pure Kotlin,
 * Android-free. Fetched on demand via `MetadataProvider.albumDetail`.
 */
@Serializable
data class Album(
    val title: String,
    val artists: List<ArtistRef> = emptyList(),
    val year: Int? = null,
    /** Full release date (`YYYY-MM-DD`) when the provider gives one; [year] is the coarse fallback. */
    val releaseDateIso: String? = null,
    val artwork: ArtworkSet? = null,
    val tracks: List<Track> = emptyList(),
    val totalTracks: Int? = null,
    val durationMs: Long? = null,
    val source: ProviderRef,
)
