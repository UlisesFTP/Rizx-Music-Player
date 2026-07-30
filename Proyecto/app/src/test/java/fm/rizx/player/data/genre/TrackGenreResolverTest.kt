package fm.rizx.player.data.genre

import fm.rizx.player.data.provider.DefaultProviderRegistry
import fm.rizx.player.data.remote.itunes.ItunesApi
import fm.rizx.player.data.remote.itunes.ItunesResultDto
import fm.rizx.player.data.remote.itunes.ItunesSearchResponse
import fm.rizx.player.domain.model.Album
import fm.rizx.player.domain.model.AlbumRef
import fm.rizx.player.domain.model.ArtistCredit
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.SearchCapability
import fm.rizx.player.domain.model.SearchParams
import fm.rizx.player.domain.model.SearchResults
import fm.rizx.player.domain.model.SoundGenre
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.provider.MetadataProvider
import fm.rizx.player.domain.provider.ProviderKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The genre chain, pinned on what it must never do: pay for a lookup it doesn't need, believe an
 * unverified match, or let a dead provider surface as an exception in the middle of playback.
 */
class TrackGenreResolverTest {

    private fun track(
        title: String = "Bow Down",
        artist: String? = "Ice Cube",
        tags: List<String> = emptyList(),
        albumRef: ProviderRef? = null,
        provider: String = "deezer",
    ) = Track(
        title = title,
        artists = listOfNotNull(artist?.let { ArtistCredit(it) }),
        album = albumRef?.let { AlbumRef(title = "Some Album", source = it) },
        tags = tags,
        source = ProviderRef(provider, "1"),
    )

    /** A catalogue that can own namespaces and answer by id, counting what it was asked. */
    private class FakeCatalogue(
        override val id: String,
        override val ownedNamespaces: Set<String> = setOf(id),
        private val trackTags: Map<String, List<String>> = emptyMap(),
        private val albumTags: Map<String, List<String>> = emptyMap(),
        private val explode: Boolean = false,
    ) : MetadataProvider {
        override val kind = ProviderKind.METADATA
        override val name = id
        override val searchCapabilities = setOf(SearchCapability.UNIFIED)
        var trackDetails = 0
        var albumDetails = 0

        override suspend fun search(params: SearchParams) = SearchResults()

        override suspend fun trackDetail(source: ProviderRef): Track? {
            trackDetails++
            if (explode) error("provider is down")
            val tags = trackTags[source.id] ?: return null
            return Track(title = "x", tags = tags, source = source)
        }

        override suspend fun albumDetail(source: ProviderRef): Album? {
            albumDetails++
            if (explode) error("provider is down")
            val tags = albumTags[source.id] ?: return null
            return Album(title = "x", tags = tags, source = source)
        }
    }

    private class FakeItunes(
        private val songs: List<ItunesResultDto> = emptyList(),
        private val artists: List<ItunesResultDto> = emptyList(),
    ) : ItunesApi {
        var songSearches = 0
        var artistSearches = 0

        override suspend fun search(
            term: String,
            media: String,
            entity: String,
            limit: Int,
            attribute: String?,
            country: String?,
        ): ItunesSearchResponse {
            val rows = if (entity == "musicArtist") { artistSearches++; artists } else { songSearches++; songs }
            return ItunesSearchResponse(rows.size, rows)
        }

        override suspend fun lookup(id: String, entity: String, limit: Int, country: String?) =
            ItunesSearchResponse()
    }

    private fun songRow(title: String, artist: String, genre: String?) = ItunesResultDto(
        trackId = 42,
        trackName = title,
        artistName = artist,
        primaryGenreName = genre,
    )

    private fun resolver(itunes: ItunesApi = FakeItunes(), vararg providers: MetadataProvider) =
        TrackGenreResolver(
            registry = DefaultProviderRegistry().apply { providers.forEach { register(it) } },
            itunes = itunes,
            io = Dispatchers.Unconfined,
        )

    @Test
    fun `a tag on the track answers for free`() = runTest {
        val itunes = FakeItunes()
        val owner = FakeCatalogue("deezer")

        val resolved = resolver(itunes, owner).resolve(track(tags = listOf("Reggaetón")))

        assertEquals(SoundGenre.REGGAETON, resolved.genre)
        assertEquals("Reggaetón", resolved.label)
        assertEquals("nothing else should have been asked", 0, itunes.songSearches + itunes.artistSearches)
        assertEquals(0, owner.trackDetails)
    }

    @Test
    fun `the owner is asked by id before any search`() = runTest {
        val itunes = FakeItunes(songs = listOf(songRow("Bow Down", "Ice Cube", "Pop")))
        val owner = FakeCatalogue("deezer", trackTags = mapOf("1" to listOf("Rap/Hip Hop")))

        val resolved = resolver(itunes, owner).resolve(track())

        assertEquals(SoundGenre.HIPHOP, resolved.genre)
        assertEquals(1, owner.trackDetails)
        assertEquals(0, itunes.songSearches)
    }

    @Test
    fun `the album carries the genre when the track doesn't — the Deezer case`() = runTest {
        val itunes = FakeItunes()
        val owner = FakeCatalogue(
            id = "deezer",
            albumTags = mapOf("album:9" to listOf("Musica Mexicana")),
        )

        val resolved = resolver(itunes, owner).resolve(track(albumRef = ProviderRef("deezer", "album:9")))

        assertEquals(SoundGenre.LATIN_REGIONAL, resolved.genre)
        assertEquals("Musica Mexicana", resolved.label)
        assertEquals(1, owner.albumDetails)
        assertEquals(0, itunes.songSearches)
    }

    @Test
    fun `a verified iTunes row may lend its genre`() = runTest {
        val itunes = FakeItunes(
            songs = listOf(
                songRow("Bow Down", "Westside Connection", "Pop"), // wrong artist — refused
                songRow("Bow Down", "Ice Cube", "Hip-Hop/Rap"),
            ),
        )

        val resolved = resolver(itunes, FakeCatalogue("deezer")).resolve(track())

        assertEquals(SoundGenre.HIPHOP, resolved.genre)
        assertEquals(1, itunes.songSearches)
    }

    @Test
    fun `an unverified iTunes row is refused, and so is the wrong artist`() = runTest {
        val itunes = FakeItunes(
            songs = listOf(songRow("Bow Down", "Somebody Else", "Classical")),
            artists = listOf(ItunesResultDto(artistName = "Somebody Else", primaryGenreName = "Classical")),
        )

        val resolved = resolver(itunes, FakeCatalogue("deezer")).resolve(track())

        // Both halves refused: a wrong cover is a lie, and so is a wrong genre.
        assertEquals(SoundGenre.UNKNOWN, resolved.genre)
        assertNull(resolved.label)
    }

    @Test
    fun `the artist's own genre is the last resort, and it is asked once per artist`() = runTest {
        val itunes = FakeItunes(artists = listOf(ItunesResultDto(artistName = "Ice Cube", primaryGenreName = "Hip-Hop/Rap")))
        val resolver = resolver(itunes, FakeCatalogue("deezer"))

        assertEquals(SoundGenre.HIPHOP, resolver.resolve(track(title = "Bow Down")).genre)
        assertEquals(SoundGenre.HIPHOP, resolver.resolve(track(title = "It Was A Good Day")).genre)

        assertEquals("a queue is a handful of artists, not a lookup per song", 1, itunes.artistSearches)
    }

    @Test
    fun `a track with no artist is never searched — a bare title matches strangers`() = runTest {
        val itunes = FakeItunes(songs = listOf(songRow("Intro", "Someone", "Rock")))

        val resolved = resolver(itunes, FakeCatalogue("deezer")).resolve(track(title = "Intro", artist = null))

        assertEquals(SoundGenre.UNKNOWN, resolved.genre)
        assertEquals(0, itunes.songSearches + itunes.artistSearches)
    }

    @Test
    fun `a provider that throws is not an exception here`() = runTest {
        val owner = FakeCatalogue("deezer", explode = true)

        val resolved = resolver(FakeItunes(), owner).resolve(track(albumRef = ProviderRef("deezer", "album:9")))

        assertEquals(SoundGenre.UNKNOWN, resolved.genre)
    }

    @Test
    fun `an unrecognised wording is still reported, even though it shapes nothing`() = runTest {
        // The badge shows what the source said; a family we don't model is a flat curve, not silence.
        val resolved = resolver(FakeItunes(), FakeCatalogue("deezer")).resolve(track(tags = listOf("Zzyzx")))

        assertEquals(SoundGenre.UNKNOWN, resolved.genre)
        assertEquals("Zzyzx", resolved.label)
    }
}
