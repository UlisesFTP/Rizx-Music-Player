package fm.rizx.player.data.remote.applemusic

import fm.rizx.player.domain.model.coverUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Discovery of Apple's "Top 100" country charts from the browse page.
 *
 * The fixture is trimmed from the real `music.apple.com/us/browse` markup — the lockup's shape (a
 * srcset of `NNNxNNNcc` crops, then `alt="…"`, then the link) is what the parser walks backwards over.
 * Ids are hard-coded values verified live; they are stable across storefronts.
 */
class AppleMusicBrowsePageTest {

    private val page = AppleMusicBrowsePage(OkHttpClient())

    private fun lockup(title: String, slug: String, id: String) = """
        <li><picture>
        <source srcset="https://is1-ssl.mzstatic.com/image/thumb/Features116/v4/c6/05/b6/cover.png/632x632cc-60.jpg 632w" type="image/jpeg">
        <img alt="$title" class="artwork-component__image" loading="lazy" width="316" height="316"></picture>
        <div class="product-lockup__controls"><a class="product-lockup__link"
        aria-label="$title, Apple Music"
        href="https://music.apple.com/us/playlist/$slug/$id"></a></div></li>
    """.trimIndent()

    private val browse = """
        <html><body><section>
        ${lockup("Top 100: Mexico", "top-100-mexico", "pl.df3f10ca27b1479087de2cd3f9f6716b")}
        ${lockup("Top 100: Global", "top-100-global", "pl.d25f5d1181894928af76c85c967f8f31")}
        ${lockup("Top 100: South Korea", "top-100-south-korea", "pl.d3d10c32fbc540b38e266367dc8cb00c")}
        ${lockup("Rap Life", "rap-life", "pl.abe8ba42278f4ef490e3a9fc5ec8e8c5")}
        </section></body></html>
    """.trimIndent()

    @Test
    fun `finds every Top 100 chart and ignores other playlists`() {
        val refs = page.parse(browse, "us")

        assertEquals(3, refs.size) // "Rap Life" is not a Top 100 and comes from the RSS instead
        assertTrue(refs.all { it.source.provider == AppleMusicIds.PROVIDER })
    }

    @Test
    fun `Global is listed first — it is the chart anyone can open`() {
        assertEquals("Top 100: Global", page.parse(browse, "us").first().name)
    }

    @Test
    fun `titles come from Apple's own markup, already localised`() {
        val names = page.parse(browse, "us").map { it.name }

        assertTrue("Top 100: South Korea" in names)
        assertTrue("Top 100: Mexico" in names)
    }

    @Test
    fun `each chart carries a square cover, upsized from the lockup thumbnail`() {
        val mexico = page.parse(browse, "us").first { it.name.contains("Mexico") }

        val cover = mexico.artwork.coverUrl()
        assertNotNull(cover)
        assertTrue(cover!!.contains("632x632cc"))
    }

    @Test
    fun `the ref keeps a public URL, which is what makes the card openable`() {
        val global = page.parse(browse, "us").first()

        assertEquals(
            "https://music.apple.com/us/playlist/top-100-global/pl.d25f5d1181894928af76c85c967f8f31",
            global.source.url,
        )
        assertEquals("playlist:pl.d25f5d1181894928af76c85c967f8f31", global.source.id)
    }

    @Test
    fun `the storefront in the URL follows the one asked for`() {
        assertTrue(page.parse(browse, "mx").first().source.url!!.contains("/mx/"))
    }

    @Test
    fun `a duplicate link is only listed once`() {
        val twice = browse + lockup("Top 100: Global", "top-100-global", "pl.d25f5d1181894928af76c85c967f8f31")

        assertEquals(3, page.parse(twice, "us").size)
    }

    @Test
    fun `charts serialised as JSON are titled too — two thirds of the page is that shape`() {
        // Verified live: only ~7 of 20 charts render as HTML lockups; the rest sit in a JSON blob as
        // "title":"…". Reading only alt= silently gave those slug-derived names.
        val json = """
            {"titleLinks":[{"title":"Top 100: Spain","segue":{"destination":{"contentDescriptor":
            {"url":"https://music.apple.com/us/playlist/top-100-spain/pl.0d656d7feae64198bc5fb1b02786ed75"}}}}]}
        """.trimIndent()

        val ref = page.parse(json, "us").single()

        assertEquals("Top 100: Spain", ref.name)
        assertNull(ref.artwork.coverUrl()) // the JSON entries carry no cover; the UI draws those
    }

    @Test
    fun `a lockup with no title falls back to the slug, and one with no cover still lists`() {
        val bare = """<a href="https://music.apple.com/us/playlist/top-100-south-korea/pl.abc123"></a>"""

        val ref = page.parse(bare, "us").single()

        assertEquals("Top 100: South Korea", ref.name)
        assertNull(ref.artwork.coverUrl())
    }

    @Test
    fun `an unreachable or unrecognisable page yields nothing rather than throwing`() {
        assertTrue(page.parse("", "us").isEmpty())
        assertTrue(page.parse("<html>nothing here</html>", "us").isEmpty())
    }

    @Test
    fun `slug names read correctly, acronyms included`() {
        assertEquals("Top 100: USA", page.displayName("top-100-usa"))
        assertEquals("Top 100: UK", page.displayName("top-100-uk"))
        assertEquals("Top 100: Antigua and Barbuda", page.displayName("top-100-antigua-and-barbuda"))
        assertEquals("Top 100", page.displayName("top-100"))
    }
}
