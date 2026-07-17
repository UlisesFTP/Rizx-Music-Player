package fm.rizx.player.core.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fm.rizx.player.data.repository.InMemoryQueueRepository
import fm.rizx.player.domain.repository.QueueRepository
import javax.inject.Singleton

/**
 * Binds the process-wide queue. A single [QueueRepository] instance holds the playback order shared
 * by the queue screen, the mini-player, and (later) the playback service. Room-backed persistence
 * replaces the in-memory store in a later phase without changing this binding's shape.
 */
@Module
@InstallIn(SingletonComponent::class)
object QueueModule {

    @Provides
    @Singleton
    fun provideQueueRepository(): QueueRepository = InMemoryQueueRepository()
}
