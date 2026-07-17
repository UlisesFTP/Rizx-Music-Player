package fm.rizx.player.domain.provider

import fm.rizx.player.domain.model.AlbumRef
import fm.rizx.player.domain.model.ArtistRef
import fm.rizx.player.domain.model.DashboardCapability
import fm.rizx.player.domain.model.PlaylistRef
import fm.rizx.player.domain.model.Track

/**
 * A dashboard/discovery provider (Phase 19): supplies Home-feed sections (charts, editorial, new
 * releases). A [ProviderDescriptor] of kind [ProviderKind.DASHBOARD] — a **multi-active fan-out** kind,
 * so the repository queries *all* registered dashboard providers, not a single active one. Each method
 * is capability-gated ([dashboardCapabilities]) and defaults to empty.
 */
interface DashboardProvider : ProviderDescriptor {
    val dashboardCapabilities: Set<DashboardCapability>

    suspend fun topTracks(limit: Int): List<Track> = emptyList()
    suspend fun topArtists(limit: Int): List<ArtistRef> = emptyList()
    suspend fun topAlbums(limit: Int): List<AlbumRef> = emptyList()
    suspend fun editorialPlaylists(limit: Int): List<PlaylistRef> = emptyList()
    suspend fun newReleases(limit: Int): List<AlbumRef> = emptyList()
}
