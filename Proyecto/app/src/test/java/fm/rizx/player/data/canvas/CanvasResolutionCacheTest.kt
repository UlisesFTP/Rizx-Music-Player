package fm.rizx.player.data.canvas

import fm.rizx.player.data.remote.youtube.googlevideoExpiryMs
import fm.rizx.player.domain.model.CanvasCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How long an answer is worth keeping.
 *
 * The cache this replaced kept everything for the life of the process, which produced two bugs the user
 * could see: a song that missed once never got another chance, and a hit kept serving a URL long after
 * the token in it had expired — so the canvas silently stopped working a few hours into a session.
 */
class CanvasResolutionCacheTest {

    private var now = 1_000_000L
    private val cache = CanvasResolutionCache(now = { now })

    private fun candidate(expiresAtMs: Long? = null) = CanvasCandidate(
        providerId = "youtube",
        mediaUrl = "https://r1.googlevideo.com/videoplayback?expire=1",
        expiresAtMs = expiresAtMs,
    )

    @Test
    fun `a hit is remembered`() {
        cache.putHit("k", candidate())

        assertEquals("youtube", cache.get("k")?.candidate?.providerId)
    }

    @Test
    fun `nothing is remembered about a track never looked up`() {
        assertNull(cache.get("k"))
    }

    @Test
    fun `a hit expires after the positive TTL`() {
        cache.putHit("k", candidate())

        now += CanvasResolutionCache.POSITIVE_TTL_MS - 1
        assertNotNull(cache.get("k"))

        now += 2
        assertNull(cache.get("k"))
    }

    @Test
    fun `a hit dies with its URL, long before the TTL would have run out`() {
        // The bug this exists for: googlevideo signs a URL for ~6h, the cache kept it forever, and the
        // player then failed on a link the cache still believed in.
        val urlDiesIn = 30 * 60 * 1000L
        cache.putHit("k", candidate(expiresAtMs = now + urlDiesIn))

        now += urlDiesIn - CanvasResolutionCache.EXPIRY_MARGIN_MS - 1
        assertNotNull("still inside the safety margin", cache.get("k"))

        now += 2
        assertNull("the URL is about to expire; go back and get a fresh one", cache.get("k"))
    }

    @Test
    fun `an already-expired URL is not remembered at all`() {
        cache.putHit("k", candidate(expiresAtMs = now - 1))

        assertNull(cache.get("k"))
    }

    @Test
    fun `a miss is remembered, but only for twenty minutes`() {
        cache.putMiss("k")

        assertNotNull("still cached, so skipping back and forth costs nothing", cache.get("k"))
        assertNull("and it is a miss, not a hit", cache.get("k")?.candidate)

        now += CanvasResolutionCache.NEGATIVE_TTL_MS + 1
        assertNull("a song that missed once must get another chance in the same sitting", cache.get("k"))
    }

    @Test
    fun `an error is forgotten fastest of all`() {
        cache.putError("k")

        now += CanvasResolutionCache.ERROR_TTL_MS + 1
        assertNull(cache.get("k"))
        assertTrue(
            "whatever broke is usually over long before a real miss would be re-checked",
            CanvasResolutionCache.ERROR_TTL_MS < CanvasResolutionCache.NEGATIVE_TTL_MS,
        )
    }

    @Test
    fun `two tracks do not share an answer`() {
        cache.putHit("a", candidate())
        cache.putMiss("b")

        assertNotNull(cache.get("a")?.candidate)
        assertNull(cache.get("b")?.candidate)
    }

    // ---- the expiry the URL itself carries ----

    @Test
    fun `the googlevideo expire parameter is read as the real deadline`() {
        val url = "https://r5---sn-x.googlevideo.com/videoplayback?expire=1780000000&ei=abc&itag=18"

        assertEquals(1_780_000_000_000L, googlevideoExpiryMs(url))
    }

    @Test
    fun `a URL with no expiry, or a broken one, simply has none`() {
        assertNull(googlevideoExpiryMs("https://cdn.example.com/canvas.mp4"))
        assertNull(googlevideoExpiryMs("https://x.googlevideo.com/vp?expire=soon"))
        assertNull(googlevideoExpiryMs("https://x.googlevideo.com/vp?expire=0"))
    }
}
