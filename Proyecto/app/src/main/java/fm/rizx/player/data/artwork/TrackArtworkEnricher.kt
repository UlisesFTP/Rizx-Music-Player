package fm.rizx.player.data.artwork

import fm.rizx.player.domain.model.ArtworkSet
import fm.rizx.player.domain.model.SearchParams
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.model.coverUrl
import fm.rizx.player.domain.provider.MetadataProvider
import fm.rizx.player.domain.provider.ProviderKind
import fm.rizx.player.domain.provider.ProviderRegistry
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
 */
class TrackArtworkEnricher(
    private val registry: ProviderRegistry,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Concurrent lookups. Enough to keep a 100-track import quick without hammering the provider. */
    private val parallelism: Int = DEFAULT_PARALLELISM,
) {

    /**
     * Returns [tracks] with missing covers filled in. Tracks that already have artwork are left untouched
     * and cost nothing. Returns the input unchanged when there is nothing to do or no provider available.
     */
    suspend fun enrich(tracks: List<Track>): List<Track> {
        if (tracks.none { it.artwork.coverUrl() == null }) return tracks
        val providers = metadataProviders().ifEmpty { return tracks }

        return withContext(io) {
            val gate = Semaphore(parallelism)
            coroutineScope {
                tracks
                    .map { track ->
                        async {
                            if (track.artwork.coverUrl() != null) {
                                track
                            } else {
                                gate.withPermit { track.copy(artwork = lookup(track, providers)) }
                            }
                        }
                    }
                    .awaitAll()
            }
        }
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

    /** Searches each provider in turn for "artist title", returning the first usable cover. */
    private suspend fun lookup(track: Track, providers: List<MetadataProvider>): ArtworkSet? {
        val query = searchQuery(track) ?: return track.artwork
        for (provider in providers) {
            val match = runCatchingProvider {
                provider.search(SearchParams(query = query, limit = 1)).tracks.firstOrNull()
            }
            val artwork = match?.artwork?.takeIf { it.coverUrl() != null }
            if (artwork != null) return artwork
        }
        return track.artwork
    }

    /** `"<first artist> <title>"` — the same shape stream resolution already matches on. */
    private fun searchQuery(track: Track): String? {
        val title = track.title.trim().takeIf { it.isNotEmpty() } ?: return null
        val artist = track.artists.firstOrNull()?.name?.trim().orEmpty()
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

    private companion object {
        const val DEFAULT_PARALLELISM = 6
        const val COVER_PROBE_LIMIT = 5

        /** Deezer first: keyless, fast, and its covers are the best match for this app's look. */
        const val PREFERRED_PROVIDER_ID = "deezer"
    }
}
