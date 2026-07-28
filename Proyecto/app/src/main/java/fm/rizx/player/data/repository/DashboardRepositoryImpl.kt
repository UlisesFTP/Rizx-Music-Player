package fm.rizx.player.data.repository

import fm.rizx.player.domain.model.AttributedResult
import fm.rizx.player.domain.model.DashboardCapability
import fm.rizx.player.domain.model.HomeFeed
import fm.rizx.player.domain.provider.DashboardProvider
import fm.rizx.player.domain.provider.EnabledProviderStore
import fm.rizx.player.domain.provider.ProviderKind
import fm.rizx.player.domain.provider.ProviderRegistry
import fm.rizx.player.domain.repository.DashboardRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Fans out over the **enabled** registered dashboard providers to assemble the [HomeFeed] (Phase 19;
 * enabled-filter Phase 21). Each provider contributes concurrently, and each section call is isolated
 * ([safe]) so one failing/slow section degrades to empty without dropping the rest — "a broken provider
 * must never crash the app".
 */
class DashboardRepositoryImpl(
    private val registry: ProviderRegistry,
    private val enabled: EnabledProviderStore,
    private val limit: Int = DEFAULT_LIMIT,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : DashboardRepository {

    /**
     * On [io], not the caller's thread. `coroutineScope` alone inherits whatever the ViewModel is on —
     * the main thread — so the DataStore read, the DTO→domain mapping of every chart and each
     * provider's own work all ran there, janking the very frames the loading indicator needed.
     */
    override suspend fun homeFeed(): HomeFeed = withContext(io) {
        val all = registry.list(ProviderKind.DASHBOARD).filterIsInstance<DashboardProvider>()
        val enabledMap = enabled.snapshot(all.map { it.id })
        val providers = all.filter { enabledMap[it.id] != false } // absent/true = enabled
        val contribs = providers.map { p -> async { contributionOf(p) } }.awaitAll()
        HomeFeed(
            topTracks = contribs.attributed { it.topTracks },
            topArtists = contribs.attributed { it.topArtists },
            topAlbums = contribs.attributed { it.topAlbums },
            editorialPlaylists = contribs.attributed { it.editorialPlaylists },
            newReleases = contribs.attributed { it.newReleases },
        )
    }

    private suspend fun contributionOf(p: DashboardProvider): Contribution = coroutineScope {
        val caps = p.dashboardCapabilities
        val tracks = async { safe(DashboardCapability.TOP_TRACKS in caps) { p.topTracks(limit) } }
        val artists = async { safe(DashboardCapability.TOP_ARTISTS in caps) { p.topArtists(limit) } }
        val albums = async { safe(DashboardCapability.TOP_ALBUMS in caps) { p.topAlbums(limit) } }
        val playlists = async { safe(DashboardCapability.EDITORIAL_PLAYLISTS in caps) { p.editorialPlaylists(limit) } }
        val releases = async { safe(DashboardCapability.NEW_RELEASES in caps) { p.newReleases(limit) } }
        Contribution(p.id, p.name, tracks.await(), artists.await(), albums.await(), playlists.await(), releases.await())
    }

    private suspend fun <T> safe(declared: Boolean, block: suspend () -> List<T>): List<T> =
        if (!declared) {
            emptyList()
        } else {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
        }

    private fun <T> List<Contribution>.attributed(select: (Contribution) -> List<T>): List<AttributedResult<T>> =
        mapNotNull { c -> select(c).takeIf { it.isNotEmpty() }?.let { AttributedResult(c.id, c.name, it) } }

    private class Contribution(
        val id: String,
        val name: String,
        val topTracks: List<fm.rizx.player.domain.model.Track>,
        val topArtists: List<fm.rizx.player.domain.model.ArtistRef>,
        val topAlbums: List<fm.rizx.player.domain.model.AlbumRef>,
        val editorialPlaylists: List<fm.rizx.player.domain.model.PlaylistRef>,
        val newReleases: List<fm.rizx.player.domain.model.AlbumRef>,
    )

    companion object {
        private const val DEFAULT_LIMIT = 12
    }
}
