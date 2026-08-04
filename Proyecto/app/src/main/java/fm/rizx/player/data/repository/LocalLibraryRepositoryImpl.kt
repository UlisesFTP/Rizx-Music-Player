package fm.rizx.player.data.repository

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import fm.rizx.player.data.local.media.LocalIds
import fm.rizx.player.data.local.media.localTrack
import fm.rizx.player.domain.model.codecForMime
import fm.rizx.player.domain.model.containerForMime
import fm.rizx.player.domain.model.Stream
import fm.rizx.player.domain.model.StreamProtocol
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.repository.LocalLibraryRepository
import fm.rizx.player.domain.repository.LocalSong
import fm.rizx.player.domain.repository.OpenedFilesRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * `MediaStore`-backed local library. [refresh] queries the device's audio (music only) into [scan];
 * [localStream] rebuilds the content URI from a local track's id so ExoPlayer's `ContentDataSource`
 * plays it directly — no bytes copied, no URL persisted.
 *
 * The scan row carries what the *library screens* need beyond the track — when it was added, its size,
 * its mime — without touching the domain `Track`; [songs] stays the plain track list every existing
 * consumer reads. The mime also rides into [localStream] as an honest codec claim, which is what makes
 * the player's technical readout (and the lossless tag) work for a local FLAC.
 *
 * Only [refresh] touches Android/`ContentResolver`; [localStream] and the row mapping are pure so they
 * unit-test on the JVM. A missing permission or a failed query yields an empty scan, never a crash.
 */
class LocalLibraryRepositoryImpl(
    private val context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Files opened through the system picker; this seam serves their streams too. Null in tests. */
    private val openedFiles: OpenedFilesRepository? = null,
) : LocalLibraryRepository {

    private val _scan = MutableStateFlow<List<LocalSong>>(emptyList())
    override val scan: StateFlow<List<LocalSong>> = _scan.asStateFlow()

    // A sibling flow rather than a mapped one: this class owns no coroutine scope to host a stateIn,
    // and both flows have exactly one writer (refresh), so they cannot disagree.
    private val _songs = MutableStateFlow<List<Track>>(emptyList())
    override val songs: StateFlow<List<Track>> = _songs.asStateFlow()

    /** identityKey → mime, so [localStream] stays a synchronous map lookup. */
    @Volatile
    private var mimeByKey: Map<String, String?> = emptyMap()

    override suspend fun refresh() {
        val rows = withContext(io) { runCatching { query() }.getOrDefault(emptyList()) }
        mimeByKey = rows.associate { it.track.source.identityKey to it.mimeType }
        _scan.value = rows
        _songs.value = rows.map { it.track }
    }

    /**
     * Registers [onChange] for MediaStore audio changes and returns an unregister handle — how the
     * screen refreshes itself when files land while it is open. Callback-based rather than a Flow so
     * the repository still owns no coroutine scope.
     */
    override fun observeChanges(onChange: () -> Unit): () -> Unit {
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean, uri: Uri?) = onChange()
        }
        val registered = runCatching {
            context.contentResolver.registerContentObserver(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer,
            )
        }.isSuccess
        return { if (registered) runCatching { context.contentResolver.unregisterContentObserver(observer) } }
    }

    private fun query(): List<LocalSong> {
        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ARTIST_ID)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.ALBUM_ID)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.TRACK)
            // The file's own genre tag. Free here and the automatic equalizer's only offline source of a
            // genre — a local file has no catalogue to ask. The column itself only exists on API 30+:
            // asking for it below throws IllegalArgumentException, which refresh()'s runCatching would
            // silently turn into a permanently empty library.
            if (Build.VERSION.SDK_INT >= 30) add(MediaStore.Audio.Media.GENRE)
            add(MediaStore.Audio.Media.DATE_ADDED)
            add(MediaStore.Audio.Media.SIZE)
            add(MediaStore.Audio.Media.MIME_TYPE)
        }.toTypedArray()
        // Music only (skips ringtones/notifications/alarms), sorted by title.
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        val out = ArrayList<LocalSong>()
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
            // getColumnIndex, not ...OrThrow: GENRE is a real column but plenty of files carry no tag, and
            // a device that doesn't populate it must not fail the whole scan.
            val genreCol = c.getColumnIndex(MediaStore.Audio.Media.GENRE)
            val addedCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            while (c.moveToNext()) {
                val track = localTrack(
                    id = c.getLong(idCol),
                    title = c.getString(titleCol),
                    artist = c.getString(artistCol),
                    artistId = if (c.isNull(artistIdCol)) null else c.getLong(artistIdCol),
                    album = c.getString(albumCol),
                    albumId = if (c.isNull(albumIdCol)) null else c.getLong(albumIdCol),
                    durationMs = if (c.isNull(durCol)) null else c.getLong(durCol),
                    trackNumber = if (c.isNull(trackCol)) null else c.getInt(trackCol),
                    genre = if (genreCol < 0 || c.isNull(genreCol)) null else c.getString(genreCol),
                )
                out += LocalSong(
                    track = track,
                    dateAddedSec = if (c.isNull(addedCol)) 0L else c.getLong(addedCol),
                    sizeBytes = if (c.isNull(sizeCol)) 0L else c.getLong(sizeCol),
                    mimeType = if (c.isNull(mimeCol)) null else c.getString(mimeCol),
                )
            }
        }
        return out
    }

    override fun localStream(track: Track): Stream? {
        // Picker-opened documents share this seam: the resolver asks once, and whichever half knows the
        // track answers. Neither path resolves anything over a network.
        if (track.source.provider == LocalIds.FILE_PROVIDER) return openedFiles?.streamFor(track)
        if (track.source.provider != LocalIds.PROVIDER) return null
        // Only real song rows resolve; namespaced `album:`/`artist:` refs (non-numeric id) don't.
        val id = track.source.id.toLongOrNull() ?: return null
        val mime = mimeByKey[track.source.identityKey]
        return Stream(
            url = "${LocalIds.AUDIO_CONTENT_URI}/$id",
            protocol = StreamProtocol.FILE,
            mimeType = mime,
            // The honest half of the readout: the mime names the codec for FLAC/WAV/MP3, and the player
            // says so under the artwork — a local FLAC finally reads as one. Ambiguous mimes claim nothing.
            codec = codecForMime(mime),
            container = containerForMime(mime),
            durationMs = track.durationMs,
            source = track.source,
        )
    }
}
