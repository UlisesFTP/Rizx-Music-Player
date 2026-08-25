package fm.rizx.player.domain.share

/**
 * The two spellings of a Rizx share link, and how to read a token out of either.
 *
 * - `https://<shareBaseUrl>/<token>` — what the app hands out, what the QR encodes, what a browser can
 *   open. Becomes an Android App Link the moment the share host serves `assetlinks.json`.
 * - `rizx://share/<token>` — the app's own scheme. Needs no domain verification, so it is what the
 *   browser landing page's "Open in Rizx" button fires (via `intent://`) and what a scanner that honours
 *   custom schemes can open directly.
 *
 * Pure string work on purpose: it runs in the Activity's intent path and in tests, with no `Uri`.
 */
object ShareLinks {
    const val SCHEME = "rizx"
    const val HOST = "share"

    /** Same shape the server enforces: 32 random bytes, base64url, unpadded. */
    private val TOKEN = Regex("^[A-Za-z0-9_-]{43}$")

    fun isToken(value: String): Boolean = TOKEN.matches(value)

    /**
     * The token carried by [uri], or null when [uri] is neither spelling, points at another host, or
     * carries something that isn't a token. [shareBaseUrl] blank means the install has no share backend:
     * the HTTPS spelling can't be recognised then, only the app scheme.
     */
    fun tokenFrom(uri: String, shareBaseUrl: String): String? {
        val trimmed = uri.trim()
        val base = shareBaseUrl.trim().trimEnd('/')
        if (base.isNotEmpty() && trimmed.length > base.length + 1 &&
            trimmed.substring(0, base.length).equals(base, ignoreCase = true) && trimmed[base.length] == '/'
        ) {
            return trimmed.substring(base.length + 1).takeIf(::isToken)
        }
        val appPrefix = "$SCHEME://$HOST/"
        if (trimmed.length > appPrefix.length && trimmed.substring(0, appPrefix.length).equals(appPrefix, ignoreCase = true)) {
            return trimmed.substring(appPrefix.length).takeIf(::isToken)
        }
        return null
    }

    /** The HTTPS spelling — the one the importer and the rest of the app already understand. */
    fun shareUrl(shareBaseUrl: String, token: String): String = "${shareBaseUrl.trim().trimEnd('/')}/$token"

    /** The app-scheme spelling. */
    fun appUri(token: String): String = "$SCHEME://$HOST/$token"
}
