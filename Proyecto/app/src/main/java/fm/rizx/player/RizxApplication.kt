package fm.rizx.player

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import fm.rizx.player.domain.plugin.PluginRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** A quarter of the app's heap for decoded covers — the scroll never re-decodes what it just showed. */
private const val MEMORY_CACHE_FRACTION = 0.25

private const val IMAGE_CACHE_DIR = "image_cache"
private const val IMAGE_CACHE_BYTES = 256L * 1024 * 1024
private const val CROSSFADE_MS = 150

@HiltAndroidApp
class RizxApplication : Application(), ImageLoaderFactory {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface PluginBootstrapEntryPoint {
        fun pluginRepository(): PluginRepository
    }

    /** Lets Coil borrow the app's HTTP stack; resolved lazily, on Coil's first image load. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface HttpEntryPoint {
        fun okHttpClient(): OkHttpClient
    }

    /**
     * App-wide Coil loader.
     *
     * It used to be `ImageLoader.Builder(this).crossfade(true)` and nothing else, which cost more than
     * it looked like:
     *
     *  - **`respectCacheHeaders(false)`** is the one that matters. Deezer's and Apple's CDNs answer
     *    `no-cache`, and Coil honours that by default — so every cover was re-downloaded on every
     *    scroll and every launch, even though these images never change once published.
     *  - An explicit disk cache, sized. Without one Coil picks a small default under a different
     *    directory than the rest of the app's caches.
     *  - The app's own [okhttp3.OkHttpClient], so images share its connection pool and User-Agent
     *    instead of Coil standing up a second HTTP stack.
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient {
            EntryPointAccessors
                .fromApplication(this, HttpEntryPoint::class.java)
                .okHttpClient()
        }
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(MEMORY_CACHE_FRACTION).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve(IMAGE_CACHE_DIR))
                .maxSizeBytes(IMAGE_CACHE_BYTES)
                .build()
        }
        .respectCacheHeaders(false)
        .crossfade(CROSSFADE_MS)
        .build()

    override fun onCreate() {
        super.onCreate()
        // Reload installed JS plugins (ADR 0014) off the main thread — each is isolated, so a broken
        // plugin can never block startup or the others.
        //
        // Resolving the entry point is *inside* the coroutine on purpose: it is what forces the whole
        // singleton graph (23 providers, 10 Retrofit services) to be constructed, including the
        // blocking DataStore reads that reconcile the active providers. Doing that here used to happen
        // on the main thread before the first frame.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            runCatching {
                EntryPointAccessors
                    .fromApplication(this@RizxApplication, PluginBootstrapEntryPoint::class.java)
                    .pluginRepository()
                    .reloadInstalled()
            }
        }
    }
}
