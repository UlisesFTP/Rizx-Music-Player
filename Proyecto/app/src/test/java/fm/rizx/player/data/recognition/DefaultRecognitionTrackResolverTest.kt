package fm.rizx.player.data.recognition

import fm.rizx.player.data.remote.deezer.DeezerAlbumShortDto
import fm.rizx.player.data.remote.deezer.DeezerApi
import fm.rizx.player.data.remote.deezer.DeezerArtistShortDto
import fm.rizx.player.data.remote.deezer.DeezerTrackDto
import fm.rizx.player.data.remote.itunes.ItunesApi
import fm.rizx.player.data.remote.itunes.ItunesResultDto
import fm.rizx.player.data.remote.itunes.ItunesSearchResponse
import fm.rizx.player.domain.model.AlbumRef
import fm.rizx.player.domain.model.ArtistCredit
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.SearchResults
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.recognition.RecognitionMatch
import fm.rizx.player.domain.repository.MetadataRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The rung order, and what happens when a rung is missing or broken.
 *
 * The point of the ladder is that a text search is the *last* thing tried, never the first — so most
 * of these assert on which endpoint was called as much as on what came back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultRecognitionTrackResolverTest {

    private val deezer = mockk<DeezerApi>(relaxed = true)
    private val itunes = mockk<ItunesApi>(relaxed = true)
    private val metadata = mockk<MetadataRepository>(relaxed = true)

    private val resolver = DefaultRecognitionTrackResolver(
        deezer = deezer,
        itunes = itunes,
        metadata = metadata,
        matcher = RecognitionMatcher(),
        io = UnconfinedTestDispatcher(),
    )

    private val heard = RecognitionMatch(
        provider = "shazam",
        providerTrackId = "105842472",
        title = "Get Lucky",
        artist = "Daft Punk, Pharrell Williams & Nile Rodgers",
        album = "Random Access Memories",
        isrc = "USQX91300108",
        appleTrackId = "617154366",
    )

    private fun deezerTrack(id: Long = 67238735, title: String = "Get Lucky", isrc: String? = "USQX91300108") =
        DeezerTrackDto(
            id = id,
            title = title,
            duration = 248,
            artist = DeezerArtistShortDto(id = 27, name = "Daft Punk"),
            album = DeezerAlbumShortDto(id = 6575789, title = "Random Access Memories"),
            isrc = isrc,
        )

    private fun catalogueTrack(title: String = "Get Lucky", id: String = "search") = Track(
        title = title,
        artists = listOf(ArtistCredit(name = "Daft Punk")),
        album = AlbumRef(title = "Random Access Memories", source = ProviderRef("deezer", "album:1")),
        source = ProviderRef("deezer", id),
    )

    @Test
    fun `the isrc is used first and nothing else is asked`() = runTest {
        coEvery { deezer.trackByIsrc("USQX91300108") } returns deezerTrack()

        val resolved = resolver.resolve(heard)

        assertEquals("67238735", resolved?.source?.id)
        assertEquals("deezer", resolved?.source?.provider)
        coVerify(exactly = 0) { itunes.lookup(any(), any(), any(), any()) }
        coVerify(exactly = 0) { metadata.search(any()) }
    }

    @Test
    fun `the resolved track carries the isrc forward and no stream url`() = runTest {
        coEvery { deezer.trackByIsrc(any()) } returns deezerTrack()

        val resolved = resolver.resolve(heard)!!

        assertTrue(resolved.tags.contains("isrc=USQX91300108"))
        assertTrue(resolved.streamCandidates.isEmpty())
        assertNull(resolved.localFile)
    }

    @Test
    fun `an unknown isrc falls through to apple's exact id`() = runTest {
        // Deezer answers an unknown ISRC with an error object, which parses to an empty row.
        coEvery { deezer.trackByIsrc(any()) } returns DeezerTrackDto()
        coEvery { itunes.lookup("617154366", any(), any(), any()) } returns ItunesSearchResponse(
            results = listOf(
                ItunesResultDto(
                    trackId = 617154366,
                    trackName = "Get Lucky",
                    artistName = "Daft Punk",
                    collectionName = "Random Access Memories",
                ),
            ),
        )

        val resolved = resolver.resolve(heard)

        assertEquals("617154366", resolved?.source?.id)
        coVerify(exactly = 0) { metadata.search(any()) }
    }

    @Test
    fun `an exact id pointing at the wrong recording is refused`() = runTest {
        coEvery { deezer.trackByIsrc(any()) } returns DeezerTrackDto()
        coEvery { itunes.lookup(any(), any(), any(), any()) } returns ItunesSearchResponse(
            results = listOf(ItunesResultDto(trackId = 1, trackName = "Instant Crush", artistName = "Daft Punk")),
        )
        coEvery { metadata.search(any()) } returns SearchResults(tracks = listOf(catalogueTrack()))

        // Falls through to the search rather than playing the wrong song from a trusted id.
        assertEquals("search", resolver.resolve(heard)?.source?.id)
    }

    @Test
    fun `with no identifiers at all it searches, and scores what comes back`() = runTest {
        val textOnly = heard.copy(isrc = null, appleTrackId = null)
        coEvery { metadata.search(any()) } returns SearchResults(
            tracks = listOf(catalogueTrack("Get Lucky (Live)", id = "live"), catalogueTrack(id = "studio")),
        )

        assertEquals("studio", resolver.resolve(textOnly)?.source?.id)
        coVerify(exactly = 0) { deezer.trackByIsrc(any()) }
    }

    @Test
    fun `the search asks for the title and the lead artist, not the whole billing`() = runTest {
        val textOnly = heard.copy(isrc = null, appleTrackId = null)
        coEvery { metadata.search(any()) } returns SearchResults(tracks = listOf(catalogueTrack()))

        resolver.resolve(textOnly)

        coVerify { metadata.search(match { it.query == "Get Lucky Daft Punk" }) }
    }

    @Test
    fun `an unconvincing search result is not played`() = runTest {
        val textOnly = heard.copy(isrc = null, appleTrackId = null)
        coEvery { metadata.search(any()) } returns SearchResults(
            tracks = listOf(catalogueTrack("Get Lucky (Karaoke Version)")),
        )

        assertNull(resolver.resolve(textOnly))
    }

    @Test
    fun `a rung that throws loses its turn instead of ending the resolution`() = runTest {
        coEvery { deezer.trackByIsrc(any()) } throws IOException("deezer is down")
        coEvery { itunes.lookup(any(), any(), any(), any()) } throws IOException("itunes is down")
        coEvery { metadata.search(any()) } returns SearchResults(tracks = listOf(catalogueTrack()))

        assertEquals("search", resolver.resolve(heard)?.source?.id)
    }

    @Test
    fun `everything failing is a null, not a crash`() = runTest {
        coEvery { deezer.trackByIsrc(any()) } throws IOException("down")
        coEvery { itunes.lookup(any(), any(), any(), any()) } throws IOException("down")
        coEvery { metadata.search(any()) } throws IOException("down")

        assertNull(resolver.resolve(heard))
    }
}
