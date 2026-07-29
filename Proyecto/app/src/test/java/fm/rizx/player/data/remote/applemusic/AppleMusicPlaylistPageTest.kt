package fm.rizx.player.data.remote.applemusic

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Parses Apple's published `MusicPlaylist` JSON-LD. The fixture is trimmed from the real
 * `music.apple.com/us/playlist/todays-hits/…` page, so the shapes here are the ones that actually
 * ship — including the two that force the design: rows carry **no artist**, and their image is a
 * 1200×630 social-card crop rather than a cover.
 */
class AppleMusicPlaylistPageTest {

    private val parser = AppleMusicPlaylistPage(OkHttpClient(), Json { ignoreUnknownKeys = true })

    private fun page(body: String) = """
        <html><head>
        <script type="application/ld+json">$body</script>
        </head><body></body></html>
    """.trimIndent()

    private val realShape = """
        {"@context":"http://schema.org","@type":"MusicPlaylist","name":"Today's Hits",
         "numTracks":50,
         "track":[
           {"@type":"MusicRecording","name":"Been By Now",
            "url":"https://music.apple.com/us/song/been-by-now/6792676860","duration":"PT3M33S",
            "audio":{"@type":"AudioObject","thumbnailUrl":"https://is1.mzstatic.com/x/1200x630bb.jpg"}},
           {"@type":"MusicRecording","name":"stupid song",
            "url":"https://music.apple.com/us/song/stupid-song/1889992115","duration":"PT3M29S"}
         ]}
    """.trimIndent()

    @Test
    fun `reads the playlist name and every track id in Apple's running order`() {
        val parsed = parser.parse(page(realShape))!!

        assertEquals("Today's Hits", parsed.name)
        assertEquals(listOf("6792676860", "1889992115"), parsed.rows.map { it.trackId })
        assertEquals(listOf("Been By Now", "stupid song"), parsed.rows.map { it.title })
    }

    @Test
    fun `durations come back in milliseconds`() {
        val rows = parser.parse(page(realShape))!!.rows

        assertEquals(213_000L, rows[0].durationMs) // PT3M33S
        assertEquals(209_000L, rows[1].durationMs) // PT3M29S
    }

    @Test
    fun `an hour-long duration parses too`() {
        assertEquals(3_723_000L, parser.isoDurationMs("PT1H2M3S"))
        assertNull(parser.isoDurationMs("garbage"))
    }

    @Test
    fun `a row without a resolvable id is dropped rather than guessed at`() {
        val parsed = parser.parse(
            page("""{"@type":"MusicPlaylist","name":"P","track":[{"@type":"MusicRecording","name":"No URL"}]}"""),
        )
        assertNull(parsed) // nothing usable left
    }

    @Test
    fun `a page that is not a playlist yields nothing`() {
        assertNull(parser.parse(page("""{"@type":"MusicAlbum","name":"An album"}""")))
        assertNull(parser.parse("<html><body>no json-ld here</body></html>"))
        assertNull(parser.parse(page("{ not json")))
    }

    @Test
    fun `a JSON-LD array picks the playlist out of it`() {
        val parsed = parser.parse(
            page(
                """[{"@type":"BreadcrumbList"},
                    {"@type":"MusicPlaylist","name":"P",
                     "track":[{"@type":"MusicRecording","name":"S","url":"https://music.apple.com/us/song/s/42"}]}]""",
            ),
        )!!
        assertEquals("P", parsed.name)
        assertEquals(listOf("42"), parsed.rows.map { it.trackId })
    }
}
