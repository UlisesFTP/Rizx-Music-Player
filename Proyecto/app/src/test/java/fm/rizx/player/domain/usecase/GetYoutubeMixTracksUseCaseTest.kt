package fm.rizx.player.domain.usecase

import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.provider.RadioMixSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GetYoutubeMixTracksUseCaseTest {

    private fun youtubeTrack(id: String) = Track(title = id, source = ProviderRef("youtube", id))

    @Test
    fun `filters the seed and already-queued refs and dedups the rest`() = runTest {
        val seed = Track(title = "Seed", source = ProviderRef("deezer", "42"))
        val queued = ProviderRef("youtube", "q1")
        val source = object : RadioMixSource {
            override suspend fun mixTracks(seed: Track) = listOf(
                youtubeTrack("q1"), // already queued → dropped
                youtubeTrack("n1"),
                youtubeTrack("n1"), // duplicate → dropped
                youtubeTrack("n2"),
            )
        }

        val out = GetYoutubeMixTracksUseCase(source)(seed, exclude = setOf(queued, seed.source))

        assertEquals(listOf("n1", "n2"), out.map { it.source.id })
    }

    @Test
    fun `a throwing source degrades to empty — the caller's cue to fall back to the artist radio`() = runTest {
        val source = object : RadioMixSource {
            override suspend fun mixTracks(seed: Track): List<Track> = error("extractor down")
        }

        val out = GetYoutubeMixTracksUseCase(source)(Track(title = "s", source = ProviderRef("deezer", "1")))

        assertTrue(out.isEmpty())
    }
}
