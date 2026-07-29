package fm.rizx.player.data.provider

import fm.rizx.player.domain.model.ProviderRef

/**
 * The canonical public URL of a remote playlist ref.
 *
 * Shared rather than private to the playlist repository because two questions need the same answer:
 * *"open this"* and *"could this be opened at all?"*. The Home feed asks the second before it draws a
 * card — a playlist that renders and then opens empty is worse than one that never appeared.
 *
 * Prefers the ref's own [ProviderRef.url] and falls back to rebuilding from the namespaced id, since
 * a nav round-trip drops the url and leaves only `provider` + `id`.
 */
object PlaylistUrls {

    fun canonical(source: ProviderRef): String? {
        source.url?.takeIf { it.isNotBlank() }?.let { return it }
        val raw = source.id.substringAfter(':').takeIf { it.isNotBlank() } ?: return null
        return when (source.provider) {
            "deezer" -> "https://www.deezer.com/playlist/$raw"
            "youtube" -> "https://www.youtube.com/playlist?list=$raw"
            "spotify" -> "https://open.spotify.com/playlist/$raw"
            // Apple's slug is decorative — the id alone resolves, so a ref that lost its url still opens.
            "applemusic" -> "https://music.apple.com/us/playlist/playlist/$raw"
            else -> null
        }
    }
}
