package fm.rizx.player.domain.repository

import java.io.OutputStream

/**
 * Read access to the streaming byte-cache, for the one caller outside playback with a legitimate use
 * for it: a download of a song the user just listened to, whose bytes are therefore already on disk.
 *
 * A domain interface because the cache itself lives in the playback layer (it is Media3's), and the
 * download repository lives in data — this is the narrow, direction-respecting bridge between them.
 * Everything here is best-effort by contract: a cache is allowed to evict at any moment, and every
 * "no" answer just means the download takes the network path it would have taken anyway.
 */
interface CachedAudioReader {

    /**
     * The codec buckets of [identityKey] that are **fully** cached, lowercased (`m4a`, `opus`,
     * `webm opus`, `mp3`, `flac`). Empty when nothing whole is held. Partial buckets are never listed:
     * a download must not be seeded from half a song.
     */
    fun fullyCachedCodecs(identityKey: String): List<String>

    /**
     * Streams the cached bytes of [identityKey]'s [codec] bucket into [sink].
     *
     * Returns false when the bucket is gone or turns out incomplete mid-read — eviction can race this —
     * in which case whatever was written to [sink] must be discarded by the caller.
     */
    suspend fun copyTo(identityKey: String, codec: String, sink: OutputStream): Boolean
}
