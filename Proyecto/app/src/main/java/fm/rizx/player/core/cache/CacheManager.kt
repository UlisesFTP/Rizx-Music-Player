package fm.rizx.player.core.cache

import android.content.Context
import coil.annotation.ExperimentalCoilApi
import coil.imageLoader
import androidx.media3.common.util.UnstableApi
import dagger.hilt.android.qualifiers.ApplicationContext
import fm.rizx.player.playback.cache.AudioCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Clears and measures the app's throwaway caches: cover art (Coil) **and** the streamed-audio cache.
 * These are the only user-clearable stores — the playback-session JSON (filesDir), Room (`rizx.db`),
 * DataStore (`settings`) and downloaded files are durable state and are intentionally never touched
 * here. Every call is guarded so a clear can't crash the UI.
 *
 * Audio dominates the number by an order of magnitude (songs are megabytes, thumbnails kilobytes), so
 * the two are reported as one total: what the user wants to know is how much space the app is holding.
 */
@UnstableApi
@Singleton
class CacheManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audioCache: AudioCache,
) {
    /** Human-readable size of the cached audio + artwork (e.g. "312.4 MB"); "0 B" when empty. */
    @OptIn(ExperimentalCoilApi::class)
    fun diskSizeLabel(): String {
        val images = runCatching { context.imageLoader.diskCache?.size ?: 0L }.getOrDefault(0L)
        val audio = runCatching { audioCache.sizeBytes() }.getOrDefault(0L)
        return formatBytes(images + audio)
    }

    /** Drops the cached audio and images. Safe to call repeatedly. */
    @OptIn(ExperimentalCoilApi::class)
    suspend fun clear() {
        withContext(Dispatchers.IO) {
            runCatching {
                val loader = context.imageLoader
                loader.memoryCache?.clear()
                loader.diskCache?.clear()
            }
            runCatching { audioCache.clear() }
        }
    }

    private companion object {
        fun formatBytes(bytes: Long): String {
            if (bytes <= 0L) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB")
            var value = bytes.toDouble()
            var i = 0
            while (value >= 1024 && i < units.lastIndex) {
                value /= 1024
                i++
            }
            return if (i == 0) "$bytes B" else String.format(Locale.US, "%.1f %s", value, units[i])
        }
    }
}
