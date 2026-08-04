package fm.rizx.player.data.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
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
 * **Permissions.** On API 29+ (scoped storage) inserting a *new* entry the app owns needs none —
 * `WRITE_EXTERNAL_STORAGE` is ignored there and `READ_MEDIA_AUDIO` is only for reading *other* apps'
 * files. On 26–28 there is no scoped storage: the file must physically land in the public `Music/`
 * directory and the insert carries its path in `DATA`, which does require `WRITE_EXTERNAL_STORAGE`
 * (declared with `maxSdkVersion="28"`, requested at the opt-in UI — `rememberSaveToPhonePermission`).
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
                if (Build.VERSION.SDK_INT >= 29) {
                    exportModern(entry, file, displayName, mime)
                } else {
                    exportLegacy(entry, file, displayName, mime)
                }
            }
        }

    /** API 29+: MediaStore owns the location (`RELATIVE_PATH`) and dedups repeated names itself. */
    private fun exportModern(
        entry: DownloadedTrack,
        file: File,
        displayName: String,
        mime: String,
    ): ExportedFile {
        val resolver = context.contentResolver
        val pending = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, mime)
            put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/$FOLDER")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
            putCommonColumns(entry)
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
        return ExportedFile(uri = uri.toString(), displayName = displayName)
    }

    /**
     * API 26–28, pre-scoped-storage: the bytes are written into the public `Music/Rizx` directory
     * first — deduped by hand, because the filesystem won't invent `"name (1).ext"` the way MediaStore
     * does on 29+ — and then registered with a `DATA` row (`RELATIVE_PATH`/`IS_PENDING` don't exist
     * yet). Same no-leftovers rule as the modern branch: if the row can't be created, the file goes.
     */
    @Suppress("DEPRECATION")
    private fun exportLegacy(
        entry: DownloadedTrack,
        file: File,
        displayName: String,
        mime: String,
    ): ExportedFile {
        if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) {
            throw IOException("External storage is not mounted")
        }
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), FOLDER)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Could not create ${dir.absolutePath}")
        val target = dedupe(dir, displayName)
        file.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DATA, target.absolutePath)
            put(MediaStore.Audio.Media.DISPLAY_NAME, target.name)
            put(MediaStore.Audio.Media.MIME_TYPE, mime)
            putCommonColumns(entry)
        }
        val uri = try {
            context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
        } catch (e: Throwable) {
            target.delete()
            throw e
        } ?: run {
            target.delete()
            throw IOException("MediaStore rejected the insert")
        }
        return ExportedFile(uri = uri.toString(), displayName = target.name)
    }

    /** The audio columns both branches share. */
    private fun ContentValues.putCommonColumns(entry: DownloadedTrack) {
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
    }

    /** `Artist - Title.m4a` → `Artist - Title (1).m4a` → … first name not taken in [dir]. */
    private fun dedupe(dir: File, displayName: String): File {
        val first = File(dir, displayName)
        if (!first.exists()) return first
        val base = displayName.substringBeforeLast('.')
        val ext = displayName.substringAfterLast('.', missingDelimiterValue = "")
        val suffix = if (ext.isEmpty()) "" else ".$ext"
        var n = 1
        while (true) {
            val candidate = File(dir, "$base ($n)$suffix")
            if (!candidate.exists()) return candidate
            n++
        }
    }

    /**
     * A one-column query is enough on 29+: the row exists or it doesn't, and scoped storage keeps rows
     * and files coupled. Below 29 they aren't — a file manager can delete the file and leave the row,
     * which would block re-publishing forever — so the legacy check asks for `DATA` and requires the
     * file itself to still be there. A uri from a previous install answers with no rows or throws, and
     * both mean the same thing here — publish again.
     */
    override suspend fun exists(uri: String): Boolean = withContext(io) {
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                context.contentResolver
                    .query(Uri.parse(uri), arrayOf(MediaStore.Audio.Media._ID), null, null, null)
                    ?.use { it.moveToFirst() } ?: false
            } else {
                @Suppress("DEPRECATION")
                context.contentResolver
                    .query(Uri.parse(uri), arrayOf(MediaStore.Audio.Media.DATA), null, null, null)
                    ?.use { c -> c.moveToFirst() && c.getString(0)?.let { File(it).exists() } == true }
                    ?: false
            }
        }.getOrDefault(false)
    }

    private companion object {
        const val FOLDER = "Rizx"
    }
}
