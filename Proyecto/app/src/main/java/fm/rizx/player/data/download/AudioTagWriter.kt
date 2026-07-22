package fm.rizx.player.data.download

import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.model.coverUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.images.AndroidArtwork
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Writes title / artist / album / date **and the cover art** into a downloaded file, so the song carries its
 * own metadata anywhere it's played — Rizx's local library, another Android player, or a PC after copying.
 *
 * Before this, the metadata only ever reached MediaStore's database at export time. That's invisible to any
 * other device, and MediaStore takes album art from the file's embedded tags, never from those columns —
 * which is exactly why downloads showed no cover.
 *
 * **Android caveat (load-bearing):** jaudiotagger ships a desktop image path built on `java.awt` /
 * `javax.imageio`, neither of which exists on Android. Those classes are present in the jar and dex fine —
 * they only explode when *executed*. So this class must construct [AndroidArtwork] directly and never touch
 * `ArtworkFactory`/`StandardArtwork`, which pick their handler by environment detection.
 * `TagOptionSingleton.isAndroid` is set for the same reason.
 *
 * Tagging is cosmetic: a failure here must never fail a download that already produced a playable file.
 */
class AudioTagWriter(
    private val client: OkHttpClient,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * Tags [file] in place for [track]. [releaseDateIso] (`YYYY-MM-DD`) wins over [year] when present.
     * Returns true only if something was actually written.
     */
    suspend fun tag(
        file: File,
        track: Track,
        /** Album name, when the caller recovered one the track itself didn't carry (e.g. Spotify imports). */
        albumTitle: String? = null,
        releaseDateIso: String? = null,
        year: Int? = null,
    ): Boolean = withContext(io) {
        if (!isTaggable(file)) return@withContext false
        val cover = runCatching { fetchCover(track) }.getOrNull()
        try {
            configureForAndroid()
            val audio = AudioFileIO.read(file)
            val tag = audio.tagOrCreateAndSetDefault

            tag.setField(FieldKey.TITLE, track.title)
            track.artists.firstOrNull()?.name?.takeIf { it.isNotBlank() }?.let {
                tag.setField(FieldKey.ARTIST, it)
                tag.setField(FieldKey.ALBUM_ARTIST, it)
            }
            (track.album?.title ?: albumTitle)?.takeIf { it.isNotBlank() }
                ?.let { tag.setField(FieldKey.ALBUM, it) }
            (releaseDateIso ?: year?.toString())?.takeIf { it.isNotBlank() }?.let {
                // YEAR maps to the container's own date frame (ID3 TDRC / MP4 ©day), so a full
                // "YYYY-MM-DD" survives where the format allows it and degrades to the year where it doesn't.
                runCatching { tag.setField(FieldKey.YEAR, it) }
            }
            track.trackNumber?.takeIf { it > 0 }?.let { runCatching { tag.setField(FieldKey.TRACK, it.toString()) } }

            if (cover != null) {
                runCatching {
                    tag.deleteArtworkField()
                    tag.setField(
                        AndroidArtwork().apply {
                            binaryData = cover
                            mimeType = "image/jpeg"
                            pictureType = FRONT_COVER
                            description = ""
                        },
                    )
                }
            }

            audio.commit()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Includes the formats jaudiotagger can't write (WebM/Opus from YouTube when no M4A track
            // exists). The file stays exactly as downloaded: playable, just untagged.
            false
        }
    }

    /** Downloads the cover bytes, or null when the track has no artwork or the fetch fails. */
    private fun fetchCover(track: Track): ByteArray? {
        val url = track.artwork.coverUrl() ?: return null
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            val bytes = response.body?.bytes() ?: return null
            return bytes.takeIf { it.size in 1..MAX_COVER_BYTES }
        }
    }

    private fun configureForAndroid() {
        if (configured) return
        runCatching {
            TagOptionSingleton.getInstance().isAndroid = true
            // jaudiotagger logs prolifically at INFO on every read/write; quiet it so a download doesn't
            // spam logcat.
            Logger.getLogger("org.jaudiotagger").level = Level.WARNING
        }
        configured = true
    }

    private companion object {
        @Volatile
        var configured = false

        const val FRONT_COVER = 3

        /** Guards against a pathological image bloating the file; covers are ~50-500 KB. */
        const val MAX_COVER_BYTES = 4 * 1024 * 1024

        /** Containers jaudiotagger can write. WebM/Opus is deliberately absent — it can't. */
        val TAGGABLE = setOf("mp3", "m4a", "mp4", "aac", "flac", "ogg", "wav")

        fun isTaggable(file: File): Boolean = file.extension.lowercase() in TAGGABLE
    }
}
