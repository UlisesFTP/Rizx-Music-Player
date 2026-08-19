package fm.rizx.player.domain.repository

/** Portable formats Rizx can write without provider credentials. */
enum class PlaylistExportFormat(val extension: String, val mimeType: String) {
    RIZX_JSON("json", "application/json"),
    XSPF("xspf", "application/xspf+xml"),
    M3U8("m3u8", "audio/x-mpegurl"),
}

data class PlaylistExportArtifact(
    val fileName: String,
    val mimeType: String,
    val content: String,
    val includedItems: Int,
    val omittedItems: Int,
)

/** Generates a sanitized artifact. It performs no Android file I/O. */
interface PlaylistExportRepository {
    suspend fun export(playlistId: String, format: PlaylistExportFormat): PlaylistExportArtifact?
}

