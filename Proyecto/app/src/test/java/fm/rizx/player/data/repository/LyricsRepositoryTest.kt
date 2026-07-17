package fm.rizx.player.data.repository

import fm.rizx.player.data.provider.DefaultProviderRegistry
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.provider.LyricsProvider
import fm.rizx.player.domain.provider.ProviderKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricsRepositoryTest {

    private class FakeLyrics(private val result: String?) : LyricsProvider {
        override val id = "fake-lyrics"
        override val kind = ProviderKind.LYRICS
        override val name = "Fake Lyrics"
        override suspend fun getLyrics(track: Track): String? = result
    }

    private fun track() = Track(title = "Yellow", source = ProviderRef("itunes", "1"))

    @Test
    fun `dispatches to the active lyrics provider`() = runBlocking {
        val registry = DefaultProviderRegistry().apply { register(FakeLyrics("la la la")) }
        val repo = LyricsRepositoryImpl(registry)

        assertEquals("la la la", repo.lyricsFor(track()))
    }

    @Test
    fun `returns null when no lyrics provider is active`() = runBlocking {
        val repo = LyricsRepositoryImpl(DefaultProviderRegistry())

        assertNull(repo.lyricsFor(track()))
    }
}
