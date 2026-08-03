package fm.rizx.player.data.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import fm.rizx.player.domain.model.DownloadedTrack
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Copies a download into the shared `Music/Rizx` folder so other players can see it.
 *
 * **No permission is needed.** minSdk is 34, so every device is on scoped storage: inserting a *new*
 * entry the app owns has required no permission since API 29, and `WRITE_EXTERNAL_STORAGE` is ignored
 * on Q+ anyway. `READ_MEDIA_AUDIO` is only for reading *other* apps' files. The manifest gains nothing.
 *
 * **The filename carries the metadata.** YouTube's adaptive audio M4A has no `ilst` tags, so the
 * TITLE/ARTIST values below are only rows in MediaStore's database — MediaScanner overwrites them from
 * the (absent) embedded tags on rescan and falls back to the display name. Setting them is free and
 * helps immediately; the `"Artist - Title.ext"` display name is what actually survives. Writing real
 * tags would need a tagging library and would contradict the owner's "save the bytes as-is" decision.
 */
class MediaStoreExporterImpl(
    private val context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : MediaStoreExporter {

    override suspend fun export(entry: DownloadedTrack, file: File): Result<ExportedFile> =
        withContext(io) {
            runCatching {
                val resolver = context.contentResolver
                val displayName = exportFileName(
                    artist = entry.track.artists.joinToString { it.name },
                    title = entry.track.title,
                    extension = entry.container,
                    fallback = entry.fileName.substringBeforeLast('.'),
                )
                // MediaStore rejects or misfiles anything that isn't a bare audio type, and NewPipe
                // hands back `audio/mp4; codecs="mp4a.40.2"`.
                val mime = bareMime(entry.mimeType)?.takeIf { it.startsWith("audio/") }
                    ?: mimeForExtension(entry.container)
                    ?: throw IOException("Unknown audio format")

                val pending = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Audio.Media.MIME_TYPE, mime)
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/$FOLDER")
                    put(MediaStore.Audio.Media.TITLE, entry.track.title)
                    entry.track.artists.joinToString { it.name }.takeIf { it.isNotBlank() }
                        ?.let { put(MediaStore.Audio.Media.ARTIST, it) }
                    entry.track.album?.title?.let { put(MediaStore.Audio.Media.ALBUM, it) }
                    entry.track.durationMs?.let { put(MediaStore.Audio.Media.DURATION, it.toInt()) }
                    // These columns only describe the row in MediaStore's own database — the cover art and
                    // the authoritative metadata live in the file's embedded tags (AudioTagWriter), which is
                    // what any other device or player actually reads.
                    entry.track.trackNumber?.takeIf { it > 0 }
                        ?.let { put(MediaStore.Audio.Media.TRACK, it) }
                    put(MediaStore.Audio.Media.IS_MUSIC, 1)
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
                val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val uri = resolver.insert(collection, pending) ?: throw IOException("MediaStore rejected the insert")
                try {
                    resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                        ?: throw IOException("Could not open $displayName for writing")
                    resolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) },
                        null,
                        null,
                    )
                } catch (e: Throwable) {
                    // Never leave a pending orphan — MediaStore holds one for 7 days.
                    resolver.delete(uri, null, null)
                    throw e
                }
                ExportedFile(uri = uri.toString(), displayName = displayName)
            }
        }

    /**
     * A one-column query is enough: the row exists or it doesn't. A uri from a previous install (or one
     * the user deleted) answers with no rows or throws, and both mean the same thing here — publish again.
     */
    override suspend fun exists(uri: String): Boolean = withContext(io) {
        runCatching {
            context.contentResolver
                .query(Uri.parse(uri), arrayOf(MediaStore.Audio.Media._ID), null, null, null)
                ?.use { it.moveToFirst() } ?: false
        }.getOrDefault(false)
    }

    private companion object {
        const val FOLDER = "Rizx"
    }
}
