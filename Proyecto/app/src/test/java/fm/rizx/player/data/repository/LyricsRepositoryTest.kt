package fm.rizx.player.data.repository

import fm.rizx.player.data.local.store.LyricsStore
import fm.rizx.player.data.provider.DefaultProviderRegistry
import fm.rizx.player.domain.model.LyricLine
import fm.rizx.player.domain.model.Lyrics
import fm.rizx.player.domain.model.LyricsCandidate
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.provider.LyricsProvider
import fm.rizx.player.domain.provider.ProviderKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class LyricsRepositoryTest {

    @get:Rule val temp = TemporaryFolder()

    private class FakeLyrics(
        override val id: String,
        private val result: Lyrics?,
        private val error: Exception? = null,
        private val results: List<LyricsCandidate> = emptyList(),
    ) : LyricsProvider {
        override val kind = ProviderKind.LYRICS
        override val name = id
        var calls = 0
            private set

        override suspend fun getLyrics(track: Track): Lyrics? {
            calls++
            error?.let { throw it }
            return result
        }

        override suspend fun searchLyrics(query: String): List<LyricsCandidate> = results
    }

    private fun track(id: String = "1") = Track(title = "Yellow", source = ProviderRef("itunes", id))

    private fun store(): LyricsStore = LyricsStore(File(temp.newFolder(), "lyrics.json"))

    private fun synced(text: String) = Lyrics(lines = listOf(LyricLine(0L, text)), sourceName = "fake")

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

        assertEquals(listOf(candidate), LyricsRepositoryImpl(registry).search("coldplay yellow"))
    }
}
