package fm.rizx.player.playback.cache

import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The eviction policy is hand-written (Media3's LRU evictor is `final` and can't be taught about liked
 * songs), so its ordering and its escape hatch are worth pinning down.
 */
class ProtectedLruCacheEvictorTest {

    private lateinit var cache: Cache
    private val removed = mutableListOf<CacheSpan>()

    /** A fake cache whose `removeSpan` reports back to the evictor, exactly as SimpleCache does. */
    @Before
    fun setUp() {
        removed.clear()
        cache = mockk(relaxed = true)
    }

    private fun wire(evictor: ProtectedLruCacheEvictor) {
        val span = slot<CacheSpan>()
        every { cache.removeSpan(capture(span)) } answers {
            val victim = span.captured
            removed += victim
            evictor.onSpanRemoved(cache, victim)
        }
    }

    private fun span(key: String, position: Long, length: Long, touchedAt: Long): CacheSpan =
        CacheSpan(key, position, length, touchedAt, File("$key-$position"))

    @Test
    fun `evicts the least recently touched first`() {
        val evictor = ProtectedLruCacheEvictor(maxBytes = 100) { false }
        wire(evictor)

        evictor.onSpanAdded(cache, span("old", 0, 60, touchedAt = 1_000))
        evictor.onSpanAdded(cache, span("new", 0, 60, touchedAt = 2_000))

        assertEquals(listOf("old"), removed.map { it.key })
        assertEquals(60L, evictor.sizeBytes())
    }

    @Test
    fun `a liked song survives while anything else can go`() {
        val evictor = ProtectedLruCacheEvictor(maxBytes = 100) { it == "liked" }
        wire(evictor)

        // The liked span is the oldest, so plain LRU would drop it first.
        evictor.onSpanAdded(cache, span("liked", 0, 60, touchedAt = 1_000))
        evictor.onSpanAdded(cache, span("other", 0, 60, touchedAt = 2_000))

        assertEquals(listOf("other"), removed.map { it.key })
    }

    @Test
    fun `protection yields rather than letting the cache overflow`() {
        // If liked songs alone exceed the limit they must still be evicted: a cache that can't make room
        // fails the write, and a failed write stops playback — worse than losing a re-fetchable copy.
        val evictor = ProtectedLruCacheEvictor(maxBytes = 100) { true }
        wire(evictor)

        evictor.onSpanAdded(cache, span("a", 0, 60, touchedAt = 1_000))
        evictor.onSpanAdded(cache, span("b", 0, 60, touchedAt = 2_000))

        assertEquals(listOf("a"), removed.map { it.key })
        assertTrue(evictor.sizeBytes() <= 100)
    }

    @Test
    fun `makes room up front for a file it is about to write`() {
        val evictor = ProtectedLruCacheEvictor(maxBytes = 100) { false }
        wire(evictor)
        evictor.onSpanAdded(cache, span("a", 0, 80, touchedAt = 1_000))

        evictor.onStartFile(cache, "b", 0, 50)

        assertEquals(listOf("a"), removed.map { it.key })
    }

    @Test
    fun `a touch moves a span to the back of the queue`() {
        val evictor = ProtectedLruCacheEvictor(maxBytes = 120) { false }
        wire(evictor)
        val a = span("a", 0, 60, touchedAt = 1_000)
        val b = span("b", 0, 60, touchedAt = 2_000)
        evictor.onSpanAdded(cache, a)
        evictor.onSpanAdded(cache, b)

        // "a" gets played again, so "b" is now the stale one.
        evictor.onSpanTouched(cache, a, span("a", 0, 60, touchedAt = 3_000))
        evictor.onSpanAdded(cache, span("c", 0, 60, touchedAt = 4_000))

        assertEquals(listOf("b"), removed.map { it.key })
    }

    @Test
    fun `spans sharing a timestamp are both kept, not collapsed`() {
        // TreeSet dedupes by comparator, so a comparator that only looked at time would silently lose one.
        val evictor = ProtectedLruCacheEvictor(maxBytes = 1_000) { false }
        wire(evictor)

        evictor.onSpanAdded(cache, span("a", 0, 10, touchedAt = 5_000))
        evictor.onSpanAdded(cache, span("b", 0, 10, touchedAt = 5_000))

        assertEquals(20L, evictor.sizeBytes())
    }

    @Test
    fun `an unbounded cache never evicts`() {
        val evictor = ProtectedLruCacheEvictor(maxBytes = Long.MAX_VALUE) { false }
        wire(evictor)

        repeat(50) { i -> evictor.onSpanAdded(cache, span("k$i", 0, 1_000_000, touchedAt = i.toLong())) }

        assertTrue(removed.isEmpty())
    }

    @Test
    fun `eviction stops instead of spinning when removeSpan does not call back`() {
        // Guards the loop: without the fallback, currentSize would never fall and this would hang.
        val evictor = ProtectedLruCacheEvictor(maxBytes = 10) { false }
        every { cache.removeSpan(any()) } answers { }

        evictor.onSpanAdded(cache, span("a", 0, 60, touchedAt = 1_000))

        assertEquals(0L, evictor.sizeBytes())
    }
}
