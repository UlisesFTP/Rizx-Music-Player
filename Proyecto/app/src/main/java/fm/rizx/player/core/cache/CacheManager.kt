package fm.rizx.player.core.cache

import android.content.Context
import coil.annotation.ExperimentalCoilApi
import coil.imageLoader
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Clears and measures the app's image cache (Coil memory + disk). This is the only user-clearable
 * cache: the playback-session JSON (filesDir), Room (`rizx.db`) and DataStore (`settings`) are durable
 * state and are intentionally never touched here. Every call is guarded so a clear can't crash the UI.
 */
@Singleton
class CacheManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Human-readable on-disk size of the image cache (e.g. "12.4 MB"); "0 B" when empty/unavailable. */
    @OptIn(ExperimentalCoilApi::class)
    fun diskSizeLabel(): String =
        formatBytes(runCatching { context.imageLoader.diskCache?.size ?: 0L }.getOrDefault(0L))

    /** Drops the in-memory and on-disk image caches. Safe to call repeatedly. */
    @OptIn(ExperimentalCoilApi::class)
    suspend fun clear() {
        withContext(Dispatchers.IO) {
            runCatching {
                val loader = context.imageLoader
                loader.memoryCache?.clear()
                loader.diskCache?.clear()
            }
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
