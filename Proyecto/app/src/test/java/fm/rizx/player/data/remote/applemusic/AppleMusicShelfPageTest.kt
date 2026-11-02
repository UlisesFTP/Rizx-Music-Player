package fm.rizx.player.data.remote.applemusic

import fm.rizx.player.domain.model.coverUrl
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The artist/curator playlist shelves. Fixtures are trimmed from the real `serialized-server-data` of
 * `music.apple.com/us/artist/bad-bunny/1126808565` and `…/curator/apple-music-pop/976439548`
 * (fetched 2026-11-02): same nesting, same keys, fewer items.
 */
class AppleMusicShelfPageTest {

    private val page = AppleMusicShelfPage(OkHttpClient(), Json { ignoreUnknownKeys = true })

    private fun html(json: String) =
        """<html><head></head><body><script type="application/json" id="serialized-server-data">$json</script></body></html>"""

    private fun artistItem(id: String, slug: String, title: String, artVariant: String) = """
        {"id": "$id", "titleLinks": [{"title": "$title"}], "subtitleLinks": [{"title": "Apple Music Urbano Latino"}],
         "contentDescriptor": {"kind": "playlist", "identifiers": {"storeAdamID": "$id"}, "url": "https://music.apple.com/us/playlist/$slug/$id"},
         "artwork": {"dictionary": {"url": "https://is1-ssl.mzstatic.com/image/thumb/Features211/v4/6c/92/d4/x/mza_1.png/{w}x{h}SC.$artVariant.{f}?l=en-US"}},
         "playAction": {"items": [{"contentDescriptor": {"kind": "playlist", "identifiers": {"storeAdamID": "$id"}, "url": "https://music.apple.com/us/playlist/$slug/$id"}}]}}
    """.trimIndent()

    private val artistPage = html(
        """
        {"data": [{"data": {"sections": [{"id": "artists_1126808565_default.marker.ArtistPlaylists-SquareLockupSection", "items": [
          ${artistItem("pl.1c35ac10cfe848aaa19f68ebe62ea46e", "bad-bunny-essentials", "Bad Bunny Essentials", "FPESS05")},
          ${artistItem("pl.0d2dc1dbc304435f9536caa2aff3295c", "bad-bunny-video-essentials", "Bad Bunny Video Essentials", "MVESS05")},
          ${artistItem("pl.74d26ec8e06340be83fde7d5c5eac665", "bad-bunny-deep-cuts", "Bad Bunny: Deep Cuts", "FPDC04")}
        ]}]}}]}
        """.trimIndent(),
    )

    private val curatorPage = html(
        """
        {"data": [{"data": {"sections": [
          {"id": "headerComponentItemSection - 976439548", "items": [{"id": "apple-curator-header - 976439548", "title": "Pop",
            "contentDescriptor": {"kind": "appleCurator", "identifiers": {"storeAdamID": "976439548"}, "url": "https://music.apple.com/us/curator/apple-music-pop/976439548"}}]},
          {"id": "hero-list-shelf - 6828704112", "items": [
            {"id": "hero-list-shelf - 6828704112 - 6639599952", "heading": "UPDATED PLAYLIST", "title": "A-List Pop", "subtitle": "Apple Music Pop",
             "contentDescriptor": {"kind": "playlist", "identifiers": {"storeAdamID": "pl.5ee8333dbe944d9f9151e97d92d1ead9"}, "url": "https://music.apple.com/us/playlist/a-list-pop/pl.5ee8333dbe944d9f9151e97d92d1ead9"},
             "artwork": {"dictionary": {"url": "https://is1-ssl.mzstatic.com/image/thumb/Features/v4/a/b/c/x.jpg/{w}x{h}{c}.{f}"}}},
            {"id": "hero-list-shelf - 6828704112 - 2", "title": "Dark Side of Pop: The Countdown",
             "contentDescriptor": {"kind": "playlist", "identifiers": {"storeAdamID": "pl.6f668aa569e64334b697f98e9beeb69b"}, "url": "https://music.apple.com/us/playlist/dark-side-of-pop-the-countdown/pl.6f668aa569e64334b697f98e9beeb69b"}}
          ]}
        ]}}]}
        """.trimIndent(),
    )

    @Test
    fun `reads each card's own title, in page order, without the video playlist`() {
        val refs = page.parse(artistPage, "us")

        assertEquals(listOf("Bad Bunny Essentials", "Bad Bunny: Deep Cuts"), refs.map { it.name })
        assertEquals(listOf("pl.1c35ac10cfe848aaa19f68ebe62ea46e", "pl.74d26ec8e06340be83fde7d5c5eac665"), refs.map { it.id })
    }

    @Test
    fun `refs open through the Apple playlist provider, in the requested storefront`() {
        val ref = page.parse(artistPage, "mx").first()

        assertEquals(AppleMusicIds.PROVIDER, ref.source.provider)
        assertEquals("playlist:pl.1c35ac10cfe848aaa19f68ebe62ea46e", ref.source.id)
        assertEquals("https://music.apple.com/mx/playlist/bad-bunny-essentials/pl.1c35ac10cfe848aaa19f68ebe62ea46e", ref.source.url)
    }

    @Test
    fun `the cover template is sized, both template shapes`() {
        val artist = page.parse(artistPage, "us").first().artwork?.coverUrl()
        val curator = page.parse(curatorPage, "us").first().artwork?.coverUrl()

        assertEquals("https://is1-ssl.mzstatic.com/image/thumb/Features211/v4/6c/92/d4/x/mza_1.png/600x600SC.FPESS05.jpg?l=en-US", artist)
        assertEquals("https://is1-ssl.mzstatic.com/image/thumb/Features/v4/a/b/c/x.jpg/600x600cc.jpg", curator)
    }

    @Test
    fun `curator shelves skip the header and keep cards without a cover`() {
        val refs = page.parse(curatorPage, "us")

        assertEquals(listOf("A-List Pop", "Dark Side of Pop: The Countdown"), refs.map { it.name })
        assertEquals(null, refs[1].artwork)
    }

    @Test
    fun `a page without the data blob, or with broken JSON, is simply empty`() {
        assertTrue(page.parse("<html><body>nothing</body></html>", "us").isEmpty())
        assertTrue(page.parse(html("{not json"), "us").isEmpty())
        assertTrue(page.parse("", "us").isEmpty())
    }
}
