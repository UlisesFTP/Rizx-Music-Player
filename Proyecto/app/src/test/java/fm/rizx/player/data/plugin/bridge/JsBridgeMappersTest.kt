package fm.rizx.player.data.plugin.bridge

import fm.rizx.player.domain.model.ProviderRef
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mapping layer for the provider kinds the runtime learned to bridge. Plugin output is untrusted
 * and inconsistently shaped, so each of these asserts a shape a **real** registry plugin emits.
 */
class JsBridgeMappersTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `artwork survives every shape plugins use for it`() {
        // `images` is an array upstream — read as a string it silently yielded no cover at all.
        val asArray = """{"tracks":[{"title":"A","images":["https://cdn/x.jpg"],"source":{"provider":"p","id":"1"}}]}"""
        val asObjectArray = """{"tracks":[{"title":"B","images":[{"url":"https://cdn/y.jpg"}],"source":{"provider":"p","id":"2"}}]}"""
        val asString = """{"tracks":[{"title":"C","coverArt":"https://cdn/z.jpg","source":{"provider":"p","id":"3"}}]}"""

        assertEquals("https://cdn/x.jpg", JsModelMappers.parseSearchResults(asArray, "p", json).tracks.single().artwork?.items?.first()?.url)
        assertEquals("https://cdn/y.jpg", JsModelMappers.parseSearchResults(asObjectArray, "p", json).tracks.single().artwork?.items?.first()?.url)
        assertEquals("https://cdn/z.jpg", JsModelMappers.parseSearchResults(asString, "p", json).tracks.single().artwork?.items?.first()?.url)
    }

    @Test
    fun `search results carry playlists too`() {
        val payload = """{"playlists":[{"name":"Chill","source":{"provider":"p","id":"pl1"}}]}"""

        val results = JsModelMappers.parseSearchResults(payload, "p", json)

        assertEquals("Chill", results.playlists.single().name)
        assertEquals("pl1", results.playlists.single().source.id)
    }

    @Test
    fun `album detail keeps its track list, release year and total`() {
        val payload = """
            {"title":"After Hours","artists":[{"name":"The Weeknd"}],"releaseDate":"2020-03-20",
             "trackCount":14,
             "tracks":[{"title":"Alone Again","durationMs":250057,"source":{"provider":"p","id":"t1"}}]}
        """.trimIndent()

        val album = JsModelMappers.parseAlbum(payload, "p", ProviderRef("p", "album:1"), json)

        assertNotNull(album)
        assertEquals("After Hours", album!!.title)
        assertEquals(2020, album.year)
        assertEquals(1, album.tracks.size)
        assertEquals(250057L, album.tracks.single().durationMs)
    }

    @Test
    fun `artist detail composes the three separate plugin calls`() {
        val bio = """{"name":"Dua Lipa","bio":"Pop singer"}"""
        val top = """[{"title":"Levitating","source":{"provider":"p","id":"t1"}}]"""
        val albums = """[{"title":"Future Nostalgia","source":{"provider":"p","id":"a1"}}]"""

        val artist = JsModelMappers.parseArtistDetail(bio, top, albums, "p", ProviderRef("p", "artist:9"), json)

        assertNotNull(artist)
        assertEquals("Dua Lipa", artist!!.name)
        assertEquals("Pop singer", artist.bio)
        assertEquals(listOf("Levitating"), artist.topTracks.map { it.title })
        assertEquals(listOf("Future Nostalgia"), artist.albums.map { it.title })
    }

    @Test
    fun `a bare-string bio is accepted, and nothing at all maps to null`() {
        val fromString = JsModelMappers.parseArtistDetail("\"Just prose\"", null, null, "p", ProviderRef("p", "artist:9"), json)
        assertEquals("Just prose", fromString?.bio)

        assertNull(JsModelMappers.parseArtistDetail(null, null, null, "p", ProviderRef("p", "artist:9"), json))
    }

    @Test
    fun `lyrics map from a bare string, a plain object, and timed lines`() {
        assertEquals("la la", JsModelMappers.parseLyrics("\"la la\"", "Acme", json)?.plain)
        assertEquals("la la", JsModelMappers.parseLyrics("""{"lyrics":"la la"}""", "Acme", json)?.plain)

        val timed = JsModelMappers.parseLyrics("""{"lines":[{"timeMs":1500,"text":"first"}]}""", "Acme", json)
        assertTrue(timed!!.isSynced)
        assertEquals(1500L, timed.lines.single().timeMs)
        assertEquals("Acme", timed.sourceName)
    }

    @Test
    fun `a playlist fetched by url keeps its name and tracks`() {
        val payload = """{"name":"Weekly","description":"Fresh","tracks":[{"title":"S","source":{"provider":"p","id":"t"}}]}"""

        val preview = JsModelMappers.parsePlaylistPreview(payload, "p", "https://x/pl", json)

        assertEquals("Weekly", preview!!.name)
        assertEquals("Fresh", preview.description)
        assertEquals(1, preview.tracks.size)
    }

    @Test
    fun `a track marshalled out to JS round-trips through the mapper`() {
        val original = fm.rizx.player.domain.model.Track(
            title = "Blinding Lights",
            artists = listOf(fm.rizx.player.domain.model.ArtistCredit("The Weeknd", source = ProviderRef("p", "artist:1"))),
            durationMs = 200_040,
            source = ProviderRef("p", "t1"),
        )

        val encoded = JsModelMappers.trackToJson(original).toString()
        val decoded = JsModelMappers.parseTracks("[$encoded]", "p", json).single()

        assertEquals(original.title, decoded.title)
        assertEquals(original.durationMs, decoded.durationMs)
        assertEquals(original.source, decoded.source)
        assertEquals("The Weeknd", decoded.artists.single().name)
    }

    @Test
    fun `garbage from a plugin degrades to empty rather than throwing`() {
        assertTrue(JsModelMappers.parseSearchResults("not json", "p", json).isEmpty)
        assertNull(JsModelMappers.parseAlbum("[]", "p", ProviderRef("p", "album:1"), json))
        assertNull(JsModelMappers.parseLyrics("null", "Acme", json))
        assertNull(JsModelMappers.parsePlaylistPreview("42", "p", "https://x", json))
    }
}
