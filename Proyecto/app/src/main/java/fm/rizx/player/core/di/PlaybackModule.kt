package fm.rizx.player.core.di

import android.content.Context
import androidx.media3.common.util.UnstableApi
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fm.rizx.player.core.network.NetworkMonitor
import fm.rizx.player.domain.playback.AudioEffectsController
import fm.rizx.player.domain.playback.PlaybackController
import fm.rizx.player.domain.repository.FavoritesRepository
import fm.rizx.player.domain.repository.RecentlyPlayedRepository
import fm.rizx.player.domain.repository.SettingsRepository
import fm.rizx.player.domain.usecase.StreamingResolver
import fm.rizx.player.playback.AudioEffects
import fm.rizx.player.playback.MediaControllerPlaybackController
import fm.rizx.player.playback.cache.AudioCache
import fm.rizx.player.playback.cache.CacheCompleter
import javax.inject.Singleton

/**
 * Binds the single process-wide [PlaybackController]. It drives the `PlaybackService`'s ExoPlayer via
 * a Media3 MediaController; UI observes it through ViewModels and never touches the player directly.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PlaybackModule {

    @Binds
    @Singleton
    abstract fun bindPlaybackController(impl: MediaControllerPlaybackController): PlaybackController

    @Binds
    @Singleton
    abstract fun bindAudioEffectsController(impl: AudioEffects): AudioEffectsController

    companion object {
        /**
         * Must be a singleton: Media3's `SimpleCache` refuses two instances over one directory, and both
         * the player and the Settings screen (size/clear) talk to this same object.
         */
        @Provides
        @Singleton
        @UnstableApi
        fun provideAudioCache(
            @ApplicationContext context: Context,
            settings: SettingsRepository,
            favorites: FavoritesRepository,
        ): AudioCache = AudioCache(context, settings, favorites)

        /** Lets a download of an already-heard song copy from the byte cache instead of the network. */
        @Provides
        @Singleton
        @UnstableApi
        fun provideCachedAudioReader(audioCache: AudioCache): fm.rizx.player.domain.repository.CachedAudioReader =
            fm.rizx.player.playback.cache.CachedAudioReaderImpl(audioCache)

        /** Finishes half-cached songs on Wi-Fi; driven by the playback service, which is already alive. */
        @Provides
        @Singleton
        @UnstableApi
        fun provideCacheCompleter(
            audioCache: AudioCache,
            resolver: StreamingResolver,
            recents: RecentlyPlayedRepository,
            favorites: FavoritesRepository,
            network: NetworkMonitor,
        ): CacheCompleter = CacheCompleter(audioCache, resolver, recents, favorites, network)
    }
}
