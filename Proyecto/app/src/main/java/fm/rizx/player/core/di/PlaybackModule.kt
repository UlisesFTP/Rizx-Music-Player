package fm.rizx.player.core.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fm.rizx.player.domain.playback.AudioEffectsController
import fm.rizx.player.domain.playback.PlaybackController
import fm.rizx.player.playback.AudioEffects
import fm.rizx.player.playback.MediaControllerPlaybackController
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
}
