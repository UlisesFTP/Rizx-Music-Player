package fm.rizx.player.data.repository

import android.content.Context
import android.provider.MediaStore
import fm.rizx.player.data.local.media.LocalIds
import fm.rizx.player.data.local.media.localTrack
import fm.rizx.player.domain.model.Stream
import fm.rizx.player.domain.model.StreamProtocol
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.repository.LocalLibraryRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * `MediaStore`-backed local library. [refresh] queries the device's audio (music only) into [songs];
 * [localStream] rebuilds the content URI from a local track's id so ExoPlayer's `ContentDataSource`
 * plays it directly — no bytes copied, no URL persisted.
 *
 * Only [refresh] touches Android/`ContentResolver`; [localStream] and the row mapping are pure so they
 * unit-test on the JVM. A missing permission or a failed query yields an empty scan, never a crash.
 */
class LocalLibraryRepositoryImpl(
    private val context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : LocalLibraryRepository {

    private val _songs = MutableStateFlow<List<Track>>(emptyList())
    override val songs: StateFlow<List<Track>> = _songs.asStateFlow()

    override suspend fun refresh() {
        _songs.value = withContext(io) { runCatching { query() }.getOrDefault(emptyList()) }
    }

    private fun query(): List<Track> {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ARTIST_ID,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
        )
        // Music only (skips ringtones/notifications/alarms), sorted by title.
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        val out = ArrayList<Track>()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, selection, null, sortOrder,
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val artistIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST_ID)
            val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val trackCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            while (c.moveToNext()) {
                out += localTrack(
                    id = c.getLong(idCol),
                    title = c.getString(titleCol),
                    artist = c.getString(artistCol),
                    artistId = if (c.isNull(artistIdCol)) null else c.getLong(artistIdCol),
                    album = c.getString(albumCol),
                    albumId = if (c.isNull(albumIdCol)) null else c.getLong(albumIdCol),
                    durationMs = if (c.isNull(durCol)) null else c.getLong(durCol),
                    trackNumber = if (c.isNull(trackCol)) null else c.getInt(trackCol),
                )
            }
        }
        return out
    }

    override fun localStream(track: Track): Stream? {
        if (track.source.provider != LocalIds.PROVIDER) return null
        // Only real song rows resolve; namespaced `album:`/`artist:` refs (non-numeric id) don't.
        val id = track.source.id.toLongOrNull() ?: return null
        return Stream(
            url = "${LocalIds.AUDIO_CONTENT_URI}/$id",
            protocol = StreamProtocol.FILE,
            durationMs = track.durationMs,
            source = track.source,
        )
    }
}
