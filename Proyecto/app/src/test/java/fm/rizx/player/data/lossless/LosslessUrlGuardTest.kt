package fm.rizx.player.data.lossless

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * The rules that decide where a third party's index is allowed to send this app.
 *
 * A row in the index is, in effect, an instruction to make an HTTP request — so these are the two
 * things that must not be reachable through one: the device's own network, and a credential written
 * into a URL.
 */
class LosslessUrlGuardTest {

    private val guard = LosslessUrlGuard.Strict

    @Test
    fun `allows an ordinary https url`() {
        assertTrue(guard.isAllowed("https://raw.githubusercontent.com/x/y/Music/song.flac"))
    }

    @Test
    fun `refuses plain http`() {
        // Not tidiness: the whole point is to trust the bytes that come back, and over http anything
        // in between decides what those are.
        assertFalse(guard.isAllowed("http://example.com/song.flac"))
    }

    @Test
    fun `refuses schemes that are not http at all`() {
        assertFalse(guard.isAllowed("file:///data/data/fm.rizx.player/databases/rizx.db"))
        assertFalse(guard.isAllowed("content://media/external/audio/media/1"))
        assertFalse(guard.isAllowed("javascript:alert(1)"))
        assertFalse(guard.isAllowed("ftp://example.com/song.flac"))
    }

    @Test
    fun `refuses credentials embedded in the url`() {
        // These end up in logs, caches and screenshots of bug reports.
        assertFalse(guard.isAllowed("https://user:secret@example.com/song.flac"))
    }

    @Test
    fun `refuses localhost by name and by literal`() {
        assertFalse(guard.isAllowed("https://localhost/song.flac"))
        assertFalse(guard.isAllowed("https://127.0.0.1/song.flac"))
        assertFalse(guard.isAllowed("https://[::1]/song.flac"))
    }

    @Test
    fun `refuses private and link-local literals`() {
        assertFalse(guard.isAllowed("https://10.0.0.5/song.flac"))
        assertFalse(guard.isAllowed("https://172.16.4.4/song.flac"))
        assertFalse(guard.isAllowed("https://192.168.1.1/song.flac"))
        assertFalse(guard.isAllowed("https://169.254.1.1/song.flac"))
    }

    @Test
    fun `refuses the cloud metadata address`() {
        // Link-local covers it, but it is the single most valuable target on the list, so it gets
        // its own line: a test that keeps passing for the wrong reason is worth catching.
        assertFalse(guard.isAllowed("https://169.254.169.254/latest/meta-data/"))
    }

    @Test
    fun `refuses local-network hostname suffixes`() {
        assertFalse(guard.isAllowed("https://nas.local/song.flac"))
        assertFalse(guard.isAllowed("https://router.home.arpa/song.flac"))
        assertFalse(guard.isAllowed("https://api.internal/song.flac"))
    }

    @Test
    fun `refuses a trailing-dot spelling of localhost`() {
        assertFalse(guard.isAllowed("https://localhost./song.flac"))
    }

    @Test
    fun `treats a resolved private address as private`() {
        // The check a pure string test structurally cannot do: a public *name* that resolves to
        // 127.0.0.1. This is what the DNS hook inside the inspector consults.
        assertTrue(guard.isPrivateAddress(InetAddress.getByName("127.0.0.1")))
        assertTrue(guard.isPrivateAddress(InetAddress.getByName("192.168.0.10")))
        assertTrue(guard.isPrivateAddress(InetAddress.getByName("169.254.169.254")))
        assertTrue(guard.isPrivateAddress(InetAddress.getByName("fd00::1"))) // IPv6 unique-local
        assertFalse(guard.isPrivateAddress(InetAddress.getByName("93.184.216.34")))
    }

    @Test
    fun `the test seam opens loopback and nothing else`() {
        val relaxed = LosslessUrlGuard(allowLoopbackOverHttp = true)

        assertTrue(relaxed.isAllowed("http://127.0.0.1:8080/song.flac"))
        assertFalse("the LAN must stay closed", relaxed.isAllowed("http://192.168.1.1/song.flac"))
        assertFalse("credentials stay refused in every mode", relaxed.isAllowed("http://u:p@127.0.0.1/x.flac"))
    }
}
