package fm.rizx.player.data.plugin.bridge

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Defensive JSON → domain mapping for untrusted plugin output. */
class JsModelMappersTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `maps tracks, artists and albums with their declared source`() {
        val payload = """
            {
              "tracks": [
                {"title":"Starboy","artist":"The Weeknd","durationMs":230000,"source":{"provider":"deezer","id":"t1"}}
              ],
              "artists": [
                {"name":"Daft Punk","source":{"provider":"deezer","id":"a1"},"thumbnail":"http://img/a.jpg"}
              ],
              "albums": [
                {"title":"Discovery","artist":"Daft Punk","source":{"provider":"deezer","id":"al1"}}
              ]
            }
        """.trimIndent()

        val results = JsModelMappers.parseSearchResults(payload, fallbackProvider = "fallback", json = json)

        assertEquals(1, results.tracks.size)
        assertEquals("Starboy", results.tracks[0].title)
        assertEquals("The Weeknd", results.tracks[0].artists.firstOrNull()?.name)
        assertEquals(230000L, results.tracks[0].durationMs)
        assertEquals("deezer", results.tracks[0].source.provider)
        assertEquals("t1", results.tracks[0].source.id)

        assertEquals("Daft Punk", results.artists.single().name)
        assertNotNull(results.artists.single().artwork)
        assertEquals("Discovery", results.albums.single().title)
        assertEquals("Daft Punk", results.albums.single().artists.firstOrNull()?.name)
    }

    @Test
    fun `drops malformed items instead of failing the whole result`() {
        val payload = """
            {"tracks":[
              {"title":"Good","source":{"provider":"p","id":"1"}},
              {"nope":"no title"},
              {"title":"Also good","source":{"provider":"p","id":"2"}}
            ]}
        """.trimIndent()

        val results = JsModelMappers.parseSearchResults(payload, "p", json)

        assertEquals(2, results.tracks.size)
        assertEquals(listOf("Good", "Also good"), results.tracks.map { it.title })
    }

    @Test
    fun `falls back to the plugin provider id when an item omits its source`() {
        val payload = """{"artists":[{"name":"NoSource"}]}"""
        val results = JsModelMappers.parseSearchResults(payload, fallbackProvider = "discogs", json = json)
        assertEquals("discogs", results.artists.single().source.provider)
    }

    @Test
    fun `converts seconds-based durations to milliseconds`() {
        val payload = """{"tracks":[{"title":"S","duration":230,"source":{"provider":"p","id":"1"}}]}"""
        val results = JsModelMappers.parseSearchResults(payload, "p", json)
        assertEquals(230_000L, results.tracks.single().durationMs)
    }

    @Test
    fun `empty or garbage json yields empty results`() {
        assertTrue(JsModelMappers.parseSearchResults("not json", "p", json).isEmpty)
        assertTrue(JsModelMappers.parseSearchResults("[]", "p", json).isEmpty)
        assertNull(JsModelMappers.parseSearchResults("{}", "p", json).tracks.firstOrNull())
    }
}
