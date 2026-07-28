package fm.rizx.player.data.repository

import fm.rizx.player.data.local.store.LyricsStore
import fm.rizx.player.data.provider.DefaultProviderRegistry
import fm.rizx.player.domain.model.LyricLine
import fm.rizx.player.domain.model.Lyrics
import fm.rizx.player.domain.model.LyricWord
import fm.rizx.player.domain.model.LyricsCandidate
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.provider.LyricsProvider
import fm.rizx.player.domain.provider.ProviderKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LyricsRepositoryTest {

    @get:Rule val temp = TemporaryFolder()

    private class FakeLyrics(
        override val id: String,
        private val result: Lyrics?,
        private val error: Exception? = null,
        private val results: List<LyricsCandidate> = emptyList(),
        /** Stands in for a provider that is up but not answering. */
        private val delayMs: Long = 0L,
    ) : LyricsProvider {
        override val kind = ProviderKind.LYRICS
        override val name = id
        var calls = 0
            private set

        override suspend fun getLyrics(track: Track): Lyrics? {
            calls++
            if (delayMs > 0) delay(delayMs)
            error?.let { throw it }
            return result
        }

        override suspend fun searchLyrics(query: String): List<LyricsCandidate> {
            if (delayMs > 0) delay(delayMs)
            return results
        }
    }

    private fun track(id: String = "1") = Track(title = "Yellow", source = ProviderRef("itunes", id))

    private fun store(): LyricsStore = LyricsStore(File(temp.newFolder(), "lyrics.json"))

    private fun synced(text: String) = Lyrics(lines = listOf(LyricLine(0L, text)), sourceName = "fake")

    private fun wordSynced(text: String) = Lyrics(
        lines = listOf(LyricLine(0L, text, words = listOf(LyricWord(0L, 500L, text)))),
        sourceName = "fake",
    )

    @Test
    fun `dispatches to the active lyrics provider`() = runBlocking {
        val registry = DefaultProviderRegistry().apply { register(FakeLyrics("a", synced("la la la"))) }

        val result = LyricsRepositoryImpl(registry).lyricsFor(track())

        assertEquals("la la la", result?.lyrics?.lines?.first()?.text)
    }

    @Test
    fun `returns null when no lyrics provider is registered`() = runBlocking {
        assertNull(LyricsRepositoryImpl(DefaultProviderRegistry()).lyricsFor(track()))
    }

    @Test
    fun `falls back to the next provider when the active one has nothing`() = runBlocking {
        val fallback = FakeLyrics("ovh", Lyrics(plain = "prose", sourceName = "ovh"))
        val registry = DefaultProviderRegistry().apply {
            register(FakeLyrics("lrclib", null)) // registered first => active
            register(fallback)
        }

        val result = LyricsRepositoryImpl(registry).lyricsFor(track())

        assertEquals("prose", result?.lyrics?.plain)
    }

    @Test
    fun `an empty result is treated as a miss, not an answer`() = runBlocking {
        val registry = DefaultProviderRegistry().apply {
            register(FakeLyrics("lrclib", Lyrics(sourceName = "lrclib"))) // no words at all
            register(FakeLyrics("ovh", Lyrics(plain = "prose", sourceName = "ovh")))
        }

        assertEquals("prose", LyricsRepositoryImpl(registry).lyricsFor(track())?.lyrics?.plain)
    }

    @Test
    fun `one broken provider does not stop the chain`() = runBlocking {
        val registry = DefaultProviderRegistry().apply {
            register(FakeLyrics("lrclib", null, error = IOException("down")))
            register(FakeLyrics("ovh", Lyrics(plain = "prose", sourceName = "ovh")))
        }

        assertEquals("prose", LyricsRepositoryImpl(registry).lyricsFor(track())?.lyrics?.plain)
    }

    @Test
    fun `an error surfaces only when every provider failed`() {
        // Otherwise a single broken source would show "you're offline" for a song that simply has no lyrics.
        val registry = DefaultProviderRegistry().apply {
            register(FakeLyrics("lrclib", null, error = IOException("down")))
            register(FakeLyrics("ovh", null, error = IOException("down")))
        }

        assertThrows(IOException::class.java) {
            runBlocking { LyricsRepositoryImpl(registry).lyricsFor(track()) }
        }
    }

    @Test
    fun `a hung provider does not hold up the one that has the song`() = runTest {
        // The whole point of racing the chain: 'stuck' is registered first, so the old sequential walk
        // would have burned its timeout before 'ovh' was even asked.
        val stuck = FakeLyrics("stuck", synced("never arrives"), delayMs = 60_000)
        val fallback = FakeLyrics("ovh", synced("here it is"))
        val registry = DefaultProviderRegistry().apply { register(stuck); register(fallback) }

        val result = LyricsRepositoryImpl(registry, providerTimeoutMs = 5_000).lyricsFor(track())

        assertEquals("here it is", result?.lyrics?.lines?.first()?.text)
    }

    @Test
    fun `a word-timed hit ends the race without waiting for the slow providers`() = runTest {
        val slow = FakeLyrics("slow", synced("late"), delayMs = 4_000)
        val karaoke = FakeLyrics("karaoke", wordSynced("bright"))
        val registry = DefaultProviderRegistry().apply { register(slow); register(karaoke) }

        val result = LyricsRepositoryImpl(registry, providerTimeoutMs = 10_000).lyricsFor(track())

        assertEquals("bright", result?.lyrics?.lines?.first()?.text)
        // Virtual time barely moved: the slow provider was cancelled, not awaited.
        assertTrue(
            "waited ${testScheduler.currentTime}ms for a result that had already arrived",
            testScheduler.currentTime < 1_000,
        )
    }

    @Test
    fun `every provider timing out reads as no lyrics, not as an error`() = runTest {
        val registry = DefaultProviderRegistry().apply {
            register(FakeLyrics("a", synced("x"), delayMs = 60_000))
            register(FakeLyrics("b", synced("y"), delayMs = 60_000))
        }

        assertNull(LyricsRepositoryImpl(registry, providerTimeoutMs = 1_000).lyricsFor(track()))
    }

    @Test
    fun `a timed-out chain is not cached, so the next play tries again`() = runTest {
        val stuck = FakeLyrics("stuck", synced("slow"), delayMs = 60_000)
        val fallback = FakeLyrics("ovh", Lyrics(plain = "prose", sourceName = "ovh"))
        val registry = DefaultProviderRegistry().apply { register(stuck); register(fallback) }
        val repo = LyricsRepositoryImpl(registry, store(), providerTimeoutMs = 1_000)

        repo.lyricsFor(track())
        repo.lyricsFor(track())

        assertEquals(2, fallback.calls)
    }

    @Test
    fun `search skips a provider that never answers`() = runTest {
        val candidate = LyricsCandidate(id = "1", title = "Yellow", artist = "Coldplay", lyrics = synced("hit"))
        val registry = DefaultProviderRegistry().apply {
            register(FakeLyrics("stuck", null, results = listOf(candidate), delayMs = 60_000))
            register(FakeLyrics("ovh", null, results = listOf(candidate)))
        }

        val results = LyricsRepositoryImpl(registry, providerTimeoutMs = 2_000).search("yellow")

        assertEquals(1, results.size)
    }

    @Test
    fun `a cached lyric is served without touching the provider again`() = runBlocking {
        val provider = FakeLyrics("lrclib", synced("cached"))
        val registry = DefaultProviderRegistry().apply { register(provider) }
        val repo = LyricsRepositoryImpl(registry, store())

        repo.lyricsFor(track())
        val second = repo.lyricsFor(track())

        assertEquals(1, provider.calls)
        assertEquals("cached", second?.lyrics?.lines?.first()?.text)
    }

    @Test
    fun `a result reached only by routing around a failure is not cached`() = runBlocking {
        // Seen on the first device run: LRCLIB timed out, lyrics.ovh answered with prose, and caching it
        // would have pinned the unsynced text to this song forever — even once LRCLIB came back.
        val flaky = FakeLyrics("lrclib", null, error = IOException("timeout"))
        val fallback = FakeLyrics("ovh", Lyrics(plain = "prose", sourceName = "ovh"))
        val registry = DefaultProviderRegistry().apply { register(flaky); register(fallback) }
        val repo = LyricsRepositoryImpl(registry, store())

        repo.lyricsFor(track())
        repo.lyricsFor(track())

        // Both lookups went back out, so a recovered provider gets another chance.
        assertEquals(2, flaky.calls)
        assertEquals(2, fallback.calls)
    }

    @Test
    fun `a pinned pick wins over what the provider would match`() = runBlocking {
        val provider = FakeLyrics("lrclib", synced("automatic"))
        val registry = DefaultProviderRegistry().apply { register(provider) }
        val repo = LyricsRepositoryImpl(registry, store())
        val chosen = LyricsCandidate(id = "9", title = "Yellow", artist = "Coldplay", lyrics = synced("chosen"))

        repo.pin(track(), chosen)
        val result = repo.lyricsFor(track())

        assertEquals("chosen", result?.lyrics?.lines?.first()?.text)
        assertTrue(result!!.pinned)
        assertEquals(0, provider.calls)
    }

    @Test
    fun `the offset is remembered per track`() = runBlocking {
        val registry = DefaultProviderRegistry().apply { register(FakeLyrics("lrclib", synced("x"))) }
        val repo = LyricsRepositoryImpl(registry, store())

        repo.lyricsFor(track())
        repo.setOffset(track(), 1_500L)

        assertEquals(1_500L, repo.lyricsFor(track())?.offsetMs)
        // A different song keeps its own correction.
        assertEquals(0L, repo.lyricsFor(track("2"))?.offsetMs)
    }

    @Test
    fun `clearing the override sends the next lookup back to the provider`() = runBlocking {
        val provider = FakeLyrics("lrclib", synced("fresh"))
        val registry = DefaultProviderRegistry().apply { register(provider) }
        val repo = LyricsRepositoryImpl(registry, store())

        repo.lyricsFor(track())
        repo.clearOverride(track())
        repo.lyricsFor(track())

        assertEquals(2, provider.calls)
    }

    @Test
    fun `search returns the first provider that has candidates`() = runBlocking {
        val candidate = LyricsCandidate(id = "1", title = "Yellow", artist = "Coldplay", lyrics = synced("hit"))
        val registry = DefaultProviderRegistry().apply {
            register(FakeLyrics("lrclib", null, results = emptyList()))
            register(FakeLyrics("other", null, results = listOf(candidate)))
        }

        val results = LyricsRepositoryImpl(registry).search("coldplay yellow")

        // Compared field by field rather than whole: search results are normalised on the way out (a
        // hand-picked candidate is rendered and cached without passing through the fetch path), so the
        // lyric that comes back has had its line ends filled in.
        assertEquals(1, results.size)
        assertEquals(candidate.id, results[0].id)
        assertEquals(candidate.title, results[0].title)
        assertEquals("hit", results[0].lyrics.lines.single().text)
    }

    @Test
    fun `a searched candidate comes back normalised, ready for the karaoke view`() {
        val candidate = LyricsCandidate(id = "1", title = "Yellow", artist = "Coldplay", lyrics = synced("hit"))
        val registry = DefaultProviderRegistry().apply { register(FakeLyrics("lrclib", null, results = listOf(candidate))) }

        val found = runBlocking { LyricsRepositoryImpl(registry).search("coldplay yellow") }.single()

        // The fake's line has no end; without one the sweep would never finish the last line.
        assertEquals(0L, candidate.lyrics.lines.single().endMs)
        assertTrue(found.lyrics.lines.single().endMs > 0L)
    }
}
