package fm.rizx.player.data.artwork

import fm.rizx.player.domain.model.Artwork
import fm.rizx.player.domain.model.ArtworkSet
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class ArtworkCacheTest {

    @get:Rule val temp = TemporaryFolder()

    private fun art(url: String) = ArtworkSet(listOf(Artwork(url = url)))

    @Test
    fun `the second lookup of the same song does not hit the provider`() = runBlocking {
        val cache = ArtworkCache()
        val calls = AtomicInteger()

        val first = cache.get("coldplay yellow") { calls.incrementAndGet(); art("a.jpg") }
        val second = cache.get("coldplay yellow") { calls.incrementAndGet(); art("b.jpg") }

        assertEquals(1, calls.get())
        assertEquals(first, second)
    }

    @Test
    fun `concurrent requests for the same song collapse into one lookup`() = runTest {
        // The Home asks for the same track from a chart, a mix and an artist radio at the same time.
        val cache = ArtworkCache()
        val calls = AtomicInteger()
        val release = CompletableDeferred<Unit>()

        val results = (1..5)
            .map {
                async {
                    cache.get("same song") {
                        calls.incrementAndGet()
                        release.await() // hold the leader so the other four pile up behind it
                        art("cover.jpg")
                    }
                }
            }
            .also { release.complete(Unit) }
            .awaitAll()

        assertEquals(1, calls.get())
        assertEquals(5, results.count { it == art("cover.jpg") })
    }

    @Test
    fun `a miss is remembered for the session but never written to disk`() = runBlocking {
        val file = File(temp.root, "artwork.json")
        val calls = AtomicInteger()
        val cache = ArtworkCache(file)

        assertNull(cache.get("nothing matches this") { calls.incrementAndGet(); null })
        assertNull(cache.get("nothing matches this") { calls.incrementAndGet(); null })
        cache.flush()

        assertEquals("the second miss went back out to the provider", 1, calls.get())
        // A persisted miss would make a song that gets artwork tomorrow permanently blank.
        assertEquals(false, file.exists())
    }

    @Test
    fun `a hit survives a restart`() = runBlocking {
        val file = File(temp.root, "artwork.json")
        ArtworkCache(file).apply {
            get("coldplay yellow") { art("cover.jpg") }
            flush()
        }

        val calls = AtomicInteger()
        val reopened = ArtworkCache(file).get("coldplay yellow") { calls.incrementAndGet(); art("other.jpg") }

        assertEquals(0, calls.get())
        assertEquals(art("cover.jpg"), reopened)
    }

    @Test
    fun `a failing lookup answers null instead of propagating`() = runBlocking {
        // Enrichment is cosmetic: a broken provider must never fail the thing that asked for the cover.
        assertNull(ArtworkCache().get("boom") { error("provider exploded") })
    }

    @Test
    fun `the oldest entries are dropped once the cap is reached`() = runBlocking {
        val cache = ArtworkCache(maxEntries = 2)
        cache.get("one") { art("1.jpg") }
        cache.get("two") { art("2.jpg") }
        cache.get("three") { art("3.jpg") }

        val calls = AtomicInteger()
        cache.get("one") { calls.incrementAndGet(); art("1b.jpg") }

        assertEquals(1, calls.get())
    }
}
