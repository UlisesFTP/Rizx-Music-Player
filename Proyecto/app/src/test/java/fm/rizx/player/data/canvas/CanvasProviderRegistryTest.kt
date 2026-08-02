package fm.rizx.player.data.canvas

import fm.rizx.player.domain.model.CanvasAspect
import fm.rizx.player.domain.model.CanvasCandidate
import fm.rizx.player.domain.model.CanvasQuality
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.provider.CanvasProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** The chain of canvas sources: priority order, and refusing to let a broken one matter. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CanvasProviderRegistryTest {

    private class Fake(
        override val id: String,
        override val priority: Int,
        private val result: CanvasCandidate? = null,
        private val error: Exception? = null,
        private val delayMs: Long = 0L,
    ) : CanvasProvider {
        override val displayName = id
        var calls = 0
            private set

        override suspend fun resolve(
            track: Track,
            preferredAspect: CanvasAspect,
            quality: CanvasQuality,
        ): List<CanvasCandidate> {
            calls++
            if (delayMs > 0) delay(delayMs)
            error?.let { throw it }
            return listOfNotNull(result)
        }
    }

    private fun candidate(providerId: String) = CanvasCandidate(providerId, "https://x/$providerId.mp4")

    private fun track() = Track(title = "Levitating", source = ProviderRef("deezer", "1"))

    private suspend fun CanvasProviderRegistry.resolve(track: Track = track()) =
        resolve(track, CanvasAspect.LANDSCAPE, CanvasQuality.DATA_SAVER)

    @Test
    fun `the lowest priority number is asked first`() = runTest {
        val curated = Fake("curated", priority = 0, result = candidate("curated"))
        val youtube = Fake("youtube", priority = 100, result = candidate("youtube"))
        // Registered out of order on purpose — the list, not the registration, must decide.
        val registry = CanvasProviderRegistry(listOf(youtube, curated))

        assertEquals("curated", registry.resolve().firstOrNull()?.providerId)
        assertEquals("the better source answered; nothing else should have been asked", 0, youtube.calls)
    }

    @Test
    fun `a provider with nothing hands over to the next`() = runTest {
        val empty = Fake("curated", priority = 0, result = null)
        val youtube = Fake("youtube", priority = 100, result = candidate("youtube"))
        val registry = CanvasProviderRegistry(listOf(empty, youtube))

        assertEquals("youtube", registry.resolve().firstOrNull()?.providerId)
        assertEquals(1, empty.calls)
    }

    @Test
    fun `a provider that throws is skipped, and reported`() = runTest {
        var reported: String? = null
        val broken = Fake("broken", priority = 0, error = IOException("youtube changed again"))
        val registry = CanvasProviderRegistry(listOf(broken, Fake("youtube", 100, candidate("youtube"))))

        val result = registry.resolve(track(), CanvasAspect.LANDSCAPE, CanvasQuality.DATA_SAVER) { p, _ ->
            reported = p.id
        }

        assertEquals("youtube", result.firstOrNull()?.providerId)
        assertEquals("broken", reported)
    }

    @Test
    fun `a provider that hangs is given up on rather than waited out`() = runTest {
        val stuck = Fake("stuck", priority = 0, result = candidate("stuck"), delayMs = 60_000)
        val youtube = Fake("youtube", priority = 100, result = candidate("youtube"))
        val registry = CanvasProviderRegistry(listOf(stuck, youtube), providerTimeoutMs = 2_000)

        assertEquals("youtube", registry.resolve().firstOrNull()?.providerId)
        assertTrue(
            "should have moved on after the timeout, not the full hang: ${testScheduler.currentTime}ms",
            testScheduler.currentTime < 3_000,
        )
    }

    @Test
    fun `every provider failing is a missing canvas, never a thrown error`() = runTest {
        val registry = CanvasProviderRegistry(
            listOf(
                Fake("a", 0, error = IOException("down")),
                Fake("b", 100, error = IllegalStateException("also down")),
            ),
        )

        // Decoration must never take the app — or the music — down with it.
        assertTrue(registry.resolve().isEmpty())
    }

    @Test
    fun `leaving the screen cancels the chain instead of falling through it`() = runTest {
        val slow = Fake("slow", priority = 0, result = candidate("slow"), delayMs = 30_000)
        val next = Fake("next", priority = 100, result = candidate("next"))
        val registry = CanvasProviderRegistry(listOf(slow, next), providerTimeoutMs = 60_000)

        val job = async { registry.resolve() }
        testScheduler.advanceTimeBy(1_000)
        job.cancel()

        // A provider timeout is a skip; the caller giving up is not — otherwise navigating away would
        // still run the rest of the chain, which is exactly the background work this feature must avoid.
        assertTrue(runCatching { job.await() }.exceptionOrNull() is CancellationException)
        assertEquals(0, next.calls)
    }

    @Test
    fun `no providers at all is simply no canvas`() = runTest {
        assertTrue(CanvasProviderRegistry(emptyList()).resolve().isEmpty())
    }
}
