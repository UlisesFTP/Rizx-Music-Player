package fm.rizx.player.playback.cache

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import java.util.TreeSet

/**
 * Least-recently-used eviction that **refuses to drop liked songs** while anything else can go.
 *
 * Media3 ships [androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor], but it is `final` and
 * knows nothing about which songs matter, so a big listening session would quietly evict the album you
 * play every day. Plain LRU is still the ordering — "most played" and "played recently" overlap almost
 * completely — with liked tracks held back as the tiebreaker the user already expressed.
 *
 * Protection is **soft on purpose**: if liked songs alone overflow the cache, they are evicted too. A
 * cache that cannot make room fails the write, and a failed write breaks playback — a worse outcome than
 * losing a cached copy that can be re-fetched.
 *
 * [isProtected] is called with the cache key (a `ProviderRef.identityKey`) and is read on the caching
 * thread, so its backing set must be safe to read concurrently.
 */
@UnstableApi
class ProtectedLruCacheEvictor(
    private val maxBytes: Long,
    private val isProtected: (String) -> Boolean,
) : CacheEvictor {

    private val leastRecentlyUsed = TreeSet(::compareByTouchTime)
    private var currentSize = 0L

    /** True: eviction order depends on access time, so spans must be touched on read. */
    override fun requiresCacheSpanTouches(): Boolean = true

    override fun onCacheInitialized() = Unit

    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        if (length != C.LENGTH_UNSET.toLong()) evict(cache, length)
    }

    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        leastRecentlyUsed.add(span)
        currentSize += span.length
        evict(cache, 0)
    }

    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        leastRecentlyUsed.remove(span)
        currentSize -= span.length
    }

    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        onSpanRemoved(cache, oldSpan)
        onSpanAdded(cache, newSpan)
    }

    /** Current tracked size, in bytes. Exposed for tests and diagnostics. */
    fun sizeBytes(): Long = currentSize

    private fun evict(cache: Cache, requiredSpace: Long) {
        // Two passes: everything unprotected goes before the first liked song does.
        evictOldest(cache, requiredSpace) { !isProtected(it.key) }
        evictOldest(cache, requiredSpace) { true }
    }

    private fun evictOldest(cache: Cache, requiredSpace: Long, accept: (CacheSpan) -> Boolean) {
        while (currentSize + requiredSpace > maxBytes) {
            val victim = leastRecentlyUsed.firstOrNull(accept) ?: return
            try {
                cache.removeSpan(victim)
            } catch (_: Cache.CacheException) {
                return
            }
            // removeSpan normally calls back into onSpanRemoved. If it didn't, currentSize would never
            // fall and this would spin forever — drop it by hand and stop trusting the callback.
            if (leastRecentlyUsed.contains(victim)) {
                onSpanRemoved(cache, victim)
                return
            }
        }
    }

    private companion object {
        /**
         * Oldest touch first. Ties fall back to [CacheSpan]'s own ordering (key, then position) so the
         * set never collapses two distinct spans that happen to share a timestamp.
         */
        fun compareByTouchTime(a: CacheSpan, b: CacheSpan): Int {
            val diff = a.lastTouchTimestamp - b.lastTouchTimestamp
            return if (diff == 0L) a.compareTo(b) else if (diff < 0) -1 else 1
        }
    }
}
