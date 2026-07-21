package fm.rizx.player.core.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fm.rizx.player.data.local.db.FavoriteDao
import fm.rizx.player.data.artwork.TrackArtworkEnricher
import fm.rizx.player.data.local.db.MIGRATION_1_2
import fm.rizx.player.data.local.db.MIGRATION_2_3
import fm.rizx.player.data.local.db.PlaylistDao
import fm.rizx.player.data.local.db.RecentlyPlayedDao
import fm.rizx.player.data.local.db.RizxDatabase
import fm.rizx.player.data.local.settings.EnabledProviderStoreImpl
import fm.rizx.player.data.local.settings.SettingsRepositoryImpl
import fm.rizx.player.data.repository.FavoritesRepositoryImpl
import fm.rizx.player.data.repository.PlaylistRepositoryImpl
import fm.rizx.player.data.repository.RecentlyPlayedRepositoryImpl
import fm.rizx.player.domain.provider.EnabledProviderStore
import fm.rizx.player.domain.provider.ProviderRegistry
import fm.rizx.player.domain.repository.FavoritesRepository
import fm.rizx.player.domain.repository.PlaylistRepository
import fm.rizx.player.domain.repository.RecentlyPlayedRepository
import fm.rizx.player.domain.repository.SettingsRepository
import fm.rizx.player.domain.usecase.ProviderHealthProbe
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Wires Room (favorites/playlists) and the Preferences DataStore (settings) plus their repositories. */
@Module
@InstallIn(SingletonComponent::class)
object PersistenceModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): RizxDatabase =
        Room.databaseBuilder(context, RizxDatabase::class.java, "rizx.db")
            // v2 adds recently_played; v3 adds playlists.artworkUrl. Both preserve existing data.
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()

    @Provides
    fun provideFavoriteDao(db: RizxDatabase): FavoriteDao = db.favoriteDao()

    @Provides
    fun providePlaylistDao(db: RizxDatabase): PlaylistDao = db.playlistDao()

    @Provides
    fun provideRecentlyPlayedDao(db: RizxDatabase): RecentlyPlayedDao = db.recentlyPlayedDao()

    @Provides
    @Singleton
    fun provideFavoritesRepository(dao: FavoriteDao): FavoritesRepository = FavoritesRepositoryImpl(dao)

    /** Fills in covers an import didn't supply (notably Spotify, whose tracklist carries no images). */
    @Provides
    @Singleton
    fun provideTrackArtworkEnricher(registry: ProviderRegistry): TrackArtworkEnricher =
        TrackArtworkEnricher(registry)

    @Provides
    @Singleton
    fun providePlaylistRepository(
        dao: PlaylistDao,
        registry: ProviderRegistry,
        enabled: EnabledProviderStore,
        artwork: TrackArtworkEnricher,
    ): PlaylistRepository = PlaylistRepositoryImpl(dao, registry, enabled, artwork)

    @Provides
    @Singleton
    fun provideRecentlyPlayedRepository(dao: RecentlyPlayedDao): RecentlyPlayedRepository =
        RecentlyPlayedRepositoryImpl(dao)

    @Provides
    @Singleton
    fun provideSettingsRepository(@ApplicationContext context: Context): SettingsRepository =
        SettingsRepositoryImpl(context.settingsDataStore)

    @Provides
    @Singleton
    fun provideEnabledProviderStore(@ApplicationContext context: Context): EnabledProviderStore =
        EnabledProviderStoreImpl(context.settingsDataStore)

    @Provides
    @Singleton
    fun provideProviderHealthProbe(): ProviderHealthProbe = ProviderHealthProbe()
}
