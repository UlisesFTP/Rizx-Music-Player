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

class ResolveArtistRefUseCaseTest {

    /** Only its id matters here — the registry answers "who is the active metadata provider". */
    private class FakeMetadataProvider(override val id: String) : MetadataProvider {
        override val kind = ProviderKind.METADATA
        override val name = id
        override val searchCapabilities: Set<SearchCapability> = setOf(SearchCapability.ARTISTS)
        override suspend fun search(params: SearchParams) = SearchResults()
    }

    private class FakeMetadata(
        private val artists: List<ArtistRef> = emptyList(),
        private val error: Exception? = null,
    ) : MetadataRepository {
        var queries = 0
            private set
        var lastTypes: List<SearchCategory>? = null
            private set

        override suspend fun search(params: SearchParams): SearchResults {
            queries++
            lastTypes = params.types
            error?.let { throw it }
            return SearchResults(artists = artists)
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

    private fun deezerArtist(name: String, id: String = "1") =
        ArtistRef(name = name, source = ProviderRef("deezer", "artist:$id"))

    @Test
    fun `an artist the active provider already identified is used as-is`() = runBlocking {
        val metadata = FakeMetadata()
        val ref = ProviderRef("deezer", "artist:42")

        val resolved = ResolveArtistRefUseCase(metadata, registry())(track("Coldplay", ref))

        assertEquals(ref, resolved)
        assertEquals("no lookup should have been needed", 0, metadata.queries)
    }

    @Test
    fun `a YouTube track's uploader name is looked up on the active provider`() = runBlocking {
        // The case the player cares about: a Mix song credits a name and carries no artist ref at all.
        val metadata = FakeMetadata(artists = listOf(deezerArtist("Coldplay", "8")))

        val resolved = ResolveArtistRefUseCase(metadata, registry())(track("Coldplay"))

        assertEquals(ProviderRef("deezer", "artist:8"), resolved)
        assertEquals(listOf(SearchCategory.ARTISTS), metadata.lastTypes)
    }

    @Test
    fun `a ref minted by another provider is re-resolved, not trusted`() = runBlocking {
        // An iTunes artist id means nothing to the Deezer artist screen — it would 404 the page.
        val metadata = FakeMetadata(artists = listOf(deezerArtist("Coldplay", "8")))
        val itunes = ProviderRef("itunes", "artist:999")

        val resolved = ResolveArtistRefUseCase(metadata, registry())(track("Coldplay", itunes))

        assertEquals(ProviderRef("deezer", "artist:8"), resolved)
    }

    @Test
    fun `case and accents don't stop a match`() = runBlocking {
        val metadata = FakeMetadata(artists = listOf(deezerArtist("Rosalía", "3")))

        val resolved = ResolveArtistRefUseCase(metadata, registry())(track("ROSALIA"))

        assertEquals(ProviderRef("deezer", "artist:3"), resolved)
    }

    @Test
    fun `a YouTube channel name still finds the artist behind it`() = runBlocking {
        // Real case: the Mix credits "ModjoOfficial", Deezer calls them "Modjo".
        val metadata = FakeMetadata(artists = listOf(deezerArtist("Modjo", "5")))

        val resolved = ResolveArtistRefUseCase(metadata, registry())(track("ModjoOfficial"))

        assertEquals(ProviderRef("deezer", "artist:5"), resolved)
    }

    @Test
    fun `VEVO and Topic channels resolve too`() = runBlocking {
        val dualipa = ResolveArtistRefUseCase(FakeMetadata(listOf(deezerArtist("Dua Lipa", "7"))), registry())
        val radiohead = ResolveArtistRefUseCase(FakeMetadata(listOf(deezerArtist("Radiohead", "9"))), registry())

        assertEquals(ProviderRef("deezer", "artist:7"), dualipa(track("DuaLipaVEVO")))
        assertEquals(ProviderRef("deezer", "artist:9"), radiohead(track("Radiohead - Topic")))
    }

    @Test
    fun `a short name is not stripped down to a stem that could match anyone`() = runBlocking {
        // "The Band" must not become "the" and collide with every other "The …".
        val metadata = FakeMetadata(artists = listOf(deezerArtist("The Music", "11")))

        assertNull(ResolveArtistRefUseCase(metadata, registry())(track("The Band")))
    }

    @Test
    fun `a merely similar name is not the same artist`() = runBlocking {
        // Opening a tribute band's page from a tap on the real artist's name would be worse than
        // doing nothing, which is exactly what the player does with a null.
        val metadata = FakeMetadata(artists = listOf(deezerArtist("Coldplay Tribute Band")))

        assertNull(ResolveArtistRefUseCase(metadata, registry())(track("Coldplay")))
    }

    @Test
    fun `no equivalent means no navigation`() = runBlocking {
        assertNull(ResolveArtistRefUseCase(FakeMetadata(), registry())(track("Some Bedroom Producer")))
    }

    @Test
    fun `a failing lookup resolves to null instead of propagating`() = runBlocking {
        val metadata = FakeMetadata(error = IOException("offline"))

        assertNull(ResolveArtistRefUseCase(metadata, registry())(track("Coldplay")))
    }

    @Test
    fun `a track with no artist, or no active provider, resolves to null`() = runBlocking {
        val subject = ResolveArtistRefUseCase(FakeMetadata(), registry())

        assertNull(subject(track(artist = null)))
        assertNull(subject(track(artist = "  ")))
        assertNull(subject(null))
        assertNull(ResolveArtistRefUseCase(FakeMetadata(), DefaultProviderRegistry())(track("Coldplay")))
    }

    @Test
    fun `the answer is memoized, including a miss`() = runBlocking {
        // The player asks on every track change, and a queue is full of songs by the same artist.
        val metadata = FakeMetadata()
        val subject = ResolveArtistRefUseCase(metadata, registry())

        subject(track("Nobody"))
        subject(track("Nobody"))
        subject(track("nobody"))

        assertEquals(1, metadata.queries)
    }
}
