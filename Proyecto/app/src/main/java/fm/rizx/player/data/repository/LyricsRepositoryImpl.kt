package fm.rizx.player.data.repository

import fm.rizx.player.data.local.store.LyricsStore
import fm.rizx.player.domain.model.Lyrics
import fm.rizx.player.domain.model.LyricsCandidate
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.model.TrackLyrics
import fm.rizx.player.domain.provider.LyricsProvider
import fm.rizx.player.domain.provider.ProviderKind
import fm.rizx.player.domain.provider.ProviderRegistry
import fm.rizx.player.domain.repository.LyricsRepository
import kotlinx.coroutines.CancellationException

/**
 * Resolves lyrics from most-specific to most-general:
 *
 * 1. **The user's pinned pick** for this track — it outranks anything matching would produce.
 * 2. **The disk cache**, which also makes downloaded songs readable offline.
 * 3. **The active lyrics provider** (LRCLIB, the only one with timings).
 * 4. **The remaining providers** (lyrics.ovh, prose only) when the active one has nothing.
 *
 * Step 4 mirrors [StreamingRepositoryImpl]'s active-first fallback chain rather than inventing new
 * dispatch: the registry stays single-active, and the chain lives in the repository. It matters because
 * LRCLIB is community-sourced — a song missing there may still exist as plain text elsewhere, and prose
 * beats an empty screen.
 *
 * Steps 1 and 2 are one lookup: a pin *is* a cache entry that is flagged and never evicted.
 */
class LyricsRepositoryImpl(
    private val registry: ProviderRegistry,
    /** Null in tests that don't exercise caching; then every lookup goes to the providers. */
    private val store: LyricsStore? = null,
) : LyricsRepository {

    override suspend fun lyricsFor(track: Track): TrackLyrics? {
        val key = track.source.identityKey
        store?.get(key)?.let { return TrackLyrics(it.lyrics, it.offsetMs, it.pinned) }

        val result = fetch(track)
        val lyrics = result.lyrics ?: return null
        // Don't cache a result the preferred provider never got to weigh in on. A transient LRCLIB outage
        // otherwise pins lyrics.ovh's prose to this track forever, and the song would never gain the timed
        // lyrics it actually has. Observed happening on the very first device run.
        if (!result.degraded) store?.put(key, lyrics)
        return TrackLyrics(lyrics)
    }

    override suspend fun search(query: String): List<LyricsCandidate> {
        for (provider in chain()) {
            val results = try {
                provider.searchLyrics(query)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                continue // a broken provider must not abort the search
            }
            if (results.isNotEmpty()) return results
        }
        return emptyList()
    }

    override suspend fun pin(track: Track, candidate: LyricsCandidate) {
        store?.put(track.source.identityKey, candidate.lyrics, pinned = true)
    }

    override suspend fun setOffset(track: Track, offsetMs: Long) {
        store?.setOffset(track.source.identityKey, offsetMs)
    }

    override suspend fun clearOverride(track: Track) {
        store?.remove(track.source.identityKey)
    }

    // ---- Internals ----

    /**
     * What the chain produced, and whether it had to route around a failure to get there.
     *
     * [degraded] is what keeps a temporary outage from becoming permanent: the answer is usable now, but
     * not good enough to write down.
     */
    private data class Fetched(val lyrics: Lyrics?, val degraded: Boolean)

    /**
     * Walks the chain and returns the first real result. A provider that throws is remembered but does
     * not stop the walk; the error is rethrown **only if every provider failed**, so one broken source
     * can't turn a genuine "no lyrics" into a false offline banner.
     */
    private suspend fun fetch(track: Track): Fetched {
        val chain = chain()
        var lastError: Exception? = null
        var anySucceeded = false
        for (provider in chain) {
            try {
                val lyrics = provider.getLyrics(track)
                anySucceeded = true
                if (lyrics != null && !lyrics.isEmpty) return Fetched(lyrics, degraded = lastError != null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        if (!anySucceeded && lastError != null) throw lastError
        return Fetched(null, degraded = lastError != null)
    }

    /** The active provider first, then the rest in registration order. */
    private fun chain(): List<LyricsProvider> {
        val all = registry.list(ProviderKind.LYRICS).filterIsInstance<LyricsProvider>()
        val active = registry.activeDescriptor(ProviderKind.LYRICS) as? LyricsProvider ?: return all
        return listOf(active) + all.filterNot { it.id == active.id }
    }
}
