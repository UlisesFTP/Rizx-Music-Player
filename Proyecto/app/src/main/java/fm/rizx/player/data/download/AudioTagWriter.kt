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
import org.jaudiotagger.audio.flac.metadatablock.MetadataBlockDataPicture
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.flac.FlacTag
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
 * **Two writers, one contract.** jaudiotagger handles MP4/MP3/FLAC and the rest; Ogg Opus goes to
 * [OggOpusTagger] instead, because jaudiotagger has no Opus support at all — its Ogg writer is Vorbis-only.
 * Which one a file gets is decided by its bytes (an `OpusHead` first packet), not by its extension.
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
        val oggOpus = file.extension.lowercase() in OGG_EXTENSIONS && OggOpusTagger.isOggOpus(file)
        if (!oggOpus && !isTaggable(file)) return@withContext false
        val cover = runCatching { fetchCover(track) }.getOrNull()
        if (oggOpus) {
            return@withContext tagOggOpus(file, track, albumTitle, releaseDateIso, year, cover)
        }
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
                val image = OggOpusTagger.describeImage(cover)
                runCatching {
                    tag.deleteArtworkField()
                    if (tag is FlacTag && image != null) {
                        // FLAC has to bypass jaudiotagger's own Artwork→field path: it asks the artwork to
                        // measure itself via `setImageFromData()`, which `AndroidArtwork` implements as an
                        // unconditional UnsupportedOperationException. Every FLAC download therefore lost
                        // its cover — silently, since tagging failures are swallowed by design. Building
                        // the picture block directly sidesteps the call entirely.
                        tag.setField(
                            MetadataBlockDataPicture(
                                cover, FRONT_COVER, image.mimeType, "",
                                image.width, image.height, COLOUR_DEPTH, 0,
                            ),
                        )
                    } else {
                        tag.setField(
                            AndroidArtwork().apply {
                                binaryData = cover
                                mimeType = image?.mimeType ?: "image/jpeg"
                                pictureType = FRONT_COVER
                                description = ""
                            },
                        )
                    }
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

    /**
     * The Ogg Opus path: the same fields under their Vorbis-comment names, with the cover as a
     * `METADATA_BLOCK_PICTURE`. Field names are the conventional ones (`TRACKNUMBER`, `ALBUMARTIST`) —
     * Vorbis comments have no registry, so what players actually read is the standard here.
     */
    private fun tagOggOpus(
        file: File,
        track: Track,
        albumTitle: String?,
        releaseDateIso: String?,
        year: Int?,
        cover: ByteArray?,
    ): Boolean = runCatching {
        val artist = track.artists.firstOrNull()?.name.orEmpty()
        val comments = buildList {
            add("TITLE" to track.title)
            add("ARTIST" to artist)
            add("ALBUMARTIST" to artist)
            add("ALBUM" to (track.album?.title ?: albumTitle).orEmpty())
            add("DATE" to (releaseDateIso ?: year?.toString()).orEmpty())
            add("TRACKNUMBER" to (track.trackNumber?.takeIf { it > 0 }?.toString().orEmpty()))
        }
        OggOpusTagger.write(file, comments, cover?.let { OggOpusTagger.describeImage(it) })
    }.getOrDefault(false)

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

        /** What a JPEG or PNG cover is, and what the FLAC picture block wants stated. */
        const val COLOUR_DEPTH = 24

        /** Guards against a pathological image bloating the file; covers are ~50-500 KB. */
        const val MAX_COVER_BYTES = 4 * 1024 * 1024

        /** Containers jaudiotagger can write. WebM is deliberately absent — nothing here can tag it. */
        val TAGGABLE = setOf("mp3", "m4a", "mp4", "aac", "flac", "ogg", "wav")

        /** Extensions worth sniffing for an Opus stream before jaudiotagger (which would read Vorbis). */
        val OGG_EXTENSIONS = setOf("opus", "ogg", "oga")

        fun isTaggable(file: File): Boolean = file.extension.lowercase() in TAGGABLE
    }
}
