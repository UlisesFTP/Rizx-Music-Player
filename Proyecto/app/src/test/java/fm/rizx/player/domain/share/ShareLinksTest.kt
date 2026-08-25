package fm.rizx.player.domain.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareLinksTest {

    private val base = "https://example.supabase.co/functions/v1/playlist-shares"
    private val token = "iS_UBbQ-InTyjHsGRcBwCk7s1tWiK_qJSv8np62mxLY"

    @Test
    fun `reads the token out of the https spelling`() {
        assertEquals(token, ShareLinks.tokenFrom("$base/$token", base))
        // Config with a trailing slash, or a link with different case in the host, still match.
        assertEquals(token, ShareLinks.tokenFrom("$base/$token", "$base/"))
        assertEquals(token, ShareLinks.tokenFrom("HTTPS://EXAMPLE.supabase.co/functions/v1/playlist-shares/$token", base))
    }

    @Test
    fun `reads the token out of the app scheme`() {
        assertEquals(token, ShareLinks.tokenFrom("rizx://share/$token", base))
        assertEquals(token, ShareLinks.tokenFrom("RIZX://SHARE/$token", base))
        // The app scheme needs no share backend to be recognised.
        assertEquals(token, ShareLinks.tokenFrom("rizx://share/$token", ""))
    }

    @Test
    fun `refuses anything that is not a share link`() {
        assertNull("other host", ShareLinks.tokenFrom("https://evil.example/functions/v1/playlist-shares/$token", base))
        assertNull("other path on the share host", ShareLinks.tokenFrom("https://example.supabase.co/functions/v1/sync/$token", base))
        assertNull("not a token", ShareLinks.tokenFrom("$base/list", base))
        assertNull("path traversal is not a token", ShareLinks.tokenFrom("$base/../auth/v1/user", base))
        assertNull("a query string is not part of a token", ShareLinks.tokenFrom("$base/$token?x=1", base))
        assertNull("other app host", ShareLinks.tokenFrom("rizx://settings/$token", base))
        assertNull("https spelling is unknown without a backend", ShareLinks.tokenFrom("$base/$token", ""))
        assertNull(ShareLinks.tokenFrom("", base))
    }

    @Test
    fun `token shape matches the server's rule`() {
        assertTrue(ShareLinks.isToken(token))
        assertFalse("42 chars", ShareLinks.isToken(token.dropLast(1)))
        assertFalse("44 chars", ShareLinks.isToken(token + "a"))
        assertFalse("padding", ShareLinks.isToken(token.dropLast(1) + "="))
        assertFalse("standard base64 alphabet", ShareLinks.isToken(token.replace('-', '+')))
    }

    @Test
    fun `rebuilds both spellings`() {
        assertEquals("$base/$token", ShareLinks.shareUrl("$base/", token))
        assertEquals("rizx://share/$token", ShareLinks.appUri(token))
    }
}
