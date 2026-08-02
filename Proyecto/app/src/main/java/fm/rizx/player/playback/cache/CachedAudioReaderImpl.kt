package fm.rizx.player.playback.cache

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import fm.rizx.player.domain.repository.CachedAudioReader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.OutputStream
import kotlin.coroutines.coroutineContext

/**
 * Reads whole songs back out of [AudioCache], through Media3's own [CacheDataSource] — the only
 * component that knows how the spans on disk stitch back into a contiguous resource.
 *
 * The upstream is **null**, which per Media3's contract turns any cache miss into a read error instead
 * of a network fetch. That is exactly the behaviour wanted here: this class exists so a download can be
 * *free*, and a version that quietly went to the network on a miss would just be a second, worse
 * downloader.
 *
 * The URI in the [DataSpec] is a placeholder — the cache key is what names the resource (Media3's
 * default key factory prefers `DataSpec.key`), the same way `QueueStreamResolver` stamps playback reads.
 */
@UnstableApi
class CachedAudioReaderImpl(
    private val audioCache: AudioCache,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : CachedAudioReader {

    override fun fullyCachedCodecs(identityKey: String): List<String> = runCatching {
        val prefix = identityKey + KEY_FORMAT_SEPARATOR
        audioCache.cache.keys
            .filter { it.startsWith(prefix) && audioCache.isFullyCached(it) }
            .map { it.removePrefix(prefix) }
    }.getOrDefault(emptyList())

    override suspend fun copyTo(identityKey: String, codec: String, sink: OutputStream): Boolean =
        withContext(io) {
            val key = audioCacheKey(identityKey, codec)
            val source = CacheDataSource(audioCache.cache, /* upstreamDataSource = */ null)
            try {
                source.open(
                    DataSpec.Builder()
                        .setUri(Uri.parse(PLACEHOLDER_URI))
                        .setKey(key)
                        .build(),
                )
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    coroutineContext.ensureActive()
                    val read = source.read(buffer, 0, buffer.size)
                    if (read == androidx.media3.common.C.RESULT_END_OF_INPUT) break
                    if (read > 0) sink.write(buffer, 0, read)
                }
                true
            } catch (e: Exception) {
                // Eviction raced the read, or the bucket was never whole. The caller falls back to the
                // network path it would have taken anyway; nothing to surface.
                false
            } finally {
                runCatching { source.close() }
            }
        }

    private companion object {
        const val PLACEHOLDER_URI = "https://cached.rizx.invalid/adopt"
        const val BUFFER_BYTES = 64 * 1024
    }
}
