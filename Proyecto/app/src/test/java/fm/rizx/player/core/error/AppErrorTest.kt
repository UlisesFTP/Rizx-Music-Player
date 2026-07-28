package fm.rizx.player.core.error

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AppErrorTest {

    @Test
    fun `known AppError kinds map to a fixed safe sentence, ignoring their embedded detail`() {
        val network = AppError.Network("host unreachable: api.deezer.com:443")
        val unavailable = AppError.ProviderUnavailable("metadata")
        val failure = AppError.ProviderFailure("Deezer", "HTTP 429 too many requests")

        assertEquals("You're offline. Connect and try again.", network.toSafeMessage("fallback"))
        assertEquals("No source is available for this right now.", unavailable.toSafeMessage("fallback"))
        assertEquals("Couldn't reach Deezer. Try again in a moment.", failure.toSafeMessage("fallback"))
    }

    @Test
    fun `an untyped exception never leaks its raw message — it always gets the caller's fallback`() {
        val boom = RuntimeException("java.io.IOException: Unable to resolve host \"api.example.com\"")

        val safe = boom.toSafeMessage("Something went wrong")

        assertEquals("Something went wrong", safe)
        assertFalse(safe.contains("api.example.com"))
        assertFalse(safe.contains("IOException"))
    }

    @Test
    fun `a ProviderFailure's safe message never contains the raw detail`() {
        val failure = AppError.ProviderFailure("PluginRegistry", "unsafe zip entry ../../etc/passwd")

        val safe = failure.toSafeMessage("fallback")

        assertFalse(safe.contains("zip entry"))
        assertFalse(safe.contains("passwd"))
    }
}
