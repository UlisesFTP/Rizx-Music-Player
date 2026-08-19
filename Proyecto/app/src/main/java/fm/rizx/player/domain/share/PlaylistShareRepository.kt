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
    suspend fun active(): List<PlaylistShare>
    suspend fun revoke(shareId: String)
}

