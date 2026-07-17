package fm.rizx.player

import fm.rizx.player.domain.model.DownloadState
import fm.rizx.player.domain.model.DownloadedTrack
import fm.rizx.player.domain.model.Stream
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.repository.DownloadRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Nothing downloaded, nothing downloadable — for the ViewModels that take a [DownloadRepository] but
 * whose tests are about something else. Downloads have their own coverage in `DownloadRepositoryTest`.
 */
class NoDownloads : DownloadRepository {
    override val downloads: StateFlow<List<DownloadedTrack>> = MutableStateFlow(emptyList())
    override val states: StateFlow<Map<String, DownloadState>> = MutableStateFlow(emptyMap())
    override fun localStream(track: Track): Stream? = null
    override fun download(track: Track) = Unit
    override fun downloadAll(tracks: List<Track>) = Unit
    override fun cancel(key: String) = Unit
    override suspend fun delete(key: String) = Unit
    override suspend fun deleteAll() = Unit
    override suspend fun markCorrupt(key: String) = Unit
    override suspend fun export(key: String): Result<String> = Result.failure(UnsupportedOperationException())
}
