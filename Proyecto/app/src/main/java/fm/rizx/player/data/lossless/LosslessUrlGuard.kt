package fm.rizx.player.data.lossless

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.Inet6Address
import java.net.InetAddress

/**
 * What a community index is allowed to point at.
 *
 * The URLs here come from a file somebody else wrote, and they are handed straight to an HTTP client
 * running inside the app — so an index row is an instruction to make a request, and the two things that
 * makes possible are worth closing: reaching **inside the device's own network** (a router's admin page,
 * a service on `localhost`, the cloud metadata endpoint at 169.254.169.254), and leaking a credential by
 * putting one in the URL.
 *
 * Every redirect is re-checked against this, because a public host is free to 302 anywhere.
 *
 * **Split in two on purpose.** [isAllowed] is pure and reads only what is written in the URL, so it is a
 * unit test with no network. Names that *resolve* somewhere private — a hostname pointing at 127.0.0.1 —
 * can only be caught once resolved, which is [isPrivateAddress]'s job from inside the HTTP client's DNS.
 */
class LosslessUrlGuard(
    /**
     * Lets plain-HTTP loopback through. **Only** set by tests, which serve their fixtures from a
     * `MockWebServer` on `127.0.0.1` — exactly the shape production must refuse. Same seam as
     * `QueueStreamResolver`'s null audio cache: the rule stays real, the test can still reach its server.
     */
    private val allowLoopbackOverHttp: Boolean = false,
) {

    /**
     * Whether [url] is a plain HTTPS URL to a host that isn't obviously on this machine or this LAN.
     *
     * HTTPS only, and that is not merely tidiness: the whole point of the header read is to trust what
     * came back, and over plain HTTP anything between here and the server can decide what that is.
     */
    fun isAllowed(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        // Credentials in a URL get logged, cached and copied into bug reports. Refused in every mode.
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return false
        if (allowLoopbackOverHttp && isLoopbackHost(parsed.host)) return true
        if (parsed.scheme != "https") return false
        return isAllowedHost(parsed)
    }

    private fun isLoopbackHost(host: String): Boolean =
        host == "localhost" || asLiteralAddress(host.lowercase())?.isLoopbackAddress == true

    private fun isAllowedHost(parsed: HttpUrl): Boolean {
        val host = parsed.host.lowercase().trimEnd('.')
        if (host.isEmpty()) return false
        if (host == "localhost" || BLOCKED_HOST_SUFFIXES.any { host.endsWith(it) }) return false
        // Only literals are resolved here — `getByName` on a *name* would do a DNS lookup, which would
        // put a network call inside a pure check (and inside every unit test of it).
        val literal = asLiteralAddress(host) ?: return true
        return !isPrivateAddress(literal)
    }

    /**
     * Whether [address] is somewhere a downloaded index has no business sending us.
     *
     * Called from the HTTP client's DNS hook as well as from [isAllowed], which is what makes a hostname
     * that resolves to `127.0.0.1` fail — the check the pure string test structurally cannot do.
     */
    fun isPrivateAddress(address: InetAddress): Boolean {
        if (allowLoopbackOverHttp && address.isLoopbackAddress) return false
        if (address.isLoopbackAddress) return true
        if (address.isAnyLocalAddress) return true
        if (address.isLinkLocalAddress) return true // covers 169.254.0.0/16, so cloud metadata too
        if (address.isSiteLocalAddress) return true // 10/8, 172.16/12, 192.168/16
        if (address.isMulticastAddress) return true
        if (address is Inet6Address) {
            val first = address.address.firstOrNull()?.toInt()?.and(0xFF) ?: return false
            if (first and IPV6_UNIQUE_LOCAL_MASK == IPV6_UNIQUE_LOCAL_PREFIX) return true
        }
        return false
    }

    /** The host as an [InetAddress] when it is written as a literal, else null (it's a name). */
    private fun asLiteralAddress(host: String): InetAddress? {
        val looksLiteral = host.contains(':') || host.all { it.isDigit() || it == '.' }
        if (!looksLiteral) return null
        val bare = host.removeSurrounding("[", "]")
        return runCatching { InetAddress.getByName(bare) }.getOrNull()
    }

    companion object {
        /** The production rules. Everything outside tests uses this one. */
        val Strict = LosslessUrlGuard()

        /** IPv6 unique-local, `fc00::/7`. Java has no predicate — `isSiteLocalAddress` is the old `fec0::/10`. */
        private const val IPV6_UNIQUE_LOCAL_MASK = 0xFE
        private const val IPV6_UNIQUE_LOCAL_PREFIX = 0xFC

        private val BLOCKED_HOST_SUFFIXES = listOf(".localhost", ".local", ".internal", ".home.arpa")
    }
}
