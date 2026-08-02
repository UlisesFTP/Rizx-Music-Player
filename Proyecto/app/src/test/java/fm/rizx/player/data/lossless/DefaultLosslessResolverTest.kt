package fm.rizx.player.data.lossless

import fm.rizx.player.FakeLosslessIndexSource
import fm.rizx.player.FakeSettingsRepository
import fm.rizx.player.dataSaverState
import fm.rizx.player.core.network.NetworkMonitor
import fm.rizx.player.domain.lossless.FlacInspector
import fm.rizx.player.domain.lossless.FlacStreamInfo
import fm.rizx.player.domain.lossless.LosslessIndexItem
import fm.rizx.player.domain.model.ArtistCredit
import fm.rizx.player.domain.model.AudioQualityMode
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The resolver is where "off costs nothing" is either true or a claim.
 *
 * Half of these assert that **no lookup happened at all** — a mode that isn't Lossless, a phone on
 * mobile data, no plugin installed. `lookups` counts calls into the index, so those are checkable
 * facts rather than a promise in a comment.
 */
class DefaultLosslessResolverTest {

    private val settings = FakeSettingsRepository()
    private val index = FakeLosslessIndexSource()
    private var now = 1_000L

    // isUnmetered is the field that decides now, so the fixtures have to set it: a Snapshot that only
    // says "not cellular" is a *hotspot*, which is exactly the case that used to slip through.
    private val onWifi =
        NetworkMonitor.Snapshot(isCellular = false, downstreamKbps = 50_000, isUnmetered = true)
    private val onCellular =
        NetworkMonitor.Snapshot(isCellular = true, downstreamKbps = 5_000, isUnmetered = false)
    private val onHotspot =
        NetworkMonitor.Snapshot(isCellular = false, downstreamKbps = 20_000, isUnmetered = false)

    private fun resolver(
        inspector: FlacInspector,
        net: NetworkMonitor.Snapshot = onWifi,
        cache: LosslessResolutionCache = LosslessResolutionCache(now = { now }),
    ) = DefaultLosslessResolver(
        settings = settings,
        source = index,
        matcher = DefaultLosslessMatcher(),
        inspector = inspector,
        cache = cache,
        network = mockk<NetworkMonitor> { every { snapshot() } returns net },
        dataSaver = dataSaverState(settings, unmetered = net.isUnmetered),
    )

    /** The happy path everything else is a deviation from. */
    private fun readyToMatch() {
        settings.audioQualityModeFlow.value = AudioQualityMode.LOSSLESS_PREFERRED
        index.available = true
        index.rows = listOf(row(URL_A))
    }

    // ---- the ways nothing happens ----

    @Test
    fun `standard mode never touches the index`() = runTest {
        index.available = true
        index.rows = listOf(row(URL_A))
        settings.audioQualityModeFlow.value = AudioQualityMode.STANDARD

        assertNull(resolver(inspector()).resolve(track()))
        assertEquals("off must cost nothing", 0, index.lookups)
    }

    @Test
    fun `best-available mode never touches the index either`() = runTest {
        index.available = true
        index.rows = listOf(row(URL_A))
        settings.audioQualityModeFlow.value = AudioQualityMode.BEST_AVAILABLE

        assertNull(resolver(inspector()).resolve(track()))
        assertEquals(0, index.lookups)
    }

    @Test
    fun `with no index plugin installed, nothing is asked and nothing fails`() = runTest {
        settings.audioQualityModeFlow.value = AudioQualityMode.LOSSLESS_PREFERRED
        index.available = false

        assertNull(resolver(inspector()).resolve(track()))
        assertEquals(0, index.lookups)
    }

    @Test
    fun `wifi-only keeps it off mobile data`() = runTest {
        readyToMatch()
        settings.losslessWifiOnlyFlow.value = true

        assertNull(resolver(inspector(URL_A to info()), net = onCellular).resolve(track()))
        assertEquals(0, index.lookups)
    }

    @Test
    fun `data saving stops it before the mode is even considered, on any connection`() = runTest {
        // Not a network rule any more: data saving makes the effective mode STANDARD, so the resolver
        // returns on its first line. Asserted on **Wi-Fi** on purpose — under the old rule this only
        // happened on cellular, which meant the switch did nothing at home.
        readyToMatch()
        settings.losslessWifiOnlyFlow.value = false
        settings.dataSaverFlow.value = true

        assertNull(resolver(inspector(URL_A to info()), net = onWifi).resolve(track()))
        assertEquals("not even the plugin should be asked", 0, index.lookups)
    }

    @Test
    fun `wifi-only refuses a hotspot, which reports Wi-Fi but bills a data plan`() = runTest {
        // The bug: keyed on isCellular, a hotspot answered false and a 24 MB FLAC went over somebody
        // else's allowance while the setting said "only on Wi-Fi".
        readyToMatch()
        settings.losslessWifiOnlyFlow.value = true

        assertNull(resolver(inspector(URL_A to info()), net = onHotspot).resolve(track()))
        assertEquals(0, index.lookups)
    }

    @Test
    fun `with wifi-only off and no data saver, cellular is allowed`() = runTest {
        readyToMatch()
        settings.losslessWifiOnlyFlow.value = false
        settings.dataSaverFlow.value = false

        assertNotNull(resolver(inspector(URL_A to info()), net = onCellular).resolve(track()))
    }

    // ---- resolution ----

    @Test
    fun `a matching row that verifies is returned with the parameters read from the file`() = runTest {
        readyToMatch()

        val result = resolver(inspector(URL_A to info(sampleRate = 48_000, bits = 16)))
            .resolve(track())!!

        assertEquals(URL_A, result.url)
        assertEquals(48_000, result.info.sampleRateHz)
        assertEquals("audio/flac", result.mimeType)
    }

    @Test
    fun `a row pointing at something that is not a FLAC falls through to the next one`() = runTest {
        readyToMatch()
        index.rows = listOf(row(URL_A), row(URL_B))

        // URL_A inspects to null — an HTML error page, an MP3, a dead link.
        val result = resolver(inspector(URL_B to info())).resolve(track())

        assertEquals(URL_B, result?.url)
    }

    @Test
    fun `when nothing verifies the answer is no, and it is remembered as invalid`() = runTest {
        readyToMatch()
        val cache = LosslessResolutionCache(now = { now })
        val inspector = inspector()

        assertNull(resolver(inspector, cache = cache).resolve(track()))
        // Second attempt is answered from the cache; the index is not asked again.
        assertNull(resolver(inspector, cache = cache).resolve(track()))
        assertEquals(1, index.lookups)
    }

    @Test
    fun `a file whose duration disagrees is refused however good the metadata looked`() = runTest {
        readyToMatch()

        assertNull(resolver(inspector(URL_A to info(durationMs = 120_000))).resolve(track()))
    }

    @Test
    fun `two equally good rows pointing at different files are refused`() = runTest {
        // The ambiguity rule: a source offering a coin flip has not identified anything, and the
        // ordinary stream is the correct outcome.
        readyToMatch()
        index.rows = listOf(row(URL_A), row(URL_B))

        val result = resolver(inspector(URL_A to info(), URL_B to info())).resolve(track())

        assertNull(result)
    }

    @Test
    fun `a tie is broken when only one of the two actually verifies`() = runTest {
        readyToMatch()
        index.rows = listOf(row(URL_A), row(URL_B))

        val result = resolver(inspector(URL_B to info())).resolve(track())

        assertEquals(URL_B, result?.url)
    }

    // ---- cache ----

    @Test
    fun `a verified answer is reused rather than re-verified`() = runTest {
        readyToMatch()
        val cache = LosslessResolutionCache(now = { now })
        val inspector = CountingInspector(mapOf(URL_A to info()))

        assertNotNull(resolver(inspector, cache = cache).resolve(track()))
        assertNotNull(resolver(inspector, cache = cache).resolve(track()))

        assertEquals("the second call must be free", 1, inspector.calls)
        assertEquals(1, index.lookups)
    }

    @Test
    fun `the answer is re-verified once its six hours are up`() = runTest {
        readyToMatch()
        val cache = LosslessResolutionCache(now = { now })
        val inspector = CountingInspector(mapOf(URL_A to info()))

        resolver(inspector, cache = cache).resolve(track())
        now += LosslessResolutionCache.HIT_TTL_MS + 1
        resolver(inspector, cache = cache).resolve(track())

        assertEquals(2, inspector.calls)
    }

    @Test
    fun `invalidating a track makes the next attempt go out again`() = runTest {
        readyToMatch()
        val cache = LosslessResolutionCache(now = { now })
        val inspector = CountingInspector(mapOf(URL_A to info()))
        val r = resolver(inspector, cache = cache)

        r.resolve(track())
        r.invalidate(track().source.identityKey)
        r.resolve(track())

        assertEquals(2, inspector.calls)
    }

    @Test
    fun `two callers landing on the same track only send one lookup`() = runTest {
        // The real pair: the player reaching a song while the prefetch is warming it.
        readyToMatch()
        val cache = LosslessResolutionCache(now = { now })
        cache.beginResolve(cache.keyFor(track().source.identityKey))

        val second = async { resolver(inspector(URL_A to info()), cache = cache).resolve(track()) }

        assertNull("the second caller defers rather than duplicating the work", second.await())
        assertEquals(0, index.lookups)
    }

    // ---- failure ----

    @Test
    fun `a broken index plugin cannot stop a song from playing`() = runTest {
        readyToMatch()
        index.failure = IllegalStateException("plugin exploded")

        assertNull(resolver(inspector(URL_A to info())).resolve(track()))
    }

    @Test
    fun `an index with nothing for this song is a plain miss`() = runTest {
        readyToMatch()
        index.rows = emptyList()

        assertNull(resolver(inspector()).resolve(track()))
    }

    // ---- fixtures ----

    private fun inspector(vararg entries: Pair<String, FlacStreamInfo>) =
        CountingInspector(entries.toMap())

    private class CountingInspector(private val byUrl: Map<String, FlacStreamInfo>) : FlacInspector {
        var calls = 0
            private set

        override suspend fun inspect(url: String): FlacStreamInfo? {
            calls++
            return byUrl[url]
        }
    }

    private fun info(sampleRate: Int = 44_100, bits: Int = 16, durationMs: Long = 287_000) = FlacStreamInfo(
        sampleRateHz = sampleRate,
        bitsPerSample = bits,
        channels = 2,
        totalSamples = durationMs * sampleRate / 1000,
        durationMs = durationMs,
        contentLength = 27_110_494L,
        effectiveBitrateKbps = 755,
    )

    private fun row(url: String) = LosslessIndexItem(song = "Pepas", artist = "Farruko", url = url)

    private fun track() = Track(
        title = "Pepas",
        artists = listOf(ArtistCredit(name = "Farruko")),
        durationMs = 287_000,
        source = ProviderRef("deezer", "track:1"),
    )

    private companion object {
        const val URL_A = "https://host.example/music/a.flac"
        const val URL_B = "https://host.example/music/b.flac"
    }
}
