package fm.rizx.player.data.canvas

import fm.rizx.player.data.remote.itunes.ItunesApi
import fm.rizx.player.data.remote.itunes.ItunesResultDto
import fm.rizx.player.data.remote.itunes.ItunesSearchResponse
import fm.rizx.player.domain.model.AlbumRef
import fm.rizx.player.domain.model.ArtistCredit
import fm.rizx.player.domain.model.CanvasAspect
import fm.rizx.player.domain.model.CanvasQuality
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Apple's motion album artwork — the provider that makes covers actually move.
 *
 * The album page is server-rendered JSON whose shape Apple changes freely, so the parser is a narrow
 * regex and everything here is about it degrading to "no canvas" rather than throwing. The fixture is
 * trimmed from the real page for *After Hours*, escaping and all.
 */
class AppleMotionArtworkProviderTest {

    private lateinit var server: MockWebServer
    private val client = OkHttpClient()

    @Before fun start() { server = MockWebServer(); server.start() }
    @After fun stop() { server.shutdown() }

    private class FakeItunes(private val rows: List<ItunesResultDto>) : ItunesApi {
        var terms = mutableListOf<String>()
        override suspend fun search(
            term: String,
            media: String,
            entity: String,
            limit: Int,
            attribute: String?,
            country: String?,
        ) = ItunesSearchResponse(rows.size, rows).also { terms += term }

        override suspend fun lookup(id: String, entity: String, limit: Int, country: String?) =
            ItunesSearchResponse()
    }

    private fun row(
        title: String = "Blinding Lights",
        artist: String = "The Weeknd",
        durationMs: Long = 200_040,
        collectionId: Long? = 1499385848,
        collectionName: String = "After Hours",
    ) = ItunesResultDto(
        collectionId = collectionId,
        trackName = title,
        artistName = artist,
        collectionName = collectionName,
        trackTimeMillis = durationMs,
    )

    private fun track(title: String = "Blinding Lights", artist: String = "The Weeknd") = Track(
        title = title,
        artists = listOf(ArtistCredit(artist)),
        durationMs = 200_040,
        source = ProviderRef("deezer", "1"),
    )

    /** A track that already carries its Apple album, the way `ItunesMappers` stamps it. */
    private fun ownedTrack(collectionId: Long?) = track().copy(
        album = AlbumRef(
            title = "After Hours",
            source = ProviderRef("itunes", "album:" + (collectionId?.toString() ?: "After Hours")),
        ),
    )

    /** The two keys as they really appear, with Apple's `/` escaping and the previewFrame between. */
    private fun albumPage(square: Boolean = true, tall: Boolean = true): String = buildString {
        append("""<html><body><script type="application/json">{"albumName":"After Hours",""")
        if (square) {
            append(""""squareVideoArtwork":{"dictionary":{"motionDetailSquare":{"previewFrame":""")
            append("""{"bgColor":"131318","height":3840,"url":"https://is1-ssl.mzstatic.com/a.png"},""")
            append(""""video":"https://mvod.itunes.apple.com/sq/P359476169_default.m3u8"}}},""")
        }
        if (tall) {
            append(""""tallVideoArtwork":{"dictionary":{"motionDetailTall":{"previewFrame":""")
            append("""{"bgColor":"191a1a","height":2732,"url":"https://is1-ssl.mzstatic.com/b.png"},""")
            append(""""video":"https://mvod.itunes.apple.com/tall/P359475901_default.m3u8"}}},""")
        }
        append(""""trackCount":14}</script></body></html>""")
    }

    @Test
    fun `the square cut is HLS, silent, and marked square`() {
        server.enqueue(MockResponse().setBody(albumPage()))

        val c = resolveAgainstServer(CanvasAspect.SQUARE).firstOrNull()

        assertTrue("expected an HLS manifest, got ${c?.mediaUrl}", c?.mediaUrl?.endsWith(".m3u8") == true)
        assertTrue(c!!.mediaUrl.contains("/sq/"))
        assertEquals(CanvasAspect.SQUARE, c.aspect)
        assertEquals("application/x-mpegURL", c.mimeType)
    }

    @Test
    fun `asking for portrait gets the tall cut first, with square behind it`() {
        server.enqueue(MockResponse().setBody(albumPage()))

        val cs = resolveAgainstServer(CanvasAspect.PORTRAIT)

        assertEquals(2, cs.size)
        assertEquals(CanvasAspect.PORTRAIT, cs[0].aspect)
        assertTrue(cs[0].mediaUrl.contains("/tall/"))
        assertTrue("the other cut is the runner-up, not a discard", cs[1].mediaUrl.contains("/sq/"))
    }

    @Test
    fun `an album with only one cut still works`() {
        server.enqueue(MockResponse().setBody(albumPage(tall = false)))

        val cs = resolveAgainstServer(CanvasAspect.PORTRAIT)

        assertEquals("falls back to the cut that exists", CanvasAspect.SQUARE, cs.single().aspect)
    }

    @Test
    fun `an album with no motion artwork simply has no canvas`() {
        // Most of the back catalogue. Must be a quiet miss, not an error.
        server.enqueue(MockResponse().setBody("""<html><body>{"albumName":"Pet Sounds"}</body></html>"""))

        assertTrue(resolveAgainstServer(CanvasAspect.SQUARE).isEmpty())
    }

    @Test
    fun `a page Apple has restructured is a miss, not a crash`() {
        server.enqueue(MockResponse().setBody("""{"motionDetailSquare":{"previewFrame":{}}}"""))

        assertTrue(resolveAgainstServer(CanvasAspect.SQUARE).isEmpty())
    }

    @Test
    fun `no matching song means the album page is never even fetched`() {
        val rows = listOf(row(title = "Save Your Tears"), row(title = "Blinding Lights (Remix)"))

        assertTrue(resolveAgainstServer(CanvasAspect.SQUARE, rows = rows).isEmpty())
        assertEquals("a rejected match must not cost a second round trip", 0, server.requestCount)
    }

    @Test
    fun `a track that already knows its Apple album skips the search entirely`() {
        // ADR 0020, owner-first: the id is already in hand, so searching could only lose it.
        server.enqueue(MockResponse().setBody(albumPage()))
        val itunes = FakeItunes(listOf(row()))

        val cs = runBlocking {
            val base = server.url("/").toString().removeSuffix("/")
            AppleMotionArtworkProvider(
                itunes = itunes,
                client = client,
                storefront = { "us" },
                io = Dispatchers.Unconfined,
                albumUrl = { _, id -> "$base/album/$id" },
            ).resolve(ownedTrack(1499378108), CanvasAspect.SQUARE, CanvasQuality.DATA_SAVER)
        }

        assertTrue(cs.first().mediaUrl.endsWith(".m3u8"))
        assertTrue("no search should have happened, got ${itunes.terms}", itunes.terms.isEmpty())
        assertTrue("the album it named, not another", server.takeRequest().path!!.contains("1499378108"))
    }

    @Test
    fun `an album ref with no numeric id falls back to the search`() {
        // ItunesMappers stores `album:<name>` when the row carried no collectionId.
        server.enqueue(MockResponse().setBody(albumPage()))
        val itunes = FakeItunes(listOf(row()))

        runBlocking {
            val base = server.url("/").toString().removeSuffix("/")
            AppleMotionArtworkProvider(itunes, client, { "us" }, Dispatchers.Unconfined) { _, id -> "$base/album/$id" }
                .resolve(ownedTrack(null), CanvasAspect.SQUARE, CanvasQuality.DATA_SAVER)
        }

        assertEquals("it had to search after all", 1, itunes.terms.size)
    }

    @Test
    fun `the single is skipped for the album that actually has the artwork`() {
        // The bug that made every cover static. iTunes ranks by *track*, so its best hit for
        // "Blinding Lights" is the single — a perfect title/artist/duration match with **no** motion
        // artwork — while "After Hours" carries both cuts. Verified live: 1488408555 has none,
        // 1499378108 has square + tall. Stopping at the top row lost most of the catalogue.
        val rows = listOf(
            row(collectionId = 1488408555, collectionName = "Blinding Lights - Single"),
            row(collectionId = 1499378108, collectionName = "After Hours"),
        )
        server.enqueue(MockResponse().setBody(albumPage()))     // the full album is tried FIRST

        val cs = resolveAgainstServer(CanvasAspect.SQUARE, rows = rows)

        assertTrue(cs.first().mediaUrl.endsWith(".m3u8"))
        assertTrue("the album, not the single", server.takeRequest().path!!.contains("1499378108"))
    }

    @Test
    fun `when the preferred album has nothing, the next one is tried`() {
        val rows = listOf(
            row(collectionId = 111, collectionName = "After Hours"),
            row(collectionId = 222, collectionName = "After Hours (Deluxe)"),
        )
        server.enqueue(MockResponse().setBody("""{"albumName":"no motion here"}"""))
        server.enqueue(MockResponse().setBody(albumPage()))

        assertTrue(resolveAgainstServer(CanvasAspect.SQUARE, rows = rows).first().mediaUrl.endsWith(".m3u8"))
        assertEquals("both albums should have been asked", 2, server.requestCount)
    }

    @Test
    fun `a track with no motion artwork anywhere stops after three albums`() {
        val rows = (1..6).map { row(collectionId = it.toLong(), collectionName = "Album $it") }
        repeat(6) { server.enqueue(MockResponse().setBody("""{"albumName":"none"}""")) }

        assertTrue(resolveAgainstServer(CanvasAspect.SQUARE, rows = rows).isEmpty())
        assertEquals("a miss must not turn into six round trips", 3, server.requestCount)
    }

    @Test
    fun `a row with no album id is skipped`() {
        assertTrue(resolveAgainstServer(CanvasAspect.SQUARE, rows = listOf(row(collectionId = null))).isEmpty())
    }

    @Test
    fun `the artist is de-channelled before searching`() {
        // "TheCranberriesTV" is a YouTube channel credit; iTunes has never heard of it.
        val itunes = FakeItunes(emptyList())
        val provider = AppleMotionArtworkProvider(itunes, client, { "us" }, Dispatchers.Unconfined)

        runBlocking {
            provider.resolve(track(artist = "TheCranberriesTV", title = "Zombie"), CanvasAspect.SQUARE, CanvasQuality.DATA_SAVER)
        }

        assertTrue("searched for ${itunes.terms}", itunes.terms.single().lowercase().contains("cranberries"))
        assertTrue("the channel suffix should be gone", !itunes.terms.single().contains("TV"))
    }

    /**
     * Runs the provider with its album URL pointed at [server].
     *
     * The provider composes `music.apple.com/{storefront}/album/{id}`; the storefront lambda is the seam
     * the test uses to redirect that at the mock server without a network call leaving the machine.
     */
    private fun resolveAgainstServer(
        aspect: CanvasAspect,
        rows: List<ItunesResultDto> = listOf(row()),
    ) = runBlocking {
        val base = server.url("/").toString().removeSuffix("/")
        val provider = AppleMotionArtworkProvider(
            itunes = FakeItunes(rows),
            client = client,
            storefront = { "us" },
            io = Dispatchers.Unconfined,
            albumUrl = { _, id -> "$base/album/$id" },
        )
        provider.resolve(track(), aspect, CanvasQuality.DATA_SAVER)
    }
}
