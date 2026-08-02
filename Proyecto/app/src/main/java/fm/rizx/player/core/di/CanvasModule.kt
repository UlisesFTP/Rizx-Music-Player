package fm.rizx.player.core.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fm.rizx.player.core.network.DataSaverState
import fm.rizx.player.data.canvas.AppleMotionArtworkProvider
import fm.rizx.player.data.canvas.CanvasPolicy
import fm.rizx.player.data.canvas.CanvasProviderRegistry
import fm.rizx.player.data.canvas.CanvasResolutionCache
import fm.rizx.player.data.canvas.YoutubeCanvasProvider
import fm.rizx.player.data.remote.itunes.ItunesApi
import fm.rizx.player.data.remote.youtube.YoutubeExtractorClient
import fm.rizx.player.data.repository.CanvasRepositoryImpl
import fm.rizx.player.domain.repository.CanvasRepository
import fm.rizx.player.domain.repository.SettingsRepository
import okhttp3.OkHttpClient
import javax.inject.Singleton

/**
 * The animated cover art ("canvas") stack — see `docs/adr/0017-canvas-provider-and-network-policy.md`.
 *
 * Everything here is a `@Singleton` because the resolution cache has to be: the whole point of caching an
 * answer is that re-opening Now Playing, or coming back to a song, doesn't re-extract it. A
 * `@ViewModelScoped` cache would be thrown away every time you left the screen, which is precisely when
 * it's about to be useful.
 */
@Module
@InstallIn(SingletonComponent::class)
object CanvasModule {

    /**
     * The providers, in priority order.
     *
     * **Apple first, and it is the one that actually animates.** Apple publishes purpose-made motion
     * album artwork — a short silent loop, square and portrait, keyless and DRM-free on the album page.
     * YouTube can only offer the *music video*, and for the auto-generated "topic" uploads that make up
     * most of the catalogue that video is a still image, which is why nothing appeared to move before.
     *
     * Spotify's and Tidal's canvas endpoints still need an account token, so they are not here — not out
     * of caution, but because there is no keyless way in.
     */
    @Provides
    @Singleton
    fun provideCanvasProviderRegistry(
        youtube: YoutubeExtractorClient,
        itunes: ItunesApi,
        client: OkHttpClient,
    ): CanvasProviderRegistry = CanvasProviderRegistry(
        listOf(
            AppleMotionArtworkProvider(itunes, client),
            YoutubeCanvasProvider(youtube),
        ),
    )

    @Provides
    @Singleton
    fun provideCanvasResolutionCache(): CanvasResolutionCache = CanvasResolutionCache()

    @Provides
    @Singleton
    fun provideCanvasRepository(
        registry: CanvasProviderRegistry,
        cache: CanvasResolutionCache,
        policy: CanvasPolicy,
        settings: SettingsRepository,
        dataSaver: DataSaverState,
    ): CanvasRepository = CanvasRepositoryImpl(registry, cache, policy, settings, dataSaver = dataSaver)
}
