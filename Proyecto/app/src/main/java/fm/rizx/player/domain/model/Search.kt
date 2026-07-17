package fm.rizx.player.domain.model

/** A requestable result category. */
enum class SearchCategory { ARTISTS, ALBUMS, TRACKS, PLAYLISTS }

/**
 * What a metadata provider can search: specific categories, or [UNIFIED] (a single combined call
 * that returns several categories at once). See NUCLEAR_UPSTREAM_STUDY.md §4.
 */
enum class SearchCapability { ARTISTS, ALBUMS, TRACKS, PLAYLISTS, UNIFIED }

/** Detail lookups a metadata provider can serve beyond search (Phase 17). */
enum class DetailCapability { ALBUM_DETAIL, ARTIST_DETAIL }

/** Parameters for a search. [types] null = all the provider supports; [limit] null = provider default. */
data class SearchParams(
    val query: String,
    val types: List<SearchCategory>? = null,
    val limit: Int? = null,
)

/**
 * Grouped search results. Tracks are **full** [Track]s (immediately playable); artists, albums and
 * playlists are lightweight refs. Each list defaults to empty so a provider only fills what it
 * returns. See NUCLEAR_UPSTREAM_STUDY.md §2.
 */
data class SearchResults(
    val artists: List<ArtistRef> = emptyList(),
    val albums: List<AlbumRef> = emptyList(),
    val tracks: List<Track> = emptyList(),
    val playlists: List<PlaylistRef> = emptyList(),
) {
    val isEmpty: Boolean
        get() = artists.isEmpty() && albums.isEmpty() && tracks.isEmpty() && playlists.isEmpty()
}
