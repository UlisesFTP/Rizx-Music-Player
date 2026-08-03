package fm.rizx.player.data.repository

import fm.rizx.player.core.region.RegionResolver
import fm.rizx.player.data.remote.deezer.DeezerAlbumShortDto
import fm.rizx.player.data.remote.deezer.DeezerAlbumsResponse
import fm.rizx.player.data.remote.deezer.DeezerApi
import fm.rizx.player.data.remote.deezer.DeezerArtistShortDto
import fm.rizx.player.data.remote.deezer.DeezerArtistsWrapper
import fm.rizx.player.data.remote.deezer.DeezerSearchResponse
import fm.rizx.player.domain.model.ArtistCredit
import fm.rizx.player.domain.model.ForYouSection
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.provider.RadioMixSource
import fm.rizx.player.domain.repository.FavoritesRepository
import fm.rizx.player.domain.repository.RecentlyPlayedRepository
import fm.rizx.player.domain.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForYouRepositoryImplTest {

    private fun taste(title: String, artist: String, artistRef: ProviderRef? = null) = Track(
        title = title,
        artists = listOf(ArtistCredit(name = artist, source = artistRef)),
        source = ProviderRef("deezer", title.lowercase()),
    )

    private fun youtubeTrack(id: String) = Track(title = "yt-$id", source = ProviderRef("youtube", id))

    private fun mixOf(vararg tracks: Track) = object : RadioMixSource {
        override suspend fun mixTracks(seed: Track) = tracks.toList()
    }

    private fun repo(
        liked: List<Track> = emptyList(),
        recent: List<Track> = emptyList(),
        mix: RadioMixSource = mixOf(),
        deezer: DeezerApi = mockk {
            coEvery { artistRadio(any(), any()) } returns DeezerSearchResponse(emptyList())
            coEvery { artistRelated(any(), any()) } returns DeezerArtistsWrapper(emptyList())
            coEvery { searchArtists(any(), any()) } returns DeezerArtistsWrapper(emptyList())
            coEvery { artistAlbums(any(), any()) } returns DeezerAlbumsResponse(emptyList())
        },
        shuffle: (List<Track>) -> List<Track> = { it }, // deterministic seeds by default
    ): ForYouRepositoryImpl {
        val favorites = mockk<FavoritesRepository> { every { favoriteTracks() } returns flowOf(liked) }
        val recents = mockk<RecentlyPlayedRepository> { every { recent(any()) } returns flowOf(recent) }
        val settings = mockk<SettingsRepository> { every { recsRegionalConsent } returns flowOf(null) }
        return ForYouRepositoryImpl(
            favorites = favorites,
            recents = recents,
            mix = mix,
            deezer = deezer,
            settings = settings,
            region = RegionResolver(listOf { "mx" }),
            io = Dispatchers.Unconfined,
            shuffle = shuffle,
        )
    }

    @Test
    fun `the rows announce themselves before any network call, so the Home can reserve their height`() = runTest {
        val tracks = listOf(taste("Song A", "Artist X"), taste("Song C", "Artist Y"))
        val emissions = repo(recent = tracks, mix = mixOf(youtubeTrack("a"), youtubeTrack("b"), youtubeTrack("c")))
            .sections()
            .toList()

        assertEquals("plan, then the finished rows", 2, emissions.size)
        val plan = emissions.first()
        // Titled but empty — every title here comes from local taste, so announcing costs no round-trip.
        assertTrue("a planned row must carry no items", plan.all { it.size == 0 })
        assertEquals(listOf("Song A", "Song C"), plan.filterIsInstance<ForYouSection.Mix>().map { it.seedTitle })
        assertEquals("Artist X", plan.filterIsInstance<ForYouSection.BecauseYouLike>().single().artistName)
        // One "Similar to" row per anchor, titled after them — most-listened first.
        assertEquals(
            listOf("Artist X", "Artist Y"),
            plan.filterIsInstance<ForYouSection.SimilarTo>().map { it.anchorName },
        )
    }

    @Test
    fun `cold start announces nothing, so no space is held for a row that will never come`() = runTest {
        assertEquals(listOf(emptyList<ForYouSection>()), repo().sections().toList())
    }

    @Test
    fun `a shuffled feed titles the skeletons exactly like the rows that replace them`() = runTest {
        // Seeds are shuffled per load, so picking them twice — once to announce, once to build — would
        // title the skeleton after one song and the row that lands in its slot after another.
        val tracks = List(6) { taste("Song $it", "Artist $it") }
        val emissions = repo(
            recent = tracks,
            mix = mixOf(youtubeTrack("a"), youtubeTrack("b"), youtubeTrack("c")),
            shuffle = { it.shuffled() },
        ).sections().toList()

        assertEquals(
            emissions.first().filterIsInstance<ForYouSection.Mix>().map { it.seedTitle },
            emissions.last().filterIsInstance<ForYouSection.Mix>().map { it.seedTitle },
        )
    }

    @Test
    fun `mix seeds are one per artist, so two rows can't be near-copies of each other`() = runTest {
        val tracks = listOf(
            taste("Song A", "Artist X"),
            taste("Song B", "Artist X"), // same artist → its mix would repeat the first row's
            taste("Song C", "Artist Y"),
        )
        val sections = repo(recent = tracks, mix = mixOf(youtubeTrack("a"), youtubeTrack("b"), youtubeTrack("c")))
            .sections().last()

        val mixes = sections.filterIsInstance<ForYouSection.Mix>()
        assertEquals(2, mixes.size)
        assertEquals(listOf("Song A", "Song C"), mixes.map { it.seedTitle })
        assertEquals(3, mixes.first().items.size)
    }

    @Test
    fun `two artists sharing a song title still yield one row, keeping the list keys unique`() = runTest {
        val tracks = listOf(taste("Hello", "Artist X"), taste("Hello", "Artist Y"))
        val sections = repo(recent = tracks, mix = mixOf(youtubeTrack("a"), youtubeTrack("b"), youtubeTrack("c")))
            .sections().last()

        assertEquals(1, sections.filterIsInstance<ForYouSection.Mix>().size)
    }

    @Test
    fun `because-you-like uses the top artist's Deezer radio via its artist ref`() = runTest {
        val artistRef = ProviderRef("deezer", "artist:77")
        val tracks = List(3) { taste("Song $it", "Artist X", artistRef) }
        val deezer = mockk<DeezerApi> {
            coEvery { artistRadio("77", any()) } returns DeezerSearchResponse(
                List(4) { fm.rizx.player.data.remote.deezer.DeezerTrackDto(id = it.toLong() + 1, title = "R$it") },
            )
            coEvery { artistRelated(any(), any()) } returns DeezerArtistsWrapper(emptyList())
            coEvery { searchArtists(any(), any()) } returns DeezerArtistsWrapper(emptyList())
            coEvery { artistAlbums(any(), any()) } returns DeezerAlbumsResponse(emptyList())
        }

        val sections = repo(liked = tracks, deezer = deezer).sections().last()

        val row = sections.filterIsInstance<ForYouSection.BecauseYouLike>().single()
        assertEquals("Artist X", row.artistName)
        assertEquals(4, row.items.size)
    }

    @Test
    fun `a broken Deezer never sinks the mix rows`() = runTest {
        val deezer = mockk<DeezerApi> {
            coEvery { artistRadio(any(), any()) } throws IllegalStateException("down")
            coEvery { artistRelated(any(), any()) } throws IllegalStateException("down")
            coEvery { searchArtists(any(), any()) } throws IllegalStateException("down")
            coEvery { artistAlbums(any(), any()) } throws IllegalStateException("down")
        }
        val sections = repo(
            recent = listOf(taste("Song A", "Artist X")),
            mix = mixOf(youtubeTrack("a"), youtubeTrack("b"), youtubeTrack("c")),
            deezer = deezer,
        ).sections().last()

        assertEquals(1, sections.filterIsInstance<ForYouSection.Mix>().size)
        assertTrue(sections.filterIsInstance<ForYouSection.BecauseYouLike>().isEmpty())
        assertTrue(sections.filterIsInstance<ForYouSection.SimilarTo>().isEmpty())
    }

    @Test
    fun `similar-to carries the anchor's related artists and their records, artist filled in`() = runTest {
        val sections = repo(liked = relatedTaste(), deezer = relatedDeezer()).sections().last()

        val row = sections.filterIsInstance<ForYouSection.SimilarTo>().single()
        assertEquals("Artist X", row.anchorName)
        assertEquals(3, row.artists.size)
        // Two related artists are seeded for albums, three each, deduped by identity.
        assertEquals(6, row.albums.size)
        // Deezer's /artist/{id}/albums omits the artist per row — it must be filled in from the seed.
        assertTrue(row.albums.all { it.artists.singleOrNull()?.name?.startsWith("Related") == true })
        // Round-robin, so the album half alternates artists instead of listing one discography first.
        assertEquals(
            listOf("Related 0", "Related 1", "Related 0", "Related 1", "Related 0", "Related 1"),
            row.albums.map { it.artists.single().name },
        )
    }

    @Test
    fun `an anchor Deezer cannot resolve drops its row, not the other anchor's`() = runTest {
        val refX = ProviderRef("deezer", "artist:1")
        // Artist X twice (the top anchor, with a ref), Artist Y once (no ref → needs a name lookup,
        // which this Deezer fails) — so only X's neighborhood can be built.
        val tracks = listOf(taste("S1", "Artist X", refX), taste("S2", "Artist X", refX), taste("S3", "Artist Y"))

        val sections = repo(liked = tracks, deezer = relatedDeezer()).sections().last()

        assertEquals(listOf("Artist X"), sections.filterIsInstance<ForYouSection.SimilarTo>().map { it.anchorName })
    }

    /** Taste that resolves to one Deezer artist id, so `artistRelated("1")` is the only seed call. */
    private fun relatedTaste(): List<Track> {
        val refX = ProviderRef("deezer", "artist:1")
        return listOf(taste("S1", "Artist X", refX), taste("S2", "Artist X", refX))
    }

    private fun relatedDeezer(): DeezerApi = mockk {
        coEvery { artistRadio(any(), any()) } returns DeezerSearchResponse(emptyList())
        coEvery { artistRelated("1", any()) } returns DeezerArtistsWrapper(
            List(3) { DeezerArtistShortDto(id = it.toLong() + 10, name = "Related $it") },
        )
        coEvery { searchArtists(any(), any()) } returns DeezerArtistsWrapper(emptyList())
        // Distinct ids per artist so the dedup doesn't collapse the two seeds into one row of three.
        coEvery { artistAlbums(any(), any()) } answers {
            val artistId = firstArg<String>().toLong()
            DeezerAlbumsResponse(
                List(3) { DeezerAlbumShortDto(id = artistId * 100 + it, title = "Album $artistId-$it") },
            )
        }
    }
}
