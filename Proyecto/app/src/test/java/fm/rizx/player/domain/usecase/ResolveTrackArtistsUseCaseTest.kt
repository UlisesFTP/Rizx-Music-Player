package fm.rizx.player.domain.usecase

import fm.rizx.player.data.provider.DefaultProviderRegistry
import fm.rizx.player.domain.model.Album
import fm.rizx.player.domain.model.Artist
import fm.rizx.player.domain.model.ArtistCredit
import fm.rizx.player.domain.model.ArtistRef
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.SearchCapability
import fm.rizx.player.domain.model.SearchCategory
import fm.rizx.player.domain.model.SearchParams
import fm.rizx.player.domain.model.SearchResults
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.provider.MetadataProvider
import fm.rizx.player.domain.provider.ProviderKind
import fm.rizx.player.domain.repository.MetadataRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

class ResolveTrackArtistsUseCaseTest {

    /** Only its id matters here — the registry answers "who is the active metadata provider". */
    private class FakeMetadataProvider(override val id: String) : MetadataProvider {
        override val kind = ProviderKind.METADATA
        override val name = id
        override val searchCapabilities: Set<SearchCapability> = setOf(SearchCapability.ARTISTS)
        override suspend fun search(params: SearchParams) = SearchResults()
    }

    private class FakeMetadata(
        /** The answer for any query not named in [byQuery]. */
        private val artists: List<ArtistRef> = emptyList(),
        private val error: Exception? = null,
        private val byQuery: Map<String, List<ArtistRef>> = emptyMap(),
    ) : MetadataRepository {
        var queries = 0
            private set
        var lastTypes: List<SearchCategory>? = null
            private set

        override suspend fun search(params: SearchParams): SearchResults {
            queries++
            lastTypes = params.types
            error?.let { throw it }
            return SearchResults(artists = byQuery[params.query] ?: artists)
        }

        override suspend fun albumDetail(source: ProviderRef): Album? = null
        override suspend fun artistDetail(source: ProviderRef): Artist? = null
        override suspend fun radioTracks(seed: Track): List<Track> = emptyList()
        override suspend fun playlistTracks(source: ProviderRef): List<Track> = emptyList()
    }

    private fun registry(activeId: String = "deezer") = DefaultProviderRegistry().apply {
        register(FakeMetadataProvider(activeId))
        setActive(ProviderKind.METADATA, activeId)
    }

    private fun track(artist: String?, artistSource: ProviderRef? = null) = Track(
        title = "Song",
        artists = listOfNotNull(artist?.let { ArtistCredit(name = it, source = artistSource) }),
        source = ProviderRef("youtube", "abcdefghijk"),
    )

    private fun deezerArtist(name: String, id: String = "1", followers: Long? = null) =
        ArtistRef(name = name, source = ProviderRef("deezer", "artist:$id"), followers = followers)

    private fun sourcesOf(links: List<LinkedArtist>) = links.map { it.source }

    // ---- Picking the right profile among same-name rows -------------------------------------

    @Test
    fun `the most complete profile wins, not the highest-ranked one`() = runBlocking {
        // Live: Deezer returns a 27-follower "The Weeknd" with no albums *above* the 14.6M one.
        // Taking rank 1 opened a page with six featured songs on it.
        val metadata = FakeMetadata(
            artists = listOf(
                deezerArtist("The Weeknd", id = "315582831", followers = 27),
                deezerArtist("The Weeknd", id = "4050205", followers = 14_587_762),
            ),
        )

        val links = ResolveTrackArtistsUseCase(metadata, registry())(track("The Weeknd"))

        assertEquals(listOf(ProviderRef("deezer", "artist:4050205")), sourcesOf(links))
    }

    @Test
    fun `a credited id is replaced when it loses to a same-name row beside it`() = runBlocking {
        // Live: a release filed under a duplicate "Morgan Wallen" (245 followers, one album). The
        // track carried that id, so the player trusted it and opened a page with one song.
        val duplicate = ProviderRef("deezer", "artist:386950521")
        val metadata = FakeMetadata(
            artists = listOf(
                deezerArtist("Morgan Wallen", id = "7188840", followers = 295_726),
                deezerArtist("Morgan Wallen", id = "386950521", followers = 245),
            ),
        )

        val links = ResolveTrackArtistsUseCase(metadata, registry())(track("Morgan Wallen", duplicate))

        assertEquals(listOf(ProviderRef("deezer", "artist:7188840")), sourcesOf(links))
    }

    @Test
    fun `a credited id the search never returns is kept, not overruled`() = runBlocking {
        // Two artists can genuinely share a name. Without the credited id in the same result set
        // there is no evidence they are duplicates, so hijacking the tap would be a guess.
        val own = ProviderRef("deezer", "artist:42")
        val metadata = FakeMetadata(artists = listOf(deezerArtist("Palace", id = "999", followers = 900_000)))

        val links = ResolveTrackArtistsUseCase(metadata, registry())(track("Palace", own))

        assertEquals(listOf(own), sourcesOf(links))
    }

    @Test
    fun `with no follower counts anywhere the provider's own ranking stands`() = runBlocking {
        val metadata = FakeMetadata(
            artists = listOf(deezerArtist("Coldplay", id = "8"), deezerArtist("Coldplay", id = "9")),
        )

        val links = ResolveTrackArtistsUseCase(metadata, registry())(track("Coldplay"))

        assertEquals(listOf(ProviderRef("deezer", "artist:8")), sourcesOf(links))
    }

    // ---- Collaborations ----------------------------------------------------------------------

    @Test
    fun `a collaboration billed as one string becomes one link per artist`() = runBlocking {
        // A YouTube-Mix track bills "Omar Courtz & De La Rose" as a single credit, and no catalogue
        // has an artist by that joined name — so before, neither artist was reachable.
        val metadata = FakeMetadata(
            byQuery = mapOf(
                "Omar Courtz & De La Rose" to emptyList(),
                "Omar Courtz" to listOf(deezerArtist("Omar Courtz", id = "14534981", followers = 52_220)),
                "De La Rose" to listOf(
                    deezerArtist("DeLaRose", id = "5882083", followers = 5),
                    deezerArtist("De la rose", id = "5421629", followers = 28_674),
                ),
            ),
        )

        val links = ResolveTrackArtistsUseCase(metadata, registry())(track("Omar Courtz & De La Rose"))

        assertEquals(listOf("Omar Courtz", "De La Rose"), links.map { it.name })
        // Each part is ranked too: "DeLaRose" folds to the same name and would have won on rank.
        assertEquals(
            listOf(ProviderRef("deezer", "artist:14534981"), ProviderRef("deezer", "artist:5421629")),
            sourcesOf(links),
        )
    }

    @Test
    fun `a duo whose name merely contains an ampersand is left whole`() = runBlocking {
        // "Simon & Garfunkel" is one act. The split is refused because "Garfunkel" names nobody.
        val metadata = FakeMetadata(
            byQuery = mapOf(
                "Simon & Garfunkel" to listOf(deezerArtist("Simon & Garfunkel", id = "2707", followers = 1_161_235)),
                "Simon" to listOf(deezerArtist("Simon", id = "50", followers = 4_000)),
                "Garfunkel" to emptyList(),
            ),
        )

        val links = ResolveTrackArtistsUseCase(metadata, registry())(track("Simon & Garfunkel"))

        assertEquals(listOf("Simon & Garfunkel"), links.map { it.name })
        assertEquals(listOf(ProviderRef("deezer", "artist:2707")), sourcesOf(links))
    }

    @Test
    fun `a band is left whole when its weakest part is smaller than the band`() = runBlocking {
        // Every part of "Earth, Wind & Fire" happens to name *some* artist, so "did they all resolve"
        // is not enough on its own — the band outranking the weakest of them is what settles it.
        val metadata = FakeMetadata(
            byQuery = mapOf(
                "Earth, Wind & Fire" to listOf(
                    deezerArtist("Earth, Wind & Fire", id = "264926312", followers = 134),
                    deezerArtist("Earth, Wind & Fire", id = "248", followers = 1_253_659),
                ),
                "Earth" to listOf(deezerArtist("Earth", id = "60", followers = 20_000)),
                "Wind" to listOf(deezerArtist("Wind", id = "77065", followers = 1_741)),
                "Fire" to listOf(deezerArtist("Fire", id = "70", followers = 900)),
            ),
        )

        val links = ResolveTrackArtistsUseCase(metadata, registry())(track("Earth, Wind & Fire"))

        assertEquals(listOf("Earth, Wind & Fire"), links.map { it.name })
        // ...and the band it opens is the real one, not the 134-follower duplicate above it.
        assertEquals(listOf(ProviderRef("deezer", "artist:248")), sourcesOf(links))
    }

    @Test
    fun `credits the provider already split are linked one by one`() = runBlocking {
        val metadata = FakeMetadata(
            byQuery = mapOf(
                "Future" to listOf(deezerArtist("Future", id = "20", followers = 5_000_000)),
                "Metro Boomin" to listOf(deezerArtist("Metro Boomin", id = "21", followers = 2_000_000)),
            ),
        )
        val song = Track(
            title = "Song",
            artists = listOf(ArtistCredit(name = "Future"), ArtistCredit(name = "Metro Boomin")),
            source = ProviderRef("deezer", "1"),
        )

        val links = ResolveTrackArtistsUseCase(metadata, registry())(song)

        assertEquals(listOf("Future", "Metro Boomin"), links.map { it.name })
        assertEquals(
            listOf(ProviderRef("deezer", "artist:20"), ProviderRef("deezer", "artist:21")),
            sourcesOf(links),
        )
    }

    // ---- Name reading (unchanged behaviour, still guarded) -------------------------------------

    @Test
    fun `a YouTube track's uploader name is looked up on the active provider`() = runBlocking {
        val metadata = FakeMetadata(artists = listOf(deezerArtist("Coldplay", "8")))

        val links = ResolveTrackArtistsUseCase(metadata, registry())(track("Coldplay"))

        assertEquals(listOf(ProviderRef("deezer", "artist:8")), sourcesOf(links))
        assertEquals(listOf(SearchCategory.ARTISTS), metadata.lastTypes)
    }

    @Test
    fun `a ref minted by another provider is re-resolved, not trusted`() = runBlocking {
        // An iTunes artist id means nothing to the Deezer artist screen — it would 404 the page.
        val metadata = FakeMetadata(artists = listOf(deezerArtist("Coldplay", "8")))

        val links = ResolveTrackArtistsUseCase(metadata, registry())(track("Coldplay", ProviderRef("itunes", "artist:999")))

        assertEquals(listOf(ProviderRef("deezer", "artist:8")), sourcesOf(links))
    }

    @Test
    fun `case and accents don't stop a match`() = runBlocking {
        val metadata = FakeMetadata(artists = listOf(deezerArtist("Rosalía", "3")))

        assertEquals(
            listOf(ProviderRef("deezer", "artist:3")),
            sourcesOf(ResolveTrackArtistsUseCase(metadata, registry())(track("ROSALIA"))),
        )
    }

    @Test
    fun `VEVO and Topic channels resolve to the artist behind them`() = runBlocking {
        val dualipa = ResolveTrackArtistsUseCase(FakeMetadata(listOf(deezerArtist("Dua Lipa", "7"))), registry())
        val radiohead = ResolveTrackArtistsUseCase(FakeMetadata(listOf(deezerArtist("Radiohead", "9"))), registry())

        assertEquals(ProviderRef("deezer", "artist:7"), dualipa(track("DuaLipaVEVO")).single().source)
        assertEquals(ProviderRef("deezer", "artist:9"), radiohead(track("Radiohead - Topic")).single().source)
    }

    @Test
    fun `a short name is not stripped down to a stem that could match anyone`() = runBlocking {
        // "The Band" must not become "the" and collide with every other "The …".
        val metadata = FakeMetadata(artists = listOf(deezerArtist("The Music", "11")))

        assertNull(ResolveTrackArtistsUseCase(metadata, registry())(track("The Band")).single().source)
    }

    @Test
    fun `a merely similar name is not the same artist`() = runBlocking {
        // Opening a tribute band's page from a tap on the real artist's name would be worse than
        // doing nothing, which is exactly what an unlinked name is.
        val metadata = FakeMetadata(artists = listOf(deezerArtist("Coldplay Tribute Band")))

        assertNull(ResolveTrackArtistsUseCase(metadata, registry())(track("Coldplay")).single().source)
    }

    @Test
    fun `a name with nowhere to go still renders, just untappable`() = runBlocking {
        val links = ResolveTrackArtistsUseCase(FakeMetadata(), registry())(track("Some Bedroom Producer"))

        assertEquals(listOf(LinkedArtist("Some Bedroom Producer", null)), links)
    }

    @Test
    fun `a failing lookup leaves the name unlinked instead of propagating`() = runBlocking {
        val metadata = FakeMetadata(error = IOException("offline"))

        assertNull(ResolveTrackArtistsUseCase(metadata, registry())(track("Coldplay")).single().source)
    }

    @Test
    fun `no artist, or no active provider, links nothing`() = runBlocking {
        val subject = ResolveTrackArtistsUseCase(FakeMetadata(), registry())

        assertEquals(emptyList<LinkedArtist>(), subject(track(artist = null)))
        assertEquals(emptyList<LinkedArtist>(), subject(track(artist = "  ")))
        assertEquals(emptyList<LinkedArtist>(), subject(null))
        // No metadata provider at all: the name still shows, it just opens nothing.
        val noProvider = ResolveTrackArtistsUseCase(FakeMetadata(), DefaultProviderRegistry())(track("Coldplay"))
        assertEquals(listOf(LinkedArtist("Coldplay", null)), noProvider)
    }

    @Test
    fun `the answer is memoized, including a miss`() = runBlocking {
        // The player asks on every track change, and a queue is full of songs by the same artist.
        val metadata = FakeMetadata()
        val subject = ResolveTrackArtistsUseCase(metadata, registry())

        subject(track("Nobody"))
        subject(track("Nobody"))
        subject(track("nobody"))

        assertEquals(1, metadata.queries)
    }
}
