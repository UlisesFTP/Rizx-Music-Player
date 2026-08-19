package fm.rizx.player.data.repository

import fm.rizx.player.data.local.db.PlaylistDao
import fm.rizx.player.data.local.store.PortablePlaylistFormats
import fm.rizx.player.data.local.store.TrackJson
import fm.rizx.player.domain.model.Playlist
import fm.rizx.player.domain.model.PlaylistItem
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.repository.PlaylistExportArtifact
import fm.rizx.player.domain.repository.PlaylistExportFormat
import fm.rizx.player.domain.repository.PlaylistExportRepository
import java.time.Instant

class PlaylistExportRepositoryImpl(
    private val dao: PlaylistDao,
    private val nowIso: () -> String = { Instant.now().toString() },
) : PlaylistExportRepository {
    override suspend fun export(playlistId: String, format: PlaylistExportFormat): PlaylistExportArtifact? {
        val entity = dao.getPlaylist(playlistId) ?: return null
        val items = dao.getItems(playlistId).map {
            PlaylistItem(it.id, TrackJson.decodeTrack(it.trackJson), it.note, it.addedAtIso)
        }
        val playlist = Playlist(
            id = entity.id,
            name = entity.name,
            description = entity.description,
            createdAtIso = entity.createdAtIso,
            lastModifiedIso = entity.lastModifiedIso,
            origin = if (entity.originProvider != null && entity.originId != null) {
                ProviderRef(entity.originProvider, entity.originId)
            } else null,
            isReadOnly = entity.isReadOnly,
            parentId = entity.parentId,
            items = items,
        )
        return PortablePlaylistFormats.artifact(playlist, format, nowIso())
    }
}
