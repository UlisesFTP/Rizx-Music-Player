package fm.rizx.player.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadFileNamingTest {

    @Test
    fun `a plain identity key becomes a readable file name`() {
        assertEquals("youtube_dQw4w9WgXcQ.m4a", downloadFileName("youtube:dQw4w9WgXcQ", "m4a"))
    }

    @Test
    fun `the same key always yields the same name so a re-download overwrites in place`() {
        assertEquals(downloadFileName("youtube:abc", "m4a"), downloadFileName("youtube:abc", "m4a"))
    }

    @Test
    fun `a key with filesystem-illegal characters falls back to a hash`() {
        // A JS plugin (ADR 0014) can mint ids with slashes or spaces, and getExternalFilesDir can land
        // on exFAT where those are illegal.
        val name = downloadFileName("plugin:a/b c?d", "m4a")

        assertTrue(name, name.matches(Regex("[0-9a-f]{32}\\.m4a")))
    }

    @Test
    fun `an absurdly long key falls back to a hash`() {
        val name = downloadFileName("plugin:" + "x".repeat(500), "webm")

        assertTrue(name, name.matches(Regex("[0-9a-f]{32}\\.webm")))
    }

    @Test
    fun `distinct keys never collide`() {
        assertEquals(2, setOf(downloadFileName("youtube:a", "m4a"), downloadFileName("audius:a", "m4a")).size)
    }

    @Test
    fun `the container wins because the provider knows its own format`() {
        assertEquals("m4a", extensionFor(container = "m4a", mimeType = "audio/mpeg"))
        assertEquals("webm", extensionFor(container = "webm", mimeType = null))
    }

    @Test
    fun `the mime type is used when no container is given`() {
        assertEquals("mp3", extensionFor(container = null, mimeType = "audio/mpeg"))
        assertEquals("m4a", extensionFor(container = null, mimeType = "audio/mp4; codecs=\"mp4a.40.2\""))
    }

    @Test
    fun `the response content type settles it for Audius, whose url redirects to a CDN`() {
        assertEquals("mp3", extensionFor(container = null, mimeType = null, contentType = "audio/mpeg"))
    }

    @Test
    fun `an unknown format is saved as bin rather than guessed`() {
        assertEquals("bin", extensionFor(container = "xyz", mimeType = "application/octet-stream"))
        assertEquals("bin", extensionFor(container = null, mimeType = null))
    }

    @Test
    fun `codec parameters are stripped because MediaStore rejects a non-bare mime`() {
        // NewPipe hands back exactly this shape.
        assertEquals("audio/mp4", bareMime("audio/mp4; codecs=\"mp4a.40.2\""))
        assertEquals("audio/mpeg", bareMime("AUDIO/MPEG"))
        assertNull(bareMime(null))
        assertNull(bareMime(""))
    }

    @Test
    fun `export names carry the metadata that survives, since the file itself is untagged`() {
        assertEquals("Maya Sol - Velvet Hours.m4a", exportFileName("Maya Sol", "Velvet Hours", "m4a", "x"))
    }

    @Test
    fun `export names drop characters that are illegal in a file name`() {
        assertEquals("ACDC - TNT.mp3", exportFileName("AC/DC", "TNT", "mp3", "x"))
        assertEquals("Artist - What's Up.m4a", exportFileName("Artist", "What's Up?", "m4a", "x"))
    }

    @Test
    fun `an export name with no artist is just the title`() {
        assertEquals("Velvet Hours.m4a", exportFileName(null, "Velvet Hours", "m4a", "x"))
        assertEquals("Velvet Hours.m4a", exportFileName("  ", "Velvet Hours", "m4a", "x"))
    }

    @Test
    fun `an export name that sanitises to nothing uses the fallback`() {
        assertEquals("deezer_123.m4a", exportFileName("//", "??", "m4a", "deezer_123"))
    }

    @Test
    fun `sizes read the way a person would say them`() {
        assertEquals("4.2 MB", formatBytes(4_200_000))
        assertEquals("512 KB", formatBytes(512_000))
        assertEquals("1.5 GB", formatBytes(1_500_000_000))
        assertEquals("0 B", formatBytes(0))
    }
}
