package fm.rizx.player.data.artwork

import fm.rizx.player.data.remote.soundcloud.SoundcloudIds
import fm.rizx.player.data.remote.youtube.YoutubeIds
import fm.rizx.player.domain.model.ArtworkSet
import fm.rizx.player.domain.model.SearchParams
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.model.coverUrl
import fm.rizx.player.domain.provider.MetadataProvider
import fm.rizx.player.domain.provider.ProviderKind
import fm.rizx.player.domain.provider.ProviderRegistry
import fm.rizx.player.domain.usecase.ArtistNameMatching
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Fills in cover art that an import didn't come with.
 *
 * Spotify's public embed carries the playlist's own cover but **no per-track artwork at all** (verified
 * live: 0 of 50 rows), so imported Spotify tracks would otherwise render as blank tiles. Each track without
 * artwork is matched on a metadata provider by "artist title" and borrows that match's cover. The track's
 * **identity is never touched** — only [Track.artwork] is filled — so it still resolves to audio exactly as
 * before and keeps its Spotify `ProviderRef`.
 *
 * Providers are tried in registration order with Deezer first (best cover art, keyless); anything else
 * registered — e.g. YouTube — acts as the fallback for tracks Deezer can't match.
 *
 * Everything here degrades silently: a lookup that fails, times out or finds nothing leaves the track
 * exactly as it was. Enrichment is cosmetic, so it must never fail an import.
 *
 * Every lookup goes through [ArtworkCache], which memoizes the answer and collapses concurrent requests
 * for the same song into one. This is the difference between a Home load costing ~60 provider searches
 * and costing a handful: the charts, the mixes and the artist radios overlap heavily, and a relaunch
 * starts from the persisted memo.
 */
class TrackArtworkEnricher(
    private val registry: ProviderRegistry,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Concurrent lookups. Enough to keep a 100-track import quick without hammering the provider. */
    private val parallelism: Int = DEFAULT_PARALLELISM,
    private val cache: ArtworkCache = ArtworkCache(),
) {

    /**
     * Returns [tracks] with missing covers filled in. Tracks that already have artwork are left untouched
     * and cost nothing. Returns the input unchanged when there is nothing to do or no provider available.
     *
     * [upgradeFrom] names providers whose artwork is *present but poor* and should be replaced rather
     * than kept — YouTube being the case that matters: its "cover" is a 16:9 video still, so a queue
     * filled from a YT Mix would show letterboxed screenshots next to Deezer's square covers. Those
     * tracks get looked up like an artwork-less one, and keep their original image if nothing matches.
     */
    suspend fun enrich(tracks: List<Track>, upgradeFrom: Set<String> = emptySet()): List<Track> {
        fun needsLookup(track: Track) =
            track.artwork.coverUrl() == null || track.source.provider in upgradeFrom

        if (tracks.none(::needsLookup)) return tracks
        val providers = metadataProviders().ifEmpty { return tracks }

        return withContext(io) {
            val gate = Semaphore(parallelism)
            coroutineScope {
                tracks
                    .map { track ->
                        async {
                            if (!needsLookup(track)) {
                                track
                            } else {
                                gate.withPermit { track.copy(artwork = lookup(track, providers)) }
                            }
                        }
                    }
                    .awaitAll()
            }.also { cache.flush() }
        }
    }

    /**
     * The best cover URL for **one** track, for a screen that shows it large.
     *
     * A track whose own artwork is good is returned untouched and costs nothing. One whose artwork is
     * missing, or comes from a provider in [upgradeFrom], is looked up: a YouTube-sourced song carries a
     * 16:9 video still, which the full-screen player crops into a blurry band — the square Deezer cover
     * is what belongs there.
     *
     * Display-only: the queue keeps the track exactly as it is. Failures return whatever the track had.
     */
    suspend fun coverFor(track: Track, upgradeFrom: Set<String> = POOR_ARTWORK_PROVIDERS): String? {
        val own = track.artwork.coverUrl()
        if (own != null && track.source.provider !in upgradeFrom) return own
        val providers = metadataProviders().ifEmpty { return own }
        return withContext(io) { lookup(track, providers).coverUrl() ?: own }.also { cache.flush() }
    }

    /**
     * A cover for a whole playlist, for sources that don't supply one: the first track that has (or can
     * find) artwork wins. Only [probeLimit] tracks are probed — a playlist cover is one image, and the
     * first few tracks are the ones a user associates with the list anyway.
     */
    suspend fun playlistCover(tracks: List<Track>): String? {
        tracks.firstNotNullOfOrNull { it.artwork.coverUrl() }?.let { return it }
        val providers = metadataProviders().ifEmpty { return null }
        return withContext(io) {
            tracks.take(probeLimit).firstNotNullOfOrNull { lookup(it, providers).coverUrl() }
        }
    }

    /** The memoized, request-collapsing front of [search]; keeps the track's own art when nothing matches. */
    private suspend fun lookup(track: Track, providers: List<MetadataProvider>): ArtworkSet? {
        val query = searchQuery(track) ?: return track.artwork
        return cache.get(query.lowercase()) { search(query, providers) } ?: track.artwork
    }

    /** Searches each provider in turn for "artist title", returning the first usable cover. */
    private suspend fun search(query: String, providers: List<MetadataProvider>): ArtworkSet? {
        for (provider in providers) {
            val match = runCatchingProvider {
                provider.search(SearchParams(query = query, limit = 1)).tracks.firstOrNull()
            }
            val artwork = match?.artwork?.takeIf { it.coverUrl() != null }
            if (artwork != null) return artwork
        }
        return null
    }

    /**
     * `"<first artist> <title>"` — the same shape stream resolution already matches on, except the
     * artist is de-channelized first: a YouTube track credits "ModjoOfficial", and searching Deezer for
     * "ModjoOfficial Lady (Hear Me Tonight)" returns nothing at all (verified live), so the very tracks
     * that most need a real cover were the ones guaranteed not to find one.
     */
    private fun searchQuery(track: Track): String? {
        val title = track.title.trim().takeIf { it.isNotEmpty() } ?: return null
        val artist = track.artists.firstOrNull()?.name?.trim().orEmpty()
            .takeIf { it.isNotEmpty() }
            ?.let(ArtistNameMatching::searchName)
            .orEmpty()
        return listOf(artist, title).filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun metadataProviders(): List<MetadataProvider> =
        registry.list(ProviderKind.METADATA)
            .filterIsInstance<MetadataProvider>()
            .sortedByDescending { it.id == PREFERRED_PROVIDER_ID }

    /** Swallows provider failures (a broken provider must never fail an import) but honours cancellation. */
    private inline fun <T> runCatchingProvider(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private val probeLimit: Int get() = COVER_PROBE_LIMIT

    companion object {
        /**
         * Providers whose artwork exists but isn't a cover: YouTube gives a 16:9 video still and
         * SoundCloud a waveform/avatar. Both look wrong next to real square album art, so anything
         * showing a track large should ask for an upgrade.
         */
        val POOR_ARTWORK_PROVIDERS: Set<String> = setOf(YoutubeIds.STREAMING, SoundcloudIds.STREAMING)

        /**
         * Raised from 6 now that the cache absorbs the repeats: what is left is genuinely distinct
         * songs, and the providers behind it are keyless HTTP endpoints that tolerate this fine.
         */
        private const val DEFAULT_PARALLELISM = 10
        private const val COVER_PROBE_LIMIT = 5

        /** Deezer first: keyless, fast, and its covers are the best match for this app's look. */
        private const val PREFERRED_PROVIDER_ID = "deezer"
    }
}
