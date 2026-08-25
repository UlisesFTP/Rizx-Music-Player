package fm.rizx.player.domain.share

data class PlaylistShare(
    val id: String,
    val url: String,
    val expiresAtIso: String,
    val createdAtIso: String? = null,
)

interface PlaylistShareRepository {
    val configured: Boolean
    suspend fun create(playlistId: String, captchaToken: String? = null): PlaylistShare

    /**
     * The link this playlist already has, or null when it has none, expired, or was revoked elsewhere.
     *
     * The snapshot behind a link is immutable, so this returns the link as it was created — editing the
     * playlist afterwards does not change what the link opens.
     */
    suspend fun existing(playlistId: String): PlaylistShare?

    suspend fun active(): List<PlaylistShare>
    suspend fun revoke(shareId: String)
}

