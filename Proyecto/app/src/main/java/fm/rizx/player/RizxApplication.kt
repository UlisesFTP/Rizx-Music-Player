package fm.rizx.player

import android.app.Activity
import android.app.Application
import android.os.Bundle
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import fm.rizx.player.data.sync.SyncScheduler
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

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SyncBootstrapEntryPoint {
        fun syncScheduler(): SyncScheduler
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var syncScheduler: SyncScheduler? = null

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
        appScope.launch {
            runCatching {
                val plugins = EntryPointAccessors
                    .fromApplication(this@RizxApplication, PluginBootstrapEntryPoint::class.java)
                    .pluginRepository()
                // Before the reload: seeding installs *and* loads, so the reload loop then skips it by
                // `runtime.isLoaded` and the active-provider reconcile at the end still sees it.
                plugins.seedBundled()
                plugins.reloadInstalled()
            }
                // Swallowing this silently made a startup failure indistinguishable from "no plugins
                // installed" — the screen simply shows nothing and there is no thread to pull.
                .onFailure { android.util.Log.w("JsPlugin", "plugin bootstrap failed", it) }
            // Cloud sync watches the session from here on: signing in (or opening the app signed in)
            // starts it, edits keep it going, and a broken start is a logged line, never a crash.
            runCatching {
                EntryPointAccessors
                    .fromApplication(this@RizxApplication, SyncBootstrapEntryPoint::class.java)
                    .syncScheduler()
                    .also { syncScheduler = it }
                    .start(appScope)
            }.onFailure { android.util.Log.w("Sync", "sync bootstrap failed", it) }
        }
        registerActivityLifecycleCallbacks(ForegroundWatcher { syncScheduler?.onForeground() })
    }

    /**
     * Fires once when the app comes on screen — the first activity started while none was — so sync
     * can catch up after a quiet spell. Counts starts against stops, so rotating or moving between the
     * app's own screens never counts as "coming back".
     */
    private class ForegroundWatcher(private val onForeground: () -> Unit) : ActivityLifecycleCallbacks {
        private var started = 0
        override fun onActivityStarted(activity: Activity) {
            if (started++ == 0) onForeground()
        }
        override fun onActivityStopped(activity: Activity) {
            started = (started - 1).coerceAtLeast(0)
        }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
