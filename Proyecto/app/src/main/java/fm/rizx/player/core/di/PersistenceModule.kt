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
import fm.rizx.player.data.local.db.MIGRATION_3_4
import fm.rizx.player.data.local.db.MIGRATION_4_5
import fm.rizx.player.data.local.db.PlaylistDao
import fm.rizx.player.data.local.db.RecentlyPlayedDao
import fm.rizx.player.data.local.db.RecognitionHistoryDao
import fm.rizx.player.data.local.db.RizxDatabase
import fm.rizx.player.data.local.settings.EnabledProviderStoreImpl
import fm.rizx.player.data.local.settings.SettingsRepositoryImpl
import fm.rizx.player.data.artwork.ArtworkCache
import fm.rizx.player.data.genre.TrackGenreResolver
import fm.rizx.player.data.remote.itunes.ItunesApi
import fm.rizx.player.data.artist.ArtistBioSource
import fm.rizx.player.data.artist.WikipediaArtistBioSource
import fm.rizx.player.data.local.store.ArtistBioStore
import fm.rizx.player.data.local.store.AutoEqStore
import fm.rizx.player.data.remote.wikipedia.WikipediaApi
import fm.rizx.player.data.local.store.HomeFeedStore
import fm.rizx.player.data.local.store.LyricsStore
import fm.rizx.player.data.local.store.SearchHistoryStore
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
import java.io.File
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
            // v2 adds recently_played; v3 adds playlists.artworkUrl; v4 turns the history into a
            // listening log (play/skip counts, time of day); v5 adds recognition_history. All of them
            // preserve existing data.
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .build()

    @Provides
    fun provideFavoriteDao(db: RizxDatabase): FavoriteDao = db.favoriteDao()

    @Provides
    fun providePlaylistDao(db: RizxDatabase): PlaylistDao = db.playlistDao()

    @Provides
    fun provideRecentlyPlayedDao(db: RizxDatabase): RecentlyPlayedDao = db.recentlyPlayedDao()

    @Provides
    fun provideRecognitionHistoryDao(db: RizxDatabase): RecognitionHistoryDao = db.recognitionHistoryDao()

    @Provides
    @Singleton
    fun provideFavoritesRepository(dao: FavoriteDao): FavoritesRepository = FavoritesRepositoryImpl(dao)

    /** Fills in covers an import didn't supply (notably Spotify, whose tracklist carries no images). */
    @Provides
    @Singleton
    fun provideTrackArtworkEnricher(
        registry: ProviderRegistry,
        @ApplicationContext context: Context,
    ): TrackArtworkEnricher = TrackArtworkEnricher(
        registry = registry,
        cache = ArtworkCache(File(context.filesDir, "artwork_cache.json")),
    )

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

    /**
     * Cached lyrics. A plain JSON file rather than a Room table: it is a lookup by track identity with no
     * queries, no relations and no migrations worth owning — the same call the download index makes.
     */
    @Provides
    @Singleton
    fun provideLyricsStore(@ApplicationContext context: Context): LyricsStore =
        LyricsStore(File(context.filesDir, "lyrics.json"))

    /**
     * The last Home the user saw, so the next launch renders instantly and refreshes behind the content
     * instead of showing a spinner through ~70 network round-trips.
     */
    @Provides
    @Singleton
    fun provideHomeFeedStore(@ApplicationContext context: Context): HomeFeedStore =
        HomeFeedStore(File(context.filesDir, "home_feed.json"))

    /**
     * The searches the user actually made, behind Search's pills and its "recent" suggestion rows.
     * Singleton because the flow is hot — a search recorded on the Search screen must be visible to
     * whatever else is reading it without a re-read.
     */
    @Provides
    @Singleton
    fun provideSearchHistoryStore(@ApplicationContext context: Context): SearchHistoryStore =
        SearchHistoryStore(File(context.filesDir, "search_history.json"))

    /**
     * The curve the automatic equalizer worked out per song. Cached because the first play pays for a
     * genre lookup *and* twelve seconds of listening, and neither answer ever changes for that recording.
     */
    @Provides
    @Singleton
    fun provideAutoEqStore(@ApplicationContext context: Context): AutoEqStore =
        AutoEqStore(File(context.filesDir, "auto_eq.json"))

    /**
     * Artist biographies. Cached to disk because an artist's life story does not change between two
     * visits to their page — and because a *missing* one costs the same two requests to rediscover.
     */
    @Provides
    @Singleton
    fun provideArtistBioStore(@ApplicationContext context: Context): ArtistBioStore =
        ArtistBioStore(File(context.filesDir, "artist_bios.json"))

    @Provides
    @Singleton
    fun provideArtistBioSource(api: WikipediaApi, store: ArtistBioStore): ArtistBioSource =
        WikipediaArtistBioSource(api, store)

    /** Answers "what genre is this song?" for the automatic equalizer — owner first, verified, guarded. */
    @Provides
    @Singleton
    fun provideTrackGenreResolver(registry: ProviderRegistry, itunes: ItunesApi): TrackGenreResolver =
        TrackGenreResolver(registry, itunes)

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
