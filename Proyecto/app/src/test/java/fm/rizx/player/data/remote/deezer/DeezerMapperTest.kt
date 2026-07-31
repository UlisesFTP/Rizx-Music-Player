package fm.rizx.player.data.remote.deezer

import fm.rizx.player.domain.model.AlbumKind
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeezerMapperTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val albumBody = """
        {"id":302127,"title":"Discovery","cover_xl":"https://cover/1000x1000.jpg",
         "cover_medium":"https://cover/250.jpg","release_date":"2001-03-07","nb_tracks":14,"duration":3660,
         "artist":{"id":27,"name":"Daft Punk","picture_xl":"https://pic/xl.jpg"},
         "tracks":{"data":[
           {"id":3135556,"title":"One More Time","duration":320,"artist":{"id":27,"name":"Daft Punk"},"track_position":1},
           {"id":3135557,"title":"Aerodynamic","duration":212,"artist":{"id":27,"name":"Daft Punk"},"track_position":2}
         ]}}
    """.trimIndent()

    @Test
    fun `album maps to a full Album with tracklist and deezer identity`() {
        val album = json.decodeFromString(DeezerAlbumDto.serializer(), albumBody).toAlbum()!!

        assertEquals("Discovery", album.title)
        assertEquals(2001, album.year)
        assertEquals(14, album.totalTracks)
        assertEquals(3_660_000L, album.durationMs)
        assertEquals("deezer", album.source.provider)
        assertEquals("album:302127", album.source.id)
        assertEquals("Daft Punk", album.artists.single().name)
        assertEquals(listOf("One More Time", "Aerodynamic"), album.tracks.map { it.title })
        val t0 = album.tracks.first()
        assertEquals("3135556", t0.source.id)            // track identity ≠ album identity
        assertEquals(320_000L, t0.durationMs)
        assertEquals(1, t0.trackNumber)
    }

    @Test
    fun `artist maps followers, top tracks and album refs`() {
        val header = json.decodeFromString(
            DeezerArtistDto.serializer(),
            """{"id":27,"name":"Daft Punk","picture_xl":"https://pic/xl.jpg","nb_fan":9600000}""",
        )
        val album = json.decodeFromString(DeezerAlbumDto.serializer(), albumBody).toAlbum()!!
        val topTrack = album.tracks.first()

        val artist = header.toArtist(topTracks = listOf(topTrack), albums = album.artists.let { emptyList() })
            ?: error("null artist")

        assertEquals("Daft Punk", artist.name)
        assertEquals(9_600_000L, artist.followers)
        assertEquals("artist:27", artist.source.id)
        assertEquals(listOf("One More Time"), artist.topTracks.map { it.title })
    }

    @Test
    fun `a discography row keeps its kind and its year`() {
        val rows = json.decodeFromString(
            DeezerAlbumsResponse.serializer(),
            """{"data":[
                {"id":1,"title":"Random Access Memories","release_date":"2013-05-17","record_type":"album"},
                {"id":2,"title":"Instant Crush","release_date":"2013-11-08","record_type":"single"},
                {"id":3,"title":"Alive 1997","release_date":"2001-10-01","record_type":"ep"},
                {"id":4,"title":"Musique Vol. 1","release_date":"2006-03-27","record_type":"compilation"},
                {"id":5,"title":"Unlabelled"}
            ]}""",
        ).data.mapNotNull { it.toAlbumRef() }

        assertEquals(
            listOf(AlbumKind.ALBUM, AlbumKind.SINGLE, AlbumKind.EP, AlbumKind.COMPILATION, AlbumKind.UNKNOWN),
            rows.map { it.kind },
        )
        assertEquals(listOf(2013, 2013, 2001, 2006, null), rows.map { it.year })
    }

    @Test
    fun `search rows become grouped results with distinct refs`() {
        val results = json.decodeFromString(
            DeezerSearchResponse.serializer(),
            """{"data":[
                {"id":1,"title":"A","duration":100,"artist":{"id":27,"name":"Daft Punk"},"album":{"id":9,"title":"Disc"}},
                {"id":2,"title":"B","duration":120,"artist":{"id":27,"name":"Daft Punk"},"album":{"id":9,"title":"Disc"}}
            ]}""",
        ).data.toSearchResults()

        assertEquals(2, results.tracks.size)
        assertEquals(1, results.artists.size)  // deduped
        assertEquals(1, results.albums.size)
        assertEquals("artist:27", results.artists.single().source.id)
        assertEquals("album:9", results.albums.single().source.id)
    }

    @Test
    fun `rawId strips the album and artist namespace prefixes`() {
        assertEquals("302127", DeezerIds.rawId(DeezerIds.album(302127)))
        assertEquals("27", DeezerIds.rawId(DeezerIds.artist(27)))
    }

    @Test
    fun `rows without an id are dropped`() {
        val dto = json.decodeFromString(DeezerTrackDto.serializer(), """{"title":"No Id"}""")
        assertNull(dto.toTrackOrNull())
    }

    @Test
    fun `album artwork upsizes to the xl cover`() {
        val album = json.decodeFromString(DeezerAlbumDto.serializer(), albumBody).toAlbum()!!
        assertTrue(album.artwork!!.items.any { it.url == "https://cover/1000x1000.jpg" && it.width == 1000 })
    }
}
