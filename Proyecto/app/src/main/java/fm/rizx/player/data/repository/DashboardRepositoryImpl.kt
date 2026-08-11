package fm.rizx.player.data.repository

import fm.rizx.player.data.local.settings.SettingsRepositoryImpl
import fm.rizx.player.data.provider.PlaylistUrls
import fm.rizx.player.domain.model.AttributedResult
import fm.rizx.player.domain.model.PlaylistRef
import fm.rizx.player.domain.provider.PlaylistProvider
import fm.rizx.player.domain.model.DashboardCapability
import fm.rizx.player.domain.model.GenreFeed
import fm.rizx.player.domain.model.HomeFeed
import fm.rizx.player.domain.model.MoodStation
import fm.rizx.player.domain.model.Track
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
 *
 * [selection] narrows the fan-out to a single source when the user picked one in Settings. It stays a
 * filter here rather than `registry.setActive` because DASHBOARD is a multi-active kind by design
 * (ADR 0012) — its active slot is inert, and using it would quietly break the per-source toggles.
 * A selection naming a provider that isn't registered (uninstalled plugin) falls back to the blend
 * rather than an empty Home.
 */
class DashboardRepositoryImpl(
    private val registry: ProviderRegistry,
    private val enabled: EnabledProviderStore,
    private val limit: Int = DEFAULT_LIMIT,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val selection: suspend () -> String = { SettingsRepositoryImpl.FEED_PROVIDER_ALL },
) : DashboardRepository {

    /**
     * On [io], not the caller's thread. `coroutineScope` alone inherits whatever the ViewModel is on —
     * the main thread — so the DataStore read, the DTO→domain mapping of every chart and each
     * provider's own work all ran there, janking the very frames the loading indicator needed.
     */
    override suspend fun homeFeed(): HomeFeed = withContext(io) {
        val all = registry.list(ProviderKind.DASHBOARD).filterIsInstance<DashboardProvider>()
        val enabledMap = enabled.snapshot(all.map { it.id })
        val chosen = runCatching { selection() }.getOrDefault(SettingsRepositoryImpl.FEED_PROVIDER_ALL)
        val providers = all
            .filter { enabledMap[it.id] != false } // absent/true = enabled
            // A single-source feed ignores the enable toggles' fan-out and asks only that provider —
            // unless it has gone away, in which case blending beats showing nothing.
            .let { list -> if (chosen == SettingsRepositoryImpl.FEED_PROVIDER_ALL) list else list.filter { it.id == chosen }.ifEmpty { list } }
        val contribs = providers.map { p -> async { contributionOf(p) } }.awaitAll()
        val canOpen = playlistOpeners()
        HomeFeed(
            topTracks = contribs.attributed { it.topTracks },
            topArtists = contribs.attributed { it.topArtists },
            topAlbums = contribs.attributed { it.topAlbums },
            // A playlist card that opens empty is worse than no card, and the two sides are separate
            // registry entries: a dashboard can offer YouTube playlists while YouTube Playlists is
            // disabled in Sources. So anything nothing can open is dropped before it is ever drawn.
            editorialPlaylists = contribs.attributed { c -> c.editorialPlaylists.filter(canOpen) },
            newReleases = contribs.attributed { it.newReleases },
            // Same rule for the featured cards — their body tap opens the playlist detail.
            featured = contribs.attributed { c -> c.featured.filter { canOpen(it.playlist) } },
            stations = contribs.attributed { it.stations },
        )
    }

    /** Just the stations section, fanned out and attributed — see [DashboardRepository.moodStations]. */
    override suspend fun moodStations(limit: Int): List<AttributedResult<MoodStation>> = withContext(io) {
        val all = registry.list(ProviderKind.DASHBOARD).filterIsInstance<DashboardProvider>()
        val enabledMap = enabled.snapshot(all.map { it.id })
        val providers = all.filter {
            enabledMap[it.id] != false && DashboardCapability.MOOD_STATIONS in it.dashboardCapabilities
        }
        providers
            .map { p -> async { p to safe(true) { p.moodStations(limit) } } }
            .awaitAll()
            .mapNotNull { (p, items) -> items.takeIf { it.isNotEmpty() }?.let { AttributedResult(p.id, p.name, it) } }
    }

    /** Routed to the provider that supplied the station — its id rode along in the [AttributedResult]. */
    override suspend fun stationTracks(providerId: String, stationId: String, limit: Int): List<Track> =
        withContext(io) {
            val provider = registry.list(ProviderKind.DASHBOARD)
                .filterIsInstance<DashboardProvider>()
                .firstOrNull { it.id == providerId }
            safe(provider != null) { provider!!.stationTracks(stationId, limit) }
        }

    /**
     * The first **enabled** genre-capable provider with something to say about [genreId]. A provider
     * that doesn't own the id space answers empty, so "first non-empty" is also "first that recognises
     * it" — and a failure is just another empty answer, never the user's problem.
     */
    override suspend fun genreFeed(genreId: String, limit: Int): GenreFeed = withContext(io) {
        val all = registry.list(ProviderKind.DASHBOARD).filterIsInstance<DashboardProvider>()
        val enabledMap = enabled.snapshot(all.map { it.id })
        val capable = all.filter {
            enabledMap[it.id] != false && DashboardCapability.GENRE_FEED in it.dashboardCapabilities
        }
        for (provider in capable) {
            val feed = try {
                provider.genreFeed(genreId, limit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                continue
            }
            if (!feed.isEmpty) return@withContext feed
        }
        GenreFeed()
    }

    /** A predicate over playlist refs: true when some **enabled** playlist provider can fetch it. */
    private suspend fun playlistOpeners(): (PlaylistRef) -> Boolean {
        val all = registry.list(ProviderKind.PLAYLISTS).filterIsInstance<PlaylistProvider>()
        if (all.isEmpty()) return { false }
        val enabledMap = enabled.snapshot(all.map { it.id })
        val usable = all.filter { enabledMap[it.id] != false }
        return { ref ->
            val url = PlaylistUrls.canonical(ref.source)
            url != null && usable.any { runCatching { it.canHandle(url) }.getOrDefault(false) }
        }
    }

    private suspend fun contributionOf(p: DashboardProvider): Contribution = coroutineScope {
        val caps = p.dashboardCapabilities
        val tracks = async { safe(DashboardCapability.TOP_TRACKS in caps) { p.topTracks(limit) } }
        val artists = async { safe(DashboardCapability.TOP_ARTISTS in caps) { p.topArtists(limit) } }
        val albums = async { safe(DashboardCapability.TOP_ALBUMS in caps) { p.topAlbums(limit) } }
        // Playlists get a far higher ceiling than the other sections. They have their own tab, which
        // is an inventory rather than a preview, and a provider's whole catalogue of them is cheap:
        // Apple's 45 come from one hourly-cached page plus one RSS call, Deezer's from its existing
        // chart response. The chart sections keep the small limit — those are per-item work.
        val playlists = async { safe(DashboardCapability.EDITORIAL_PLAYLISTS in caps) { p.editorialPlaylists(PLAYLIST_LIMIT) } }
        val releases = async { safe(DashboardCapability.NEW_RELEASES in caps) { p.newReleases(limit) } }
        val featured = async { safe(DashboardCapability.FEATURED_PLAYLISTS in caps) { p.featuredPlaylists(FEATURED_LIMIT) } }
        val stations = async { safe(DashboardCapability.MOOD_STATIONS in caps) { p.moodStations(STATION_LIMIT) } }
        Contribution(
            p.id, p.name,
            tracks.await(), artists.await(), albums.await(), playlists.await(), releases.await(),
            featured.await(), stations.await(),
        )
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
        val topTracks: List<Track>,
        val topArtists: List<fm.rizx.player.domain.model.ArtistRef>,
        val topAlbums: List<fm.rizx.player.domain.model.AlbumRef>,
        val editorialPlaylists: List<PlaylistRef>,
        val newReleases: List<fm.rizx.player.domain.model.AlbumRef>,
        val featured: List<fm.rizx.player.domain.model.FeaturedPlaylist>,
        val stations: List<fm.rizx.player.domain.model.MoodStation>,
    )

    companion object {
        private const val DEFAULT_LIMIT = 12

        /** Enough for every country chart plus a platform's curated rows, per provider. */
        private const val PLAYLIST_LIMIT = 60

        /** Full-width cards — two is a shelf, five would be the whole Home. */
        private const val FEATURED_LIMIT = 2

        /**
         * The provider's whole station list. Home draws a preview of it and puts the rest behind
         * "See all", so capping here would only hide stations from that screen too — and they all
         * arrive in the single `/radio/lists` call the section already makes.
         */
        private const val STATION_LIMIT = 100
    }
}
