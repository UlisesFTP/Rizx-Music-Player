package fm.rizx.player.playback.canvas

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * The canvas byte cache: a loop is downloaded once, then replayed from disk.
 *
 * Reopening Now Playing used to cost the whole video again — [CanvasPlaybackController] tears the
 * player down whenever the screen goes away (deliberately: decoration must not buffer in the
 * background), so every return re-fetched the same few megabytes and paid the same first-frame wait.
 * With the bytes on disk the second open renders at disk speed, and a song on repeat costs its
 * canvas exactly once.
 *
 * Same [SimpleCache] pattern as [AudioCache][fm.rizx.player.playback.cache.AudioCache], but keyed by
 * **URL** on purpose — the opposite of the audio cache's identity keys. Apple's motion URLs and
 * TIDAL's CDN paths are stable, unlike googlevideo's tokened audio URLs, and the resolution cache in
 * front of this one already re-resolves after its own TTL, so a rotated URL simply becomes a new
 * entry and the old one ages out of the LRU. Small and fixed: a loop is 1-4 MB, so 96 MB holds a few
 * dozen — a listening session's worth — without becoming a second downloads folder.
 *
 * Process-wide singleton because [SimpleCache] throws if two instances share a directory.
 */
@UnstableApi
object CanvasMediaCache {

    @Volatile
    private var cache: SimpleCache? = null

    fun dataSourceFactory(context: Context): DataSource.Factory {
        val app = context.applicationContext
        val opened = cache ?: synchronized(this) {
            cache ?: SimpleCache(
                File(app.cacheDir, DIRECTORY),
                LeastRecentlyUsedCacheEvictor(MAX_BYTES),
                StandaloneDatabaseProvider(app),
            ).also { cache = it }
        }
        return CacheDataSource.Factory()
            .setCache(opened)
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(app))
            // A cache hiccup must degrade to plain streaming, never to a dead canvas.
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /** Under cacheDir, not filesDir: the OS may clear it under pressure, which is fine for decoration. */
    private const val DIRECTORY = "canvas-media"
    private const val MAX_BYTES = 96L * 1024 * 1024
}
