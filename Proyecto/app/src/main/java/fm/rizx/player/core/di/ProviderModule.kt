package fm.rizx.player.core.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fm.rizx.player.data.provider.DefaultProviderRegistry
import fm.rizx.player.data.provider.FakeMetadataProvider
import fm.rizx.player.data.provider.FakeMetadataProviderB
import fm.rizx.player.data.provider.FakeStreamingProvider
import fm.rizx.player.data.provider.FakeStreamingProviderB
import fm.rizx.player.data.provider.AudiusStreamingProvider
import fm.rizx.player.data.provider.DeezerDashboardProvider
import fm.rizx.player.data.provider.DeezerMetadataProvider
import fm.rizx.player.data.provider.DeezerPlaylistProvider
import fm.rizx.player.data.provider.ItunesMetadataProvider
import fm.rizx.player.data.provider.ItunesStreamingProvider
import fm.rizx.player.data.provider.LyricsOvhProvider
import fm.rizx.player.data.provider.RizxUrlPlaylistProvider
import fm.rizx.player.data.provider.SpotifyPlaylistProvider
import fm.rizx.player.data.provider.YoutubePlaylistProvider
import fm.rizx.player.data.provider.SoundcloudStreamingProvider
import fm.rizx.player.data.provider.YoutubeStreamingProvider
import fm.rizx.player.data.remote.soundcloud.SoundcloudExtractorClient
import fm.rizx.player.data.remote.audius.AudiusApi
import fm.rizx.player.data.remote.audius.AudiusHostProvider
import fm.rizx.player.data.remote.deezer.DeezerApi
import fm.rizx.player.data.remote.deezer.DeezerIds
import fm.rizx.player.data.remote.itunes.ItunesApi
import fm.rizx.player.data.remote.lyricsovh.LyricsOvhApi
import fm.rizx.player.core.network.NetworkMonitor
import fm.rizx.player.data.remote.youtube.YoutubeExtractorClient
import fm.rizx.player.data.remote.youtube.YoutubeIds
import fm.rizx.player.data.repository.DashboardRepositoryImpl
import fm.rizx.player.data.repository.LyricsRepositoryImpl
import fm.rizx.player.data.repository.MetadataRepositoryImpl
import fm.rizx.player.data.repository.StreamingRepositoryImpl
import fm.rizx.player.domain.model.PlaybackResolverSettings
import fm.rizx.player.domain.provider.EnabledProviderStore
import fm.rizx.player.domain.provider.ProviderKind
import fm.rizx.player.domain.provider.ProviderRegistry
import fm.rizx.player.domain.repository.DashboardRepository
import fm.rizx.player.domain.repository.LyricsRepository
import fm.rizx.player.domain.repository.MetadataRepository
import fm.rizx.player.domain.repository.SettingsRepository
import fm.rizx.player.domain.repository.StreamingRepository
import fm.rizx.player.domain.usecase.StreamingResolver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Singleton

/**
 * Wires the provider registry (pre-seeded with the development fakes) and the repositories that
 * dispatch through it. Real providers register here (or via a plugin runtime) in later phases.
 */
@Module
@InstallIn(SingletonComponent::class)
object ProviderModule {

    @Provides
    @Singleton
    fun provideProviderRegistry(
        settings: SettingsRepository,
        networkMonitor: NetworkMonitor,
        itunes: ItunesApi,
        lyricsOvh: LyricsOvhApi,
        audius: AudiusApi,
        audiusHosts: AudiusHostProvider,
        deezer: DeezerApi,
        youtube: YoutubeExtractorClient,
        soundcloud: SoundcloudExtractorClient,
        okHttp: okhttp3.OkHttpClient,
        json: kotlinx.serialization.json.Json,
    ): ProviderRegistry =
        DefaultProviderRegistry().apply {
            // Fakes register first, so a fresh install defaults to the offline demo data (first-wins).
            register(FakeMetadataProvider())
            register(FakeMetadataProviderB())
            register(FakeStreamingProvider())
            register(FakeStreamingProviderB())
            // Real metadata providers (Phase 13/17): selectable in Sources; require network.
            register(ItunesMetadataProvider(itunes))
            // Deezer (Phase 17): keyless metadata — unified search + album/artist detail (track lists).
            register(DeezerMetadataProvider(deezer))
            // Deezer charts (Phase 19): dashboard fan-out source for the real Home feed.
            register(DeezerDashboardProvider(deezer))
            // Streaming providers in fallback priority (StreamingRepositoryImpl chains active-first,
            // then registration order): YouTube full tracks → Audius full tracks → iTunes 30s preview.
            // ADR 0014: native full-length YouTube audio. Gets NetworkMonitor + settings so quality can
            // adapt (max by default; lower on data saver + cellular or a weak signal).
            register(YoutubeStreamingProvider(youtube, networkMonitor, settings))
            // Native SoundCloud (indie/emerging), also NewPipe. Below YouTube in the fallback chain, but a
            // SoundCloud-owned track resolves against it directly (StreamingRepositoryImpl native-owner path).
            register(SoundcloudStreamingProvider(soundcloud, networkMonitor, settings))
            register(AudiusStreamingProvider(audius, audiusHosts))
            register(ItunesStreamingProvider(itunes))
            // Lyrics provider (Phase 15): keyless lyrics.ovh; single-active for its kind.
            register(LyricsOvhProvider(lyricsOvh))
            // Playlist import-by-URL providers (Phase 22). Service-specific first; the file provider is
            // last because its canHandle is broad (any http URL ending .json/.csv, gist/pastebin/raw).
            register(DeezerPlaylistProvider(deezer))
            // Keyless: NewPipe for YouTube/YT-Music playlists, the public embed page for Spotify.
            register(YoutubePlaylistProvider(youtube))
            register(SpotifyPlaylistProvider(okHttp, json))
            register(RizxUrlPlaylistProvider(okHttp))
            // Restore the persisted active selection over first-wins (preserve-then-reconcile, §4):
            // apply a persisted id only when it is actually registered; otherwise pick a sensible real
            // default. Metadata defaults to Deezer and streaming to YouTube so a fresh install searches
            // and plays real music out of the box (the offline fakes are only for dev/no-network).
            runBlocking {
                // A persisted id may point at a plugin provider that hasn't been reloaded yet (plugins
                // load after this reconcile), so applying it can fail — fall back to a real default so a
                // fresh install always searches/plays real music. The user can re-select once loaded.
                val metaApplied = settings.activeMetadataProviderId.first()
                    ?.let { runCatching { setActive(ProviderKind.METADATA, it) }.isSuccess } ?: false
                if (!metaApplied) runCatching { setActive(ProviderKind.METADATA, DeezerIds.PROVIDER) }

                val streamApplied = settings.activeStreamingProviderId.first()
                    ?.let { runCatching { setActive(ProviderKind.STREAMING, it) }.isSuccess } ?: false
                if (!streamApplied) runCatching { setActive(ProviderKind.STREAMING, YoutubeIds.STREAMING) }
            }
        }

    @Provides
    @Singleton
    fun provideMetadataRepository(registry: ProviderRegistry): MetadataRepository =
        MetadataRepositoryImpl(registry)

    @Provides
    @Singleton
    fun provideStreamingRepository(registry: ProviderRegistry, enabled: EnabledProviderStore): StreamingRepository =
        StreamingRepositoryImpl(registry, enabled)

    @Provides
    @Singleton
    fun provideLyricsRepository(registry: ProviderRegistry): LyricsRepository =
        LyricsRepositoryImpl(registry)

    @Provides
    @Singleton
    fun provideDashboardRepository(registry: ProviderRegistry, enabled: EnabledProviderStore): DashboardRepository =
        DashboardRepositoryImpl(registry, enabled)

    @Provides
    @Singleton
    fun providePlaybackResolverSettings(): PlaybackResolverSettings = PlaybackResolverSettings()

    @Provides
    @Singleton
    fun provideStreamingResolver(
        streaming: StreamingRepository,
        settings: PlaybackResolverSettings,
    ): StreamingResolver = StreamingResolver(streaming, settings)
}
