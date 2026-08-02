package fm.rizx.player.data.lossless

import fm.rizx.player.domain.lossless.ValidatedLosslessStream
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers what the index said about a track, for a while.
 *
 * **Memory only, never persisted.** The URL inside a hit is a resolved stream URL, and this project's
 * rule is that those are never written down — not into a playlist, not into the download index, not
 * into a `Track`. A restart re-resolves; that is the correct cost.
 *
 * Four lifetimes, because the four answers are worth remembering for very different lengths of time:
 *
 * - a **hit** lasts six hours. Verifying one costs a round trip and 64 KiB, and the answer doesn't
 *   change: the file either is that recording or isn't.
 * - a **miss** lasts twenty minutes. Long enough that skipping around a playlist costs nothing, short
 *   enough that a song which missed because the network hiccuped gets another chance in the same sitting.
 * - an **invalid file** lasts an hour. Someone would have to re-upload it for that to change.
 * - an **error** lasts two minutes (five for a 403/404, which is at least a definite answer), because
 *   whatever caused it is usually already over.
 *
 * [key] carries an algorithm version, so tightening the matcher invalidates every remembered verdict
 * rather than leaving yesterday's looser decisions in place.
 */
class LosslessResolutionCache(
    private val now: () -> Long = System::currentTimeMillis,
    private val maxEntries: Int = MAX_ENTRIES,
) {

    /** Bounded LRU. A long listening session touches thousands of tracks; the verdicts must not pile up. */
    private val entries = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean =
            size > maxEntries
    }

    /**
     * Keys currently being resolved, so two queue items landing on the same track don't both go out.
     *
     * Single-flight matters more here than for most caches: a miss costs up to three ranged requests
     * against three different hosts, and the prefetch warms the next track while the current one is
     * still resolving.
     */
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /** The cache key for a track's content identity, versioned by the matching algorithm. */
    fun keyFor(identityKey: String): String = "$identityKey|v$ALGORITHM_VERSION"

    /** What is remembered for [key], or `null` if nothing usable is. Expired entries are dropped here. */
    @Synchronized
    fun get(key: String): Entry? {
        val entry = entries[key] ?: return null
        if (now() >= entry.expiresAtMs) {
            entries.remove(key)
            return null
        }
        return entry
    }

    @Synchronized
    fun putHit(key: String, stream: ValidatedLosslessStream) {
        entries[key] = Entry(stream, now() + HIT_TTL_MS)
    }

    /** No row in the index matched, or none of them verified. */
    @Synchronized
    fun putMiss(key: String) {
        entries[key] = Entry(null, now() + MISS_TTL_MS)
    }

    /** A row matched but the bytes behind it are not a usable FLAC. */
    @Synchronized
    fun putInvalid(key: String) {
        entries[key] = Entry(null, now() + INVALID_TTL_MS)
    }

    /** The lookup failed. [definite] for a 403/404 — an answer, just not a useful one. */
    @Synchronized
    fun putError(key: String, definite: Boolean = false) {
        entries[key] = Entry(null, now() + if (definite) DEFINITE_ERROR_TTL_MS else ERROR_TTL_MS)
    }

    @Synchronized
    fun invalidate(key: String) {
        entries.remove(key)
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    /** Claims [key] for this caller. `false` means somebody else is already resolving it. */
    fun beginResolve(key: String): Boolean = inFlight.add(key)

    fun endResolve(key: String) {
        inFlight.remove(key)
    }

    /** A remembered answer. [stream] is null for a miss, an invalid file or an error. */
    data class Entry(val stream: ValidatedLosslessStream?, val expiresAtMs: Long)

    companion object {
        /**
         * Bumped whenever the matcher's rules change. Old verdicts were reached under the old rules,
         * and a loosened one lingering for six hours is exactly the wrong thing to inherit.
         */
        const val ALGORITHM_VERSION = 1

        const val MAX_ENTRIES = 256

        const val HIT_TTL_MS = 6 * 60 * 60 * 1000L
        const val MISS_TTL_MS = 20 * 60 * 1000L
        const val INVALID_TTL_MS = 60 * 60 * 1000L
        const val ERROR_TTL_MS = 2 * 60 * 1000L
        const val DEFINITE_ERROR_TTL_MS = 5 * 60 * 1000L
    }
}
