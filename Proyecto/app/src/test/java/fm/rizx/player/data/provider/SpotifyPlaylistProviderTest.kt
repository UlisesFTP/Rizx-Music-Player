package fm.rizx.player.data.provider

import fm.rizx.player.core.error.AppError
import fm.rizx.player.data.remote.spotify.SpotifyPathfinderClient
import fm.rizx.player.domain.model.coverUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Spotify import via the public embed page. The fixture mirrors the **real** `__NEXT_DATA__` shape
 * captured from `open.spotify.com/embed/playlist/…`: `props.pageProps.state.data.entity` with a
 * `trackList` of `{uri, title, subtitle, duration}` (duration in **milliseconds**).
 */
class SpotifyPlaylistProviderTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun provider(pathfinder: SpotifyPathfinderClient? = null) = SpotifyPlaylistProvider(
        client = OkHttpClient(),
        json = json,
        io = Dispatchers.Unconfined,
        embedUrlTemplate = server.url("/embed/playlist/").toString() + "%s",
        albumEmbedUrlTemplate = server.url("/embed/album/").toString() + "%s",
        pathfinder = pathfinder,
    )

    /** A pathfinder client wired to the same MockWebServer, so one dispatcher can serve both hops. */
    private fun pathfinder() = SpotifyPathfinderClient(
        client = OkHttpClient(),
        json = json,
        queryUrl = server.url("/pathfinder/v1/query").toString(),
        webPlayerUrl = server.url("/playlist/").toString() + "%s",
    )

    /** The anonymous bearer the real embed publishes alongside the tracklist. */
    private val sessionJson = """"settings":{"session":{"accessToken":"anon-token","isAnonymous":true}},"""

    private fun pathfinderPage(totalCount: Int, ids: List<Int>): String {
        val items = ids.joinToString(",") { n ->
            """{"itemV2":{"__typename":"TrackResponseWrapper","data":{
                 "uri":"spotify:track:pf%022d","name":"Paged $n","trackDuration":{"totalMilliseconds":$n},
                 "artists":{"items":[{"profile":{"name":"Tyler, The Creator"}}]},
                 "albumOfTrack":{"uri":"spotify:album:alb$n","name":"Album $n",
                   "coverArt":{"sources":[{"url":"https://i.scdn.co/image/cover$n","width":640,"height":640}]}}
               }}}""".format(n)
        }
        return """{"data":{"playlistV2":{"content":{"totalCount":$totalCount,"items":[$items]}}}}"""
    }

    private fun embedHtml(tracks: String, name: String = "Today’s Top Hits", coverArt: String = ""): String =
        """<!DOCTYPE html><html><body><div>markup</div>
           <script id="__NEXT_DATA__" type="application/json">
           {"props":{"pageProps":{"state":{$sessionJson"data":{"entity":
             {"type":"playlist","name":"$name","subtitle":"Spotify",$coverArt"trackList":[$tracks]}
           }}}}}
           </script></body></html>
        """.trimIndent()

    private fun albumHtml(tracks: String, name: String, coverArt: String): String =
        """<script id="__NEXT_DATA__" type="application/json">
           {"props":{"pageProps":{"state":{"data":{"entity":
             {"type":"album","name":"$name",$coverArt"trackList":[$tracks]}
           }}}}}
           </script>""".trimIndent()

    /** Shape the live embed actually returns: width/height are usually null. */
    private fun coverArtJson(url: String) = """"coverArt":{"sources":[{"url":"$url","width":null,"height":null}]},"""

    private fun trackJson(id: String, title: String, artists: String, durationMs: Long) =
        """{"uri":"spotify:track:$id","title":"$title","subtitle":"$artists","duration":$durationMs,"isPlayable":true}"""

    @Test
    fun `canHandle accepts spotify playlist links and rejects others`() {
        val p = provider()
        assertTrue(p.canHandle("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=abc"))
        assertTrue(p.canHandle("https://open.spotify.com/intl-es/playlist/37i9dQZF1DXcBWIGoYBM5M"))
        assertTrue(p.canHandle("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"))
        assertFalse(p.canHandle("https://open.spotify.com/track/65DbTqJKhbwqYbZ1Okr0rc")) // not a playlist
        assertFalse(p.canHandle("https://www.deezer.com/playlist/123"))
    }

    @Test
    fun `reads the embed tracklist into domain tracks`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                embedHtml(
                    listOf(
                        trackJson("65DbTqJKhbwqYbZ1Okr0rc", "Choosin' Texas", "Ella Langley", 232226),
                        trackJson("2plbrEY59IikOBgBGLjaoe", "Dai Dai", "Shakira, Burna Boy", 180000),
                    ).joinToString(","),
                ),
            ),
        )

        val preview = provider().fetchPlaylist("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M")

        assertEquals("Today’s Top Hits", preview.name)
        assertEquals(listOf("Choosin' Texas", "Dai Dai"), preview.tracks.map { it.title })
        assertEquals(232226L, preview.tracks[0].durationMs) // duration is already ms
        // Identity is the Spotify track id; audio resolves later by artist+title.
        assertEquals("spotify:65DbTqJKhbwqYbZ1Okr0rc", preview.tracks[0].source.identityKey)
        // Comma-joined artists are split so the first stays clean for the streaming search.
        assertEquals(listOf("Shakira", "Burna Boy"), preview.tracks[1].artists.map { it.name })
    }

    @Test
    fun `reads the playlist cover from the embed`() = runBlocking {
        val cover = "https://i.scdn.co/image/ab67706f00000002b2fcce8f3fce2910355ee501"
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                embedHtml(trackJson("65DbTqJKhbwqYbZ1Okr0rc", "Choosin' Texas", "Ella Langley", 232226), coverArt = coverArtJson(cover)),
            ),
        )

        val preview = provider().fetchPlaylist("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M")

        assertEquals(cover, preview.artwork.coverUrl())
    }

    @Test
    fun `reads public album embed into an openable album detail`() = runBlocking {
        val cover = "https://i.scdn.co/image/album-cover"
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                albumHtml(
                    trackJson("65DbTqJKhbwqYbZ1Okr0rc", "Album song", "Artist", 232226),
                    name = "Public Album",
                    coverArt = coverArtJson(cover),
                ),
            ),
        )

        val album = provider().fetchAlbum(fm.rizx.player.data.remote.spotify.SpotifyIds.album("album123"))!!

        assertEquals("Public Album", album.title)
        assertEquals(cover, album.artwork.coverUrl())
        assertEquals("Album song", album.tracks.single().title)
        assertEquals(album.source, album.tracks.single().album?.source)
    }

    @Test
    fun `a playlist without cover art previews with no artwork rather than a blank url`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                embedHtml(trackJson("65DbTqJKhbwqYbZ1Okr0rc", "Choosin' Texas", "Ella Langley", 232226)),
            ),
        )

        val preview = provider().fetchPlaylist("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M")

        assertEquals(null, preview.artwork)
        // Rows carry no images on playlist embeds, so tracks stay artwork-less until the enricher runs.
        assertEquals(null, preview.tracks[0].artwork)
    }

    @Test
    fun `flags the embed track cap in the description`() = runBlocking {
        val many = (1..SpotifyPlaylistProvider.EMBED_TRACK_CAP)
            .joinToString(",") { trackJson("id%022d".format(it), "Song $it", "Artist", 1000) }
        server.enqueue(MockResponse().setResponseCode(200).setBody(embedHtml(many)))

        val preview = provider().fetchPlaylist("https://open.spotify.com/playlist/abc")

        assertEquals(SpotifyPlaylistProvider.EMBED_TRACK_CAP, preview.tracks.size)
        assertTrue(preview.description!!.contains("First ${SpotifyPlaylistProvider.EMBED_TRACK_CAP} tracks"))
    }

    /** A full embed page plus a pathfinder dispatcher that serves [totalCount] tracks in 100s. */
    private fun serveFullPlaylist(totalCount: Int, failPaging: Boolean = false) {
        val embed = embedHtml(
            (1..SpotifyPlaylistProvider.EMBED_TRACK_CAP)
                .joinToString(",") { trackJson("id%022d".format(it), "Song $it", "Artist", 1000) },
        )
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                if (path.startsWith("/embed/")) return MockResponse().setResponseCode(200).setBody(embed)
                if (!path.startsWith("/pathfinder/")) return MockResponse().setResponseCode(404)
                if (failPaging) return MockResponse().setResponseCode(500)
                val offset = Regex("""offset%22%3A(\d+)|"offset":(\d+)""").find(path)
                    ?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toInt() ?: 0
                val ids = (offset until minOf(offset + 100, totalCount)).toList()
                return MockResponse().setResponseCode(200).setBody(pathfinderPage(totalCount, ids))
            }
        }
    }

    @Test
    fun `pages past the embed cap so a long playlist imports whole`() = runBlocking {
        serveFullPlaylist(totalCount = 250)

        val preview = provider(pathfinder()).fetchPlaylist("https://open.spotify.com/playlist/abc")

        // 100 from the embed + everything pathfinder served past it.
        assertEquals(250, preview.tracks.size)
        // No truncation notice: the list really is complete now.
        assertFalse(preview.description!!.contains("First"))
    }

    @Test
    fun `paged rows keep their own artists, album and cover instead of the embed's blanks`() = runBlocking {
        serveFullPlaylist(totalCount = 150)

        val preview = provider(pathfinder()).fetchPlaylist("https://open.spotify.com/playlist/abc")
        val paged = preview.tracks[SpotifyPlaylistProvider.EMBED_TRACK_CAP]

        // Pathfinder ships artists as separate objects, so a comma inside a name survives — the embed's
        // comma-splitting would have made this two artists.
        assertEquals(listOf("Tyler, The Creator"), paged.artists.map { it.name })
        assertEquals("Album 100", paged.album?.title)
        assertEquals("https://i.scdn.co/image/cover100", paged.artwork.coverUrl())
    }

    @Test
    fun `a paging failure degrades to the embed's tracks rather than failing the import`() = runBlocking {
        serveFullPlaylist(totalCount = 250, failPaging = true)

        val preview = provider(pathfinder()).fetchPlaylist("https://open.spotify.com/playlist/abc")

        assertEquals(SpotifyPlaylistProvider.EMBED_TRACK_CAP, preview.tracks.size)
        // …and says so, so a short import never passes for a complete one.
        assertTrue(preview.description!!.contains("First ${SpotifyPlaylistProvider.EMBED_TRACK_CAP} tracks"))
    }

    @Test
    fun `a playlist shorter than the cap never spends a paging request`() = runBlocking {
        serveFullPlaylist(totalCount = 250)
        // Override the embed with a short one: the cap wasn't hit, so there is nothing to page.
        val short = embedHtml((1..3).joinToString(",") { trackJson("id%022d".format(it), "Song $it", "Artist", 1000) })
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse =
                if (request.path.orEmpty().startsWith("/embed/")) {
                    MockResponse().setResponseCode(200).setBody(short)
                } else {
                    MockResponse().setResponseCode(500) // any pathfinder hit here is a bug
                }
        }

        val preview = provider(pathfinder()).fetchPlaylist("https://open.spotify.com/playlist/abc")

        assertEquals(3, preview.tracks.size)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a page without the data blob fails as a typed provider error`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html><body>nothing here</body></html>"))

        val error = assertThrows(AppError::class.java) {
            runBlocking { provider().fetchPlaylist("https://open.spotify.com/playlist/abc") }
        }
        assertTrue(error is AppError.ProviderFailure)
    }

    @Test
    fun `an http error surfaces as a typed error`() {
        server.enqueue(MockResponse().setResponseCode(404))

        val error = assertThrows(AppError::class.java) {
            runBlocking { provider().fetchPlaylist("https://open.spotify.com/playlist/abc") }
        }
        assertTrue(error is AppError.Network || error is AppError.ProviderFailure)
    }
}
