package fm.rizx.player.domain.model

import kotlinx.serialization.Serializable

/** Which dashboard sections a provider can supply (Phase 19). */
enum class DashboardCapability { TOP_TRACKS, TOP_ARTISTS, TOP_ALBUMS, EDITORIAL_PLAYLISTS, NEW_RELEASES, MOOD_STATIONS, FEATURED_PLAYLISTS }

/**
 * A mood/genre station ("Chill Out", "¡Fiesta!", Pop…) as the provider curates it — the provider also
 * localizes the titles, which is why this carries a display string rather than a mood enum of ours.
 * [id] is the provider's own station id; the provider that supplied it (via [AttributedResult]) is the
 * one that can resolve it to tracks, so the pair travels together to [stationTracks].
 */
@Serializable
data class MoodStation(
    val id: String,
    val title: String,
    val artworkUrl: String? = null,
)

/**
 * An editorial playlist promoted to a full card: the ref plus the first few tracks, so the card can
 * show what is inside before the user commits to opening it. The preview is a *peek*, not the list —
 * playing the playlist still fetches the real tracklist.
 */
@Serializable
data class FeaturedPlaylist(
    val playlist: PlaylistRef,
    val preview: List<Track> = emptyList(),
)

/**
 * A section's items **attributed** to the dashboard provider that produced them. Dashboard is a
 * multi-provider **fan-out** kind (NUCLEAR_UPSTREAM_STUDY.md §4/§5): each active provider contributes an
 * [AttributedResult] per section, so the UI can group/label by source.
 *
 * Serializable so the whole feed can be cached to disk — the items it carries ([Track], [ArtistRef],
 * [AlbumRef], [PlaylistRef]) already are, and none of them holds an ephemeral stream URL.
 */
@Serializable
data class AttributedResult<T>(
    val providerId: String,
    val providerName: String,
    val items: List<T>,
)

/**
 * The Home feed: sections aggregated by fan-out over all active dashboard providers. Each section is a
 * list of [AttributedResult]s (one per contributing provider). Empty sections are simply absent.
 */
@Serializable
data class HomeFeed(
    val topTracks: List<AttributedResult<Track>> = emptyList(),
    val topArtists: List<AttributedResult<ArtistRef>> = emptyList(),
    val topAlbums: List<AttributedResult<AlbumRef>> = emptyList(),
    val editorialPlaylists: List<AttributedResult<PlaylistRef>> = emptyList(),
    val newReleases: List<AttributedResult<AlbumRef>> = emptyList(),
    val featured: List<AttributedResult<FeaturedPlaylist>> = emptyList(),
    val stations: List<AttributedResult<MoodStation>> = emptyList(),
) {
    val isEmpty: Boolean
        get() = topTracks.isEmpty() && topArtists.isEmpty() && topAlbums.isEmpty() &&
            editorialPlaylists.isEmpty() && newReleases.isEmpty() &&
            featured.isEmpty() && stations.isEmpty()
}
