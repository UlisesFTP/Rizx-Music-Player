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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
    private val limits: DashboardFeedLimits = DashboardFeedLimits(),
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

    /**
     * The enabled dashboard sources, re-emitting whenever any of them is switched on or off.
     *
     * Built by combining each provider's own enabled flow rather than polling: the registry's set is
     * fixed for the process, so only the on/off state actually moves. Emits the ids sorted, so the
     * value is a stable identity — Home compares it as a cache key, and an order flip would look like
     * a change and refetch for nothing.
     */
    override fun activeSourceIds(): Flow<List<String>> {
        val ids = registry.list(ProviderKind.DASHBOARD).map { it.id }.sorted()
        if (ids.isEmpty()) return flowOf(emptyList())
        return combine(ids.map { id -> enabled.isEnabled(id).map { id to it } }) { pairs ->
            pairs.filter { it.second }.map { it.first }
        }.distinctUntilChanged()
    }

    private suspend fun contributionOf(p: DashboardProvider): Contribution = coroutineScope {
        val caps = p.dashboardCapabilities
        val tracks = async { safe(DashboardCapability.TOP_TRACKS in caps) { p.topTracks(limits.tracks) } }
        val artists = async { safe(DashboardCapability.TOP_ARTISTS in caps) { p.topArtists(limits.artists) } }
        val albums = async { safe(DashboardCapability.TOP_ALBUMS in caps) { p.topAlbums(limits.albums) } }
        // Playlists get a far higher ceiling than the other sections. They have their own tab, which
        // is an inventory rather than a preview, and a provider's whole catalogue of them is cheap:
        // Apple's 45 come from one hourly-cached page plus one RSS call, Deezer's from its existing
        // chart response. The chart sections keep the small limit — those are per-item work.
        val playlists = async { safe(DashboardCapability.EDITORIAL_PLAYLISTS in caps) { p.editorialPlaylists(limits.playlists) } }
        val releases = async { safe(DashboardCapability.NEW_RELEASES in caps) { p.newReleases(limits.releases) } }
        val featured = async { safe(DashboardCapability.FEATURED_PLAYLISTS in caps) { p.featuredPlaylists(limits.featured) } }
        val stations = async { safe(DashboardCapability.MOOD_STATIONS in caps) { p.moodStations(limits.stations) } }
        Contribution(
            p.id, p.name,
            tracks.await(), artists.await(), albums.await(), playlists.await(), releases.await(),
            featured.await(), stations.await(),
        )
    }

    /**
     * One section's fetch, bounded three ways: not declared → nothing; threw → nothing; **took too
     * long → nothing**.
     *
     * The timeout is the one that was missing, and it is what keeps the sources independent. Every
     * section of every provider is awaited before the feed is returned *and before the cache is
     * written*, so until now a single provider hanging on a bad network held the whole Home hostage —
     * Deezer's charts included — and left the cache unwritten, making the next cold start just as slow.
     * Only the JS plugin invoker had a timeout; the native providers had none.
     *
     * Sections run concurrently, so this doubles as the per-provider bound. Twelve seconds is chosen to
     * be well past a healthy call on a poor connection (cutting a slow-but-working source would show as
     * an empty row, which is worse than a slow one) while still bounding the worst case.
     */
    private suspend fun <T> safe(declared: Boolean, block: suspend () -> List<T>): List<T> =
        if (!declared) {
            emptyList()
        } else {
            try {
                withTimeoutOrNull(SECTION_TIMEOUT_MS) { block() } ?: emptyList()
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
        /** How long any one section may take before it is dropped. See [safe] for why it exists. */
        private const val SECTION_TIMEOUT_MS = 12_000L
    }
}

/** Internal feed-depth policy; previews apply their own smaller display cap in the UI. */
data class DashboardFeedLimits(
    val tracks: Int = 50,
    val artists: Int = 40,
    val albums: Int = 40,
    val playlists: Int = 60,
    val releases: Int = 30,
    val featured: Int = 2,
    val stations: Int = 100,
)
