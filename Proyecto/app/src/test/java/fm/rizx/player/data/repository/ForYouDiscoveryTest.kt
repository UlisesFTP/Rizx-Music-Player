package fm.rizx.player.data.repository

import fm.rizx.player.core.region.RegionResolver
import fm.rizx.player.data.remote.deezer.DeezerAlbumShortDto
import fm.rizx.player.data.remote.deezer.DeezerAlbumsResponse
import fm.rizx.player.data.remote.deezer.DeezerApi
import fm.rizx.player.data.remote.deezer.DeezerArtistsWrapper
import fm.rizx.player.data.remote.deezer.DeezerSearchResponse
import fm.rizx.player.domain.discovery.DiscoveryAnchor
import fm.rizx.player.domain.discovery.DiscoveryFeedback
import fm.rizx.player.domain.discovery.DiscoveryPlaylistSource
import fm.rizx.player.domain.discovery.HiddenItems
import fm.rizx.player.domain.discovery.TasteSeeds
import fm.rizx.player.domain.model.ArtistCredit
import fm.rizx.player.domain.model.ForYouSection
import fm.rizx.player.domain.model.PlayStat
import fm.rizx.player.domain.model.PlaylistRef
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.SoundGenre
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.provider.RadioMixSource
import fm.rizx.player.domain.repository.FavoritesRepository
import fm.rizx.player.domain.repository.RecentlyPlayedRepository
import fm.rizx.player.domain.repository.SettingsRepository
import fm.rizx.player.domain.usecase.ArtistNameMatching
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** The discovery rows of spec 026: playlists with an artist, genre shelves, the release radar, signals, cold start. */
class ForYouDiscoveryTest {

    private val today = LocalDate.parse("2026-11-02")
    private val nowIso = "2026-11-02T10:00:00Z"
    private val nowMs = Instant.parse(nowIso).toEpochMilli()

    private fun track(title: String, artist: String, deezerArtist: String? = null, tags: List<String> = emptyList()) = Track(
        title = title,
        artists = listOf(ArtistCredit(name = artist, source = deezerArtist?.let { ProviderRef("deezer", "artist:$it") })),
        source = ProviderRef("deezer", title.lowercase()),
        tags = tags,
    )

    private fun pl(provider: String, id: String) = PlaylistRef(id = id, name = "$provider-$id", source = ProviderRef(provider, "playlist:$id"))

    /** Answers three playlists for any artist and any genre, named after what it was asked. */
    private class FakeSource(override val id: String, private val fail: Boolean = false) : DiscoveryPlaylistSource {
        val askedArtists = mutableListOf<String>()
        val askedGenres = mutableListOf<SoundGenre>()

        override suspend fun forArtist(anchor: DiscoveryAnchor): List<PlaylistRef> {
            askedArtists += anchor.name
            if (fail) error("down")
            return List(3) { PlaylistRef(id = "$id-${anchor.name}-$it", name = "$id ${anchor.name} $it", source = ProviderRef(id, "playlist:${anchor.name}-$it")) }
        }

        override suspend fun forGenre(genre: SoundGenre): List<PlaylistRef> {
            askedGenres += genre
            if (fail) error("down")
            return List(3) { PlaylistRef(id = "$id-$genre-$it", name = "$id $genre $it", source = ProviderRef(id, "playlist:$genre-$it")) }
        }
    }

    private class FakeFeedback(hidden: HiddenItems) : DiscoveryFeedback {
        override val hidden: Flow<HiddenItems> = flowOf(hidden)
        override suspend fun hideArtist(name: String) = Unit
        override suspend fun hidePlaylist(identityKey: String) = Unit
        override suspend fun unhideArtist(name: String) = Unit
        override suspend fun unhidePlaylist(identityKey: String) = Unit
        override suspend fun clear() = Unit
    }

    private class FakeSeeds(names: List<String>) : TasteSeeds {
        override val seeds: Flow<List<String>> = flowOf(names)
        override val dismissed: Flow<Boolean> = flowOf(false)
        override suspend fun setSeeds(names: List<String>) = Unit
        override suspend fun dismiss() = Unit
    }

    private fun album(id: Long, title: String, date: String?, genre: Long? = null) =
        DeezerAlbumShortDto(id = id, title = title, releaseDate = date, genreId = genre)

    private fun deezer(albums: Map<String, List<DeezerAlbumShortDto>> = emptyMap()): DeezerApi = mockk {
        coEvery { artistRadio(any(), any()) } returns DeezerSearchResponse(emptyList())
        coEvery { artistRelated(any(), any()) } returns DeezerArtistsWrapper(emptyList())
        coEvery { searchArtists(any(), any()) } returns DeezerArtistsWrapper(emptyList())
        coEvery { artistAlbums(any(), any(), any()) } answers { DeezerAlbumsResponse(albums[firstArg()].orEmpty()) }
    }

    private fun repo(
        recent: List<Track> = emptyList(),
        stats: List<PlayStat>? = null,
        sources: List<DiscoveryPlaylistSource> = listOf(FakeSource("deezer"), FakeSource("applemusic")),
        api: DeezerApi = deezer(),
        hidden: HiddenItems = HiddenItems(),
        seeds: List<String> = emptyList(),
    ): ForYouRepositoryImpl {
        val favorites = mockk<FavoritesRepository> { every { favoriteTracks() } returns flowOf(emptyList()) }
        val recents = mockk<RecentlyPlayedRepository> {
            every { recent(any()) } returns flowOf(recent)
            if (stats != null) every { stats(any()) } returns flowOf(stats)
        }
        val settings = mockk<SettingsRepository> { every { recsRegionalConsent } returns flowOf(null) }
        return ForYouRepositoryImpl(
            favorites = favorites,
            recents = recents,
            mix = object : RadioMixSource {
                override suspend fun mixTracks(seed: Track) = emptyList<Track>()
            },
            deezer = api,
            settings = settings,
            region = RegionResolver(listOf { "mx" }),
            io = Dispatchers.Unconfined,
            shuffle = { it },
            discovery = sources,
            feedback = FakeFeedback(hidden),
            seeds = FakeSeeds(seeds),
            today = { today },
            nowMs = { nowMs },
        )
    }

    @Test
    fun `playlists with the top artist interleave the platforms`() = runTest {
        val rows = repo(recent = listOf(track("a", "Karol G"), track("b", "Karol G"), track("c", "Feid"))).sections().last()

        val first = rows.filterIsInstance<ForYouSection.PlaylistsWith>().first()
        assertEquals("Karol G", first.anchorName)
        assertEquals(listOf("deezer", "applemusic", "deezer"), first.playlists.take(3).map { it.source.provider })
    }

    @Test
    fun `the plan announces the playlist shelves, so the Home reserves their height`() = runTest {
        val plan = repo(recent = listOf(track("a", "Karol G"), track("c", "Feid"))).sections().toList().first()

        assertEquals(
            listOf("Karol G", "Feid"),
            plan.filterIsInstance<ForYouSection.PlaylistsWith>().map { it.anchorName },
        )
        assertTrue(plan.all { it.size == 0 })
    }

    @Test
    fun `an artist the listener keeps skipping no longer leads, however often they appear`() = runTest {
        val skipped = listOf(track("s1", "Skipped"), track("s2", "Skipped"), track("s3", "Skipped"))
        val loved = track("l1", "Loved")
        val stats = skipped.map { PlayStat(it, plays = 10, completions = 0, skips = 10, lastPlayedAtIso = nowIso) } +
            PlayStat(loved, plays = 5, completions = 5, skips = 0, lastPlayedAtIso = nowIso)

        val countOnly = repo(recent = skipped + loved).sections().last()
        val weighted = repo(recent = skipped + loved, stats = stats).sections().last()

        assertEquals("Skipped", countOnly.filterIsInstance<ForYouSection.PlaylistsWith>().first().anchorName)
        assertEquals("Loved", weighted.filterIsInstance<ForYouSection.PlaylistsWith>().first().anchorName)
    }

    @Test
    fun `release radar keeps the last 30 days, newest first, nothing from the future`() = runTest {
        val api = deezer(
            mapOf(
                "77" to listOf(
                    album(1, "Thirty days ago", "2026-10-03"),
                    album(2, "Thirty-one days ago", "2026-10-02"),
                    album(3, "Yesterday", "2026-11-01"),
                    album(4, "Next week", "2026-11-09"),
                    album(5, "Undated", null),
                    album(6, "Garbage date", "0000-00-00"),
                ),
            ),
        )

        val radar = repo(recent = listOf(track("a", "Feid", deezerArtist = "77")), api = api)
            .sections().last().filterIsInstance<ForYouSection.NewFromArtists>().single()

        assertEquals(listOf("Yesterday", "Thirty days ago"), radar.albums.map { it.title })
        assertEquals("the anchor fills the artist Deezer omits", "Feid", radar.albums.first().artists.single().name)
    }

    @Test
    fun `no recent release means no radar row`() = runTest {
        val api = deezer(mapOf("77" to listOf(album(1, "Old", "2019-01-01"))))

        val rows = repo(recent = listOf(track("a", "Feid", deezerArtist = "77")), api = api).sections().last()

        assertTrue(rows.none { it is ForYouSection.NewFromArtists })
    }

    @Test
    fun `genre shelves come from the taste's tags, then from Deezer's genre for the anchor`() = runTest {
        val tagged = repo(recent = listOf(track("a", "Karol G", tags = listOf("reggaeton")))).sections().last()
        assertEquals(
            listOf(SoundGenre.REGGAETON),
            tagged.filterIsInstance<ForYouSection.GenrePlaylists>().map { it.genre },
        )

        val api = deezer(mapOf("9" to listOf(album(1, "A", "2010-01-01", genre = 464), album(2, "B", "2012-01-01", genre = 464))))
        val untagged = repo(recent = listOf(track("a", "Metallica", deezerArtist = "9")), api = api).sections().last()
        assertEquals(
            listOf(SoundGenre.METAL),
            untagged.filterIsInstance<ForYouSection.GenrePlaylists>().map { it.genre },
        )
    }

    @Test
    fun `one platform down costs its cards, not the shelf`() = runTest {
        val rows = repo(
            recent = listOf(track("a", "Karol G")),
            sources = listOf(FakeSource("deezer", fail = true), FakeSource("applemusic")),
        ).sections().last()

        val shelf = rows.filterIsInstance<ForYouSection.PlaylistsWith>().single()
        assertTrue(shelf.playlists.all { it.source.provider == "applemusic" })
    }

    @Test
    fun `a hidden artist never anchors a row, and its tracks don't count as taste`() = runTest {
        val hidden = HiddenItems(artistKeys = setOf(ArtistNameMatching.key("Karol G")))
        val source = FakeSource("deezer")

        val rows = repo(
            recent = listOf(track("a", "Karol G"), track("b", "Karol G"), track("c", "Feid")),
            sources = listOf(source),
            hidden = hidden,
        ).sections().last()

        assertTrue("Karol G" !in source.askedArtists)
        assertEquals(listOf("Feid"), rows.filterIsInstance<ForYouSection.PlaylistsWith>().map { it.anchorName })
    }

    @Test
    fun `hidden playlists are left out of the shelves`() = runTest {
        val hidden = HiddenItems(playlistKeys = setOf(ProviderRef("deezer", "playlist:Feid-0").identityKey))

        val shelf = repo(recent = listOf(track("c", "Feid")), sources = listOf(FakeSource("deezer")), hidden = hidden)
            .sections().last().filterIsInstance<ForYouSection.PlaylistsWith>().singleOrNull()

        // Two cards left is below the row minimum, so the row is dropped rather than drawn as a stub.
        assertNull(shelf)
    }

    @Test
    fun `cold start with picked artists builds rows from the picks`() = runTest {
        val emissions = repo(seeds = listOf("Rosalía", "Bad Bunny", "Feid")).sections().toList()

        val plan = emissions.first()
        assertEquals("Rosalía", plan.filterIsInstance<ForYouSection.PlaylistsWith>().first().anchorName)
        assertTrue(emissions.last().filterIsInstance<ForYouSection.PlaylistsWith>().isNotEmpty())
    }

    @Test
    fun `cold start with nothing picked still announces nothing`() = runTest {
        assertEquals(listOf(emptyList<ForYouSection>()), repo().sections().toList())
    }

    @Test
    fun `with no discovery platforms the playlist shelves are skipped entirely`() = runTest {
        val rows = repo(recent = listOf(track("a", "Karol G", tags = listOf("reggaeton"))), sources = emptyList()).sections().toList()

        assertTrue(rows.flatten().none { it is ForYouSection.PlaylistsWith || it is ForYouSection.GenrePlaylists })
    }

    @Test
    fun `the plan and the rows carry the same titles in the same order`() = runTest {
        val emissions = repo(recent = listOf(track("a", "Karol G", tags = listOf("reggaeton")), track("c", "Feid"))).sections().toList()

        fun ForYouSection.key() = when (this) {
            is ForYouSection.PlaylistsWith -> "pw:$anchorName"
            is ForYouSection.GenrePlaylists -> "g:$genre"
            else -> null
        }
        val planned = emissions.first().mapNotNull { it.key() }
        val built = emissions.last().mapNotNull { it.key() }
        assertEquals(planned, built)
    }

    @Test
    fun `seeds are read through the store, picks first`() = runTest {
        assertEquals(listOf("A", "B"), FakeSeeds(listOf("A", "B")).seeds.first())
    }
}
