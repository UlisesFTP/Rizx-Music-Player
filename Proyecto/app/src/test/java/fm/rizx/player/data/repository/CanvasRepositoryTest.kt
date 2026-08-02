package fm.rizx.player.data.repository

import fm.rizx.player.FakeSettingsRepository
import fm.rizx.player.data.canvas.CanvasProviderRegistry
import fm.rizx.player.data.canvas.CanvasResolutionCache
import fm.rizx.player.domain.canvas.CanvasGate
import fm.rizx.player.domain.model.CanvasAspect
import fm.rizx.player.domain.model.CanvasBlockReason
import fm.rizx.player.domain.model.CanvasCandidate
import fm.rizx.player.domain.model.CanvasNetworkPolicy
import fm.rizx.player.domain.model.CanvasPreferences
import fm.rizx.player.domain.model.CanvasQuality
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.provider.CanvasProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The road to a canvas: policy first, then the cache, then the providers.
 *
 * The order is the feature — a canvas that isn't allowed has to cost nothing, rather than being fetched
 * and then not shown.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CanvasRepositoryTest {

    private class Fake(
        override val id: String = "youtube",
        // Labelled with this provider's own id — a candidate that lies about where it came from would
        // make the cross-provider fallback untestable.
        private val results: List<CanvasCandidate> = listOf(CanvasCandidate(id, "https://x/$id.mp4", score = 95)),
        private val error: Exception? = null,
        private val delayMs: Long = 0L,
        override val priority: Int = 0,
    ) : CanvasProvider {
        override val displayName = id
        var calls = 0
            private set
        var lastQuality: CanvasQuality? = null
            private set

        override suspend fun resolve(
            track: Track,
            preferredAspect: CanvasAspect,
            quality: CanvasQuality,
        ): List<CanvasCandidate> {
            calls++
            lastQuality = quality
            if (delayMs > 0) delay(delayMs)
            error?.let { throw it }
            return results
        }
    }

    private var now = 0L

    private fun repo(
        vararg providers: CanvasProvider,
        conditions: CanvasGate.Conditions = CanvasGate.Conditions(unmetered = true),
        cache: CanvasResolutionCache = CanvasResolutionCache(now = { now }),
    ) = CanvasRepositoryImpl(
        registry = CanvasProviderRegistry(providers.toList()),
        cache = cache,
        policy = { dataSaver -> conditions.copy(dataSaver = dataSaver) },
        settings = FakeSettingsRepository(),
        elapsedMs = { now },
    )

    private fun track(id: String = "1") =
        Track(title = "Levitating", durationMs = 203_000, source = ProviderRef("deezer", id))

    private val on = CanvasPreferences(enabled = true)

    @Test
    fun `a canvas that is switched off is never looked up`() = runTest {
        val provider = Fake()

        val result = repo(provider).resolve(track(), CanvasPreferences(enabled = false))

        assertNull(result.candidate)
        assertEquals(CanvasBlockReason.DISABLED, result.diagnostics.blockedBy)
        assertEquals("off must mean zero network work, not a discarded answer", 0, provider.calls)
    }

    @Test
    fun `mobile data blocks the lookup itself, not just the display`() = runTest {
        val provider = Fake()

        val result = repo(provider, conditions = CanvasGate.Conditions(unmetered = false)).resolve(track(), on)

        assertEquals(CanvasBlockReason.METERED, result.diagnostics.blockedBy)
        assertEquals(0, provider.calls)
    }

    @Test
    fun `battery saver blocks the lookup too`() = runTest {
        val provider = Fake()
        val saving = CanvasGate.Conditions(unmetered = true, powerSaveMode = true)

        assertEquals(CanvasBlockReason.BATTERY_SAVER, repo(provider, conditions = saving).resolve(track(), on).diagnostics.blockedBy)
        assertEquals(0, provider.calls)
    }

    @Test
    fun `an allowed lookup returns the candidate and describes itself`() = runTest {
        val result = repo(Fake()).resolve(track(), on)

        assertEquals("https://x/youtube.mp4", result.candidate?.mediaUrl)
        assertEquals("youtube", result.diagnostics.providerId)
        assertEquals(95, result.diagnostics.score)
        assertTrue(!result.diagnostics.cacheHit)
        assertNull(result.diagnostics.blockedBy)
    }

    @Test
    fun `the second look at the same song costs nothing`() = runTest {
        val provider = Fake()
        val repo = repo(provider)

        repo.resolve(track(), on)
        val second = repo.resolve(track(), on)

        assertEquals(1, provider.calls)
        assertTrue(second.diagnostics.cacheHit)
        assertNotNull(second.candidate)
    }

    @Test
    fun `a remembered miss is reported as a miss, not as a blank`() = runTest {
        val provider = Fake(results = emptyList())
        val repo = repo(provider)

        repo.resolve(track(), on)
        val second = repo.resolve(track(), on)

        assertEquals(1, provider.calls)
        assertEquals(CanvasBlockReason.NO_CANDIDATE, second.diagnostics.blockedBy)
    }

    @Test
    fun `a failure is remembered far more briefly than a genuine miss`() = runTest {
        // The distinction the old cache couldn't make: it kept "no video" forever, so a song that
        // missed because the network hiccuped never got another chance in that process.
        val provider = Fake(error = IOException("down"))
        val repo = repo(provider)

        val first = repo.resolve(track(), on)
        assertEquals(CanvasBlockReason.PROVIDER_ERROR, first.diagnostics.blockedBy)
        assertNotNull("the user is told something went wrong, in safe words", first.diagnostics.error)

        now += CanvasResolutionCache.ERROR_TTL_MS + 1
        repo.resolve(track(), on)

        assertEquals("an error must be retried long before a real miss would be", 2, provider.calls)
    }

    @Test
    fun `an unmetered connection honours the chosen quality, a metered one clamps it`() = runTest {
        val provider = Fake()
        repo(provider).resolve(track(), on.copy(quality = CanvasQuality.HIGH))
        assertEquals(CanvasQuality.HIGH, provider.lastQuality)

        val metered = Fake()
        val anyNetwork = on.copy(network = CanvasNetworkPolicy.ANY, quality = CanvasQuality.HIGH)
        repo(metered, conditions = CanvasGate.Conditions(unmetered = false)).resolve(track(), anyNetwork)
        assertEquals("the user's money outranks the user's preference", CanvasQuality.DATA_SAVER, metered.lastQuality)
    }

    @Test
    fun `two songs keep their own answers`() = runTest {
        val provider = Fake()
        val repo = repo(provider)

        repo.resolve(track("1"), on)
        repo.resolve(track("2"), on)

        assertEquals("identity is the ProviderRef, so a second song is a second lookup", 2, provider.calls)
    }

    @Test
    fun `the last lookup is remembered for the diagnostics panel`() = runTest {
        val repo = repo(Fake())

        repo.resolve(track(), on)

        // Settings can ask long after Now Playing — and its ViewModel — are gone.
        assertEquals("youtube", repo.lastDiagnostics.value.providerId)
        assertEquals(95, repo.lastDiagnostics.value.score)
    }

    @Test
    fun `the diagnostics never carry a URL`() = runTest {
        // A resolved googlevideo link is a signed token, and this panel is what people screenshot.
        val repo = repo(Fake())

        repo.resolve(track(), on)

        val rendered = repo.lastDiagnostics.value.toString()
        assertTrue("diagnostics leaked a URL: $rendered", !rendered.contains("http"))
    }

    @Test
    fun `what the player measured is folded into the diagnostics`() = runTest {
        // Resolution can't report these: an HLS master advertises nine variants and only Media3 knows
        // which one it decoded.
        val repo = repo(Fake())
        repo.resolve(track(), on)

        repo.report(repo.lastDiagnostics.value.copy(width = 1080, height = 1080, frameRate = 30f))

        assertEquals(1080, repo.lastDiagnostics.value.width)
        assertEquals(30f, repo.lastDiagnostics.value.frameRate!!, 0.01f)
        assertEquals("and it keeps what the lookup found", "youtube", repo.lastDiagnostics.value.providerId)
    }

    // ---- the candidate list, and falling across providers ----

    @Test
    fun `every candidate the winning provider offered comes back, best first`() = runTest {
        val apple = Fake(
            id = "apple",
            results = listOf(
                CanvasCandidate("apple", "https://a/tall.m3u8", score = 100),
                CanvasCandidate("apple", "https://a/square.m3u8", score = 100),
            ),
        )

        val result = repo(apple).resolve(track(), on)

        assertEquals(listOf("https://a/tall.m3u8", "https://a/square.m3u8"), result.candidates.map { it.mediaUrl })
        assertEquals("and the first is the one to play", "https://a/tall.m3u8", result.candidate?.mediaUrl)
    }

    @Test
    fun `the chain stops at the first provider that answers`() = runTest {
        val apple = Fake(id = "apple", priority = 10)
        val youtube = Fake(id = "youtube", priority = 100)

        repo(apple, youtube).resolve(track(), on)

        assertEquals("a NewPipe extraction nobody will reach is a wasted round trip", 0, youtube.calls)
    }

    @Test
    fun `excluding the provider whose candidates failed reaches the next one`() = runTest {
        val apple = Fake(id = "apple", priority = 10)
        val youtube = Fake(id = "youtube", priority = 100)
        val repo = repo(apple, youtube)

        val second = repo.resolve(track(), on, exclude = setOf("apple"))

        assertEquals("youtube", second.candidate?.providerId)
        assertEquals(1, youtube.calls)
    }

    @Test
    fun `the fallback answer does not overwrite the first one in the cache`() = runTest {
        val apple = Fake(id = "apple", priority = 10)
        val youtube = Fake(id = "youtube", priority = 100)
        val repo = repo(apple, youtube)

        repo.resolve(track(), on)                                // apple, cached
        repo.resolve(track(), on, exclude = setOf("apple"))      // youtube, cached separately
        val again = repo.resolve(track(), on)

        assertEquals("apple", again.candidate?.providerId)
        assertTrue("and it came from the cache", again.diagnostics.cacheHit)
        assertEquals("apple was asked exactly once", 1, apple.calls)
    }

    @Test
    fun `a source switched off in Settings is never asked`() = runTest {
        val apple = Fake(id = "apple", priority = 10)
        val youtube = Fake(id = "youtube", priority = 100)

        val result = repo(apple, youtube).resolve(track(), on.copy(appleEnabled = false))

        assertEquals("youtube", result.candidate?.providerId)
        assertEquals(0, apple.calls)
    }

    @Test
    fun `switching both sources off costs no round trip at all`() = runTest {
        val apple = Fake(id = "apple", priority = 10)
        val youtube = Fake(id = "youtube", priority = 100)

        val result = repo(apple, youtube)
            .resolve(track(), on.copy(appleEnabled = false, youtubeEnabled = false))

        assertEquals(CanvasBlockReason.DISABLED, result.diagnostics.blockedBy)
        assertEquals(0, apple.calls)
        assertEquals(0, youtube.calls)
    }
}
