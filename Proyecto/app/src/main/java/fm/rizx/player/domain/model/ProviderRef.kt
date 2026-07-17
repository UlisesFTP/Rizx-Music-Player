package fm.rizx.player.domain.model

import kotlinx.serialization.Serializable

/**
 * Canonical identity for any upstream-derived content (tracks, albums, artists, playlists).
 *
 * Identity is **`provider` + `id` only** — [url] is carried for convenience but is deliberately
 * excluded from [equals]/[hashCode] (a ref that later gains or changes its `url` is still the same
 * entity). Never use title/artist/album/url as a stable identity.
 *
 * Modeled as a plain class (not a `data class`) precisely so equality can exclude [url]; use
 * [copy] for value-style updates and [identityKey] for a stable string key.
 *
 * See AGENTS.md ("ProviderRef(provider, id) is the canonical identity") and
 * NUCLEAR_UPSTREAM_STUDY.md §2.
 */
@Serializable
class ProviderRef(
    val provider: String,
    val id: String,
    val url: String? = null,
) {
    /** Stable identity key, e.g. `"youtube:abc123"`. [url] is intentionally excluded. */
    val identityKey: String get() = "$provider:$id"

    fun copy(
        provider: String = this.provider,
        id: String = this.id,
        url: String? = this.url,
    ): ProviderRef = ProviderRef(provider, id, url)

    override fun equals(other: Any?): Boolean =
        this === other || (other is ProviderRef && other.provider == provider && other.id == id)

    override fun hashCode(): Int = identityKey.hashCode()

    override fun toString(): String =
        "ProviderRef($identityKey${url?.let { ", url=$it" } ?: ""})"
}
