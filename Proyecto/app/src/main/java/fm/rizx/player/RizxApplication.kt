package fm.rizx.player

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
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

@HiltAndroidApp
class RizxApplication : Application(), ImageLoaderFactory {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface PluginBootstrapEntryPoint {
        fun pluginRepository(): PluginRepository
    }

    // App-wide Coil loader: crossfade for cover art.
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this).crossfade(true).build()

    override fun onCreate() {
        super.onCreate()
        // Reload installed JS plugins (ADR 0014) off the main thread — each is isolated, so a broken
        // plugin can never block startup or the others.
        val repo = EntryPointAccessors.fromApplication(this, PluginBootstrapEntryPoint::class.java).pluginRepository()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            runCatching { repo.reloadInstalled() }
        }
    }
}
