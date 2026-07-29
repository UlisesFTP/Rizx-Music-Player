package fm.rizx.player.data.remote.deezer

import fm.rizx.player.core.concurrent.SingleFlight
import fm.rizx.player.domain.model.ArtistCredit
import fm.rizx.player.domain.usecase.ArtistNameMatching
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The one place Deezer's artist search is called, so the same question is only asked once.
 *
 * Three different things want it at almost the same moment when a song starts: the Search screen's
 * Artists tab, the player resolving whose profile the artist name should open, and the queue's radio
 * refill looking up the id its artist radio is keyed by. They used to fire identical
 * `search/artist?q=…` requests side by side; [SingleFlight] collapses them into one, and OkHttp's disk
 * cache (ten minutes on `api.deezer.com`) covers the repeats that come later.
 */
class DeezerArtistSearch(
    private val api: DeezerApi,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val ttlMs: Long = TTL_MS,
    private val missTtlMs: Long = MISS_TTL_MS,
) {

    private val flight = SingleFlight<String, List<DeezerArtistShortDto>>()
    private val lock = Mutex()
    private val memo = LinkedHashMap<String, Cached>()

    /**
     * Raw results, answered from the memo when it is fresh, otherwise fetched once however many callers
     * are waiting.
     *
     * The memo is what actually matters in practice. The player's artist lookup lives in a `mapLatest`,
     * so a queue change **cancels and restarts** it — and a restarted leader is a *new* leader that
     * [SingleFlight] cannot merge with the one it just replaced. Observed live: four requests for the
     * same artist in 250 ms, two of them cancelled. A short-lived answer sidesteps the whole race.
     *
     * A **miss** is remembered too, but only briefly ([missTtlMs]): it has to be, or the burst survives
     * for exactly the artists Deezer doesn't have — which is when it fires most. Keeping it short means
     * a transient failure costs a minute of silence, not ten.
     */
    suspend fun byName(query: String, limit: Int): List<DeezerArtistShortDto> {
        val key = "$query|$limit"
        lock.withLock { memo[key]?.takeIf { nowMs() - it.atMs < it.ttlMs } }?.let { return it.artists }

        return flight.run(key) { api.searchArtists(query, limit).data }
            .also { results ->
                lock.withLock {
                    memo[key] = Cached(nowMs(), results, if (results.isEmpty()) missTtlMs else ttlMs)
                    if (memo.size > MAX_MEMO) memo.keys.firstOrNull()?.let(memo::remove)
                }
            }
    }

    private class Cached(val atMs: Long, val artists: List<DeezerArtistShortDto>, val ttlMs: Long)

    /**
     * The raw Deezer artist id behind a credit: its own ref when it already has one, otherwise a name
     * lookup. See [idFor] for why the name needs work first.
     */
    suspend fun idFor(credit: ArtistCredit?): String? {
        credit?.source
            ?.takeIf { it.provider == DeezerIds.PROVIDER }
            ?.let { return DeezerIds.rawId(it) }
        return idFor(credit?.name)
    }

    /**
     * The raw Deezer artist id for a name.
     *
     * Everything Deezer can do *as a recommendation engine* — the artist radio, related artists, an
     * artist's albums — is keyed by artist id, and a track from elsewhere has only a name. For a
     * YouTube track that name is a **channel**: searching Deezer for "ModjoOfficial" returns nothing at
     * all (verified live) while "modjo" finds the band, so the name is de-channelized through
     * [ArtistNameMatching] first. A result is only accepted when it really is the same artist — a wrong
     * id would quietly seed the radio with someone else's music.
     *
     * Among the ones that *are* the same artist, the **most-followed** wins rather than the highest
     * ranked. Deezer keeps a duplicate row per artist for feature credits — a "The Weeknd" with 27
     * followers and no albums, returned above the 14.6M one — and seeding a radio off that entry
     * yields three songs and then nothing.
     */
    suspend fun idFor(name: String?): String? {
        val artist = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        for (query in ArtistNameMatching.queries(artist)) {
            val hit = try {
                byName(query, ARTIST_SEARCH_LIMIT)
                    .filter { it.id != null && ArtistNameMatching.sameArtist(it.name.orEmpty(), artist) }
                    .maxByOrNull { it.nbFan ?: -1L }
                    ?.id
                    ?.toString()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (hit != null) return hit
        }
        return null
    }

    private companion object {
        /**
         * Deep enough that the real artist is still in the page when tribute acts and the catalogue's
         * own duplicate rows outrank them. Matches `ResolveTrackArtistsUseCase`'s limit so the two
         * share this memo's entries instead of each fetching their own page.
         */
        const val ARTIST_SEARCH_LIMIT = 10

        /** Matches the OkHttp `max-age` on `api.deezer.com`: past this, the network is cheap again. */
        const val TTL_MS = 10 * 60_000L

        /** Long enough to absorb one song's worth of repeated asking, short enough to keep retrying. */
        const val MISS_TTL_MS = 60_000L
        const val MAX_MEMO = 100
    }
}
