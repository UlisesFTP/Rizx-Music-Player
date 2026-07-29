package fm.rizx.player.data.artwork

import fm.rizx.player.domain.model.ArtworkSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Remembers which cover belongs to a `"<artist> <title>"` query, so the same song is never searched
 * twice.
 *
 * Two mechanisms, both aimed at the same N+1: a cold Home used to fire ~60–75 individual provider
 * searches, and the *same* song legitimately shows up in several rows (a chart, a mix, an artist radio).
 *
 *  - **Memoization.** An access-ordered LRU capped at [maxEntries], persisted as one JSON blob so a
 *    relaunch starts warm. Written with the atomic temp-then-rename idiom of `LyricsStore`.
 *  - **Single-flight.** Concurrent callers asking for the same key share one in-flight lookup
 *    (`groupcache`'s pattern) instead of each making its own request.
 *
 * **Misses are remembered for this process only.** A song no provider matched today may match tomorrow,
 * so a persisted "no cover" would make it permanently invisible — but re-searching it on every row of
 * the same session is pure waste.
 */
class ArtworkCache(
    private val file: File? = null,
    private val maxEntries: Int = MAX_ENTRIES,
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val lock = Mutex()

    /** `accessOrder = true` makes iteration order least-recently-used first, which is what [evict] wants. */
    private var hits: LinkedHashMap<String, ArtworkSet>? = null
    private val misses = LinkedHashSet<String>()
    private val inFlight = mutableMapOf<String, CompletableDeferred<ArtworkSet?>>()
    private var dirty = false

    /**
     * Returns the cover for [key], running [fetch] at most once across all concurrent callers and
     * remembering the answer. A [fetch] that fails resolves to `null` for everyone rather than
     * propagating — a broken provider must never fail an enrichment.
     */
    suspend fun get(key: String, fetch: suspend () -> ArtworkSet?): ArtworkSet? {
        cached(key)?.let { return it.value }

        val pending = lock.withLock {
            val existing = inFlight[key]
            if (existing != null) return@withLock existing to false
            CompletableDeferred<ArtworkSet?>().also { inFlight[key] = it } to true
        }
        val (deferred, isLeader) = pending
        if (!isLeader) return deferred.await()

        return try {
            val result = fetch()
            put(key, result)
            deferred.complete(result)
            result
        } catch (e: CancellationException) {
            // The followers must not inherit this leader's cancellation — they get "no cover" and move on.
            deferred.complete(null)
            throw e
        } catch (_: Exception) {
            deferred.complete(null)
            null
        } finally {
            lock.withLock { inFlight.remove(key) }
        }
    }

    /** Writes the memo out. Cheap and idempotent — call it once after a batch, not per lookup. */
    suspend fun flush() {
        val target = file ?: return
        val snapshot = lock.withLock {
            if (!dirty) return
            dirty = false
            loaded().toMap()
        }
        withContext(Dispatchers.IO) {
            runCatching {
                if (snapshot.isEmpty()) {
                    target.delete()
                    return@runCatching
                }
                val text = json.encodeToString(
                    PersistedArtwork.serializer(),
                    PersistedArtwork(snapshot.map { ArtworkEntry(it.key, it.value) }, EPOCH),
                )
                val tmp = File(target.parentFile, "${target.name}.tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(target)) {
                    target.writeText(text)
                    tmp.delete()
                }
            }
        }
    }

    // ---- Internals ----

    /** `null` = unknown; a wrapper holding `null` = a known miss. */
    private suspend fun cached(key: String): Wrapped? = lock.withLock {
        loaded()[key]?.let { return@withLock Wrapped(it) }
        if (key in misses) Wrapped(null) else null
    }

    private suspend fun put(key: String, artwork: ArtworkSet?) = lock.withLock {
        if (artwork == null || artwork.items.isEmpty()) {
            misses.add(key)
            if (misses.size > maxEntries) misses.iterator().let { it.next(); it.remove() }
            return@withLock
        }
        val map = loaded()
        map[key] = artwork
        misses.remove(key)
        dirty = true
        evict(map)
    }

    private fun evict(map: LinkedHashMap<String, ArtworkSet>) {
        while (map.size > maxEntries) {
            val oldest = map.keys.firstOrNull() ?: return
            map.remove(oldest)
        }
    }

    private fun loaded(): LinkedHashMap<String, ArtworkSet> = hits ?: read().also { hits = it }

    private fun read(): LinkedHashMap<String, ArtworkSet> {
        val map = LinkedHashMap<String, ArtworkSet>(INITIAL_CAPACITY, LOAD_FACTOR, true)
        val source = file ?: return map
        runCatching {
            if (!source.exists()) return@runCatching
            val stored = json.decodeFromString(PersistedArtwork.serializer(), source.readText())
            // A cache written by an older, wronger resolver is discarded whole rather than trusted.
            // Entries used to be keyed by a search string and accepted without verifying the match,
            // so the file can hold a remix's cover under the original's name — and it outlived every
            // restart. Bumping EPOCH is how that gets repaired on real installs.
            if (stored.epoch != EPOCH) return@runCatching
            stored.entries.forEach { map[it.key] = it.artwork }
        }
        return map
    }

    private class Wrapped(val value: ArtworkSet?)

    companion object {
        /**
         * Bump whenever the resolver's notion of a correct cover changes, to discard every entry the
         * old one wrote. 2 = owner-first resolution, verified borrowing, and keys that are
         * `ProviderRef` identities rather than search strings.
         */
        const val EPOCH = 2

        /** A few hundred covers is tens of KB of URLs and covers any realistic browsing session. */
        private const val MAX_ENTRIES = 800
        private const val INITIAL_CAPACITY = 64
        private const val LOAD_FACTOR = 0.75f
    }
}

// ---- On-disk shape (private) ----

@Serializable
private data class PersistedArtwork(val entries: List<ArtworkEntry>, val epoch: Int = 1)

@Serializable
private data class ArtworkEntry(val key: String, val artwork: ArtworkSet)
