package fm.rizx.player.domain.model

/** Which dashboard sections a provider can supply (Phase 19). */
enum class DashboardCapability { TOP_TRACKS, TOP_ARTISTS, TOP_ALBUMS, EDITORIAL_PLAYLISTS, NEW_RELEASES }

/**
 * A section's items **attributed** to the dashboard provider that produced them. Dashboard is a
 * multi-provider **fan-out** kind (NUCLEAR_UPSTREAM_STUDY.md §4/§5): each active provider contributes an
 * [AttributedResult] per section, so the UI can group/label by source.
 */
data class AttributedResult<T>(
    val providerId: String,
    val providerName: String,
    val items: List<T>,
)

/**
 * The Home feed: sections aggregated by fan-out over all active dashboard providers. Each section is a
 * list of [AttributedResult]s (one per contributing provider). Empty sections are simply absent.
 */
data class HomeFeed(
    val topTracks: List<AttributedResult<Track>> = emptyList(),
    val topArtists: List<AttributedResult<ArtistRef>> = emptyList(),
    val topAlbums: List<AttributedResult<AlbumRef>> = emptyList(),
    val editorialPlaylists: List<AttributedResult<PlaylistRef>> = emptyList(),
    val newReleases: List<AttributedResult<AlbumRef>> = emptyList(),
) {
    val isEmpty: Boolean
        get() = topTracks.isEmpty() && topArtists.isEmpty() && topAlbums.isEmpty() &&
            editorialPlaylists.isEmpty() && newReleases.isEmpty()
}
