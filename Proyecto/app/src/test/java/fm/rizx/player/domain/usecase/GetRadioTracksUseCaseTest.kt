package fm.rizx.player.domain.usecase

import fm.rizx.player.domain.model.Album
import fm.rizx.player.domain.model.Artist
import fm.rizx.player.domain.model.AttributedResult
import fm.rizx.player.domain.model.HomeFeed
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.SearchParams
import fm.rizx.player.domain.model.SearchResults
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.repository.DashboardRepository
import fm.rizx.player.domain.repository.MetadataRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class GetRadioTracksUseCaseTest {

    private fun track(id: String) = Track(title = id, source = ProviderRef("deezer", id))

    private fun meta(radio: List<Track>) = object : MetadataRepository {
        override suspend fun search(params: SearchParams) = SearchResults()
        override suspend fun albumDetail(source: ProviderRef): Album? = null
        override suspend fun artistDetail(source: ProviderRef): Artist? = null
        override suspend fun radioTracks(seed: Track): List<Track> = radio
        override suspend fun playlistTracks(source: ProviderRef): List<Track> = emptyList()
    }

    private fun dashboard(chart: List<Track>) = object : DashboardRepository {
        override suspend fun homeFeed() = HomeFeed(topTracks = listOf(AttributedResult("deezer", "Deezer", chart)))
    }

    @Test
    fun `returns radio tracks excluding the seed and already-queued sources`() = runBlocking {
        val useCase = GetRadioTracksUseCase(meta(listOf(track("s"), track("a"), track("b"))), dashboard(emptyList()))

        val result = useCase(track("s"), exclude = setOf(ProviderRef("deezer", "a")))

        assertEquals(listOf("b"), result.map { it.title }) // seed "s" + excluded "a" filtered out
    }

    @Test
    fun `falls back to the chart when the radio is empty`() = runBlocking {
        val useCase = GetRadioTracksUseCase(meta(emptyList()), dashboard(listOf(track("c1"), track("c2"))))

        val result = useCase(track("s"))

        assertEquals(setOf("c1", "c2"), result.map { it.title }.toSet())
    }
}
