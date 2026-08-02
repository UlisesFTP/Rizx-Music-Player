package fm.rizx.player.core.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fm.rizx.player.core.network.DataSaverState
import fm.rizx.player.core.network.NetworkMonitor
import fm.rizx.player.data.lossless.DefaultLosslessMatcher
import fm.rizx.player.data.lossless.DefaultLosslessResolver
import fm.rizx.player.data.lossless.LosslessResolutionCache
import fm.rizx.player.data.lossless.PluginLosslessIndexSource
import fm.rizx.player.data.lossless.RemoteFlacInspector
import fm.rizx.player.domain.lossless.FlacInspector
import fm.rizx.player.domain.lossless.LosslessIndexSource
import fm.rizx.player.domain.lossless.LosslessMatcher
import fm.rizx.player.domain.lossless.LosslessResolver
import fm.rizx.player.domain.provider.ProviderRegistry
import fm.rizx.player.domain.repository.SettingsRepository
import okhttp3.OkHttpClient
import javax.inject.Singleton

/**
 * The optional community-lossless stack — see `docs/adr/0024-community-lossless-source.md`.
 *
 * **There is no endpoint in here, and that is the point.** Every other network module in this app names
 * the service it talks to; this one names none, because the list of files lives in a plugin the user
 * installs. What Rizx ships is the part that can be reasoned about and tested: strict matching, a real
 * FLAC header check, a bounded cache, and a fallback that makes a miss invisible.
 *
 * Singletons throughout, for the cache's sake: the whole value of remembering a verdict is that coming
 * back to a song doesn't re-verify it, and a shorter-lived cache would be discarded exactly when it was
 * about to pay off. The single-flight set lives there too, so it has to be shared to work at all.
 */
@Module
@InstallIn(SingletonComponent::class)
object LosslessModule {

    /**
     * The index, read from whichever installed plugins publish one.
     *
     * Takes the registry rather than a list of providers because plugins come and go while the app is
     * running — installing one has to light the feature up without a restart.
     */
    @Provides
    @Singleton
    fun provideLosslessIndexSource(registry: ProviderRegistry): LosslessIndexSource =
        PluginLosslessIndexSource(registry)

    @Provides
    @Singleton
    fun provideLosslessMatcher(): LosslessMatcher = DefaultLosslessMatcher()

    /**
     * Derived from the shared client rather than built fresh: same connection pool and TLS session
     * cache, but with redirects unfollowed, short timeouts, and a DNS hook that refuses private
     * addresses. Those three must not leak back into the app's ordinary traffic.
     */
    @Provides
    @Singleton
    fun provideFlacInspector(client: OkHttpClient): FlacInspector = RemoteFlacInspector(client)

    @Provides
    @Singleton
    fun provideLosslessResolutionCache(): LosslessResolutionCache = LosslessResolutionCache()

    @Provides
    @Singleton
    fun provideLosslessResolver(
        settings: SettingsRepository,
        source: LosslessIndexSource,
        matcher: LosslessMatcher,
        inspector: FlacInspector,
        cache: LosslessResolutionCache,
        network: NetworkMonitor,
        dataSaver: DataSaverState,
    ): LosslessResolver =
        DefaultLosslessResolver(settings, source, matcher, inspector, cache, network, dataSaver)
}
