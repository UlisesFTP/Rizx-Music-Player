package fm.rizx.player.core.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fm.rizx.player.core.region.RegionResolver
import fm.rizx.player.data.provider.AppleMusicDashboardProvider
import fm.rizx.player.data.provider.AppleMusicMetadataProvider
import fm.rizx.player.data.provider.AppleMusicPlaylistProvider
import fm.rizx.player.data.provider.DefaultProviderRegistry
import fm.rizx.player.data.provider.FakeStreamingProvider
import fm.rizx.player.data.provider.FakeStreamingProviderB
import fm.rizx.player.data.provider.AudiusStreamingProvider
import fm.rizx.player.data.provider.DeezerDashboardProvider
import fm.rizx.player.data.provider.DeezerMetadataProvider
import fm.rizx.player.data.provider.DeezerPlaylistProvider
import fm.rizx.player.data.provider.ItunesMetadataProvider
import fm.rizx.player.data.provider.ItunesStreamingProvider
import fm.rizx.player.data.provider.KugouLyricsProvider
import fm.rizx.player.data.provider.LrcLibProvider
import fm.rizx.player.data.provider.LyricsOvhProvider
import fm.rizx.player.data.provider.MusixmatchLyricsProvider
import fm.rizx.player.data.provider.NeteaseLyricsProvider
import fm.rizx.player.data.provider.RizxUrlPlaylistProvider
import fm.rizx.player.data.provider.SpotifyChartsDashboardProvider
import fm.rizx.player.data.provider.SpotifyPlaylistProvider
import fm.rizx.player.data.provider.YoutubePlaylistProvider
import fm.rizx.player.data.provider.SoundcloudChartsDashboardProvider
import fm.rizx.player.data.provider.SoundcloudMetadataProvider
import fm.rizx.player.data.provider.SoundcloudStreamingProvider
import fm.rizx.player.data.provider.YoutubeStreamingProvider
import fm.rizx.player.data.remote.applemusic.AppleMusicRssApi
import fm.rizx.player.data.remote.soundcloud.SoundcloudExtractorClient
import fm.rizx.player.data.remote.audius.AudiusApi
import fm.rizx.player.data.remote.audius.AudiusHostProvider
import fm.rizx.player.data.remote.deezer.DeezerApi
import fm.rizx.player.data.remote.deezer.DeezerIds
import fm.rizx.player.data.remote.itunes.ItunesApi
import fm.rizx.player.data.remote.lrclib.LrcLibApi
import fm.rizx.player.data.remote.lyricsovh.LyricsOvhApi
import fm.rizx.player.core.network.DataSaverState
import fm.rizx.player.core.network.NetworkMonitor
import fm.rizx.player.data.local.store.LyricsStore
import fm.rizx.player.data.remote.youtube.YoutubeExtractorClient
import fm.rizx.player.data.remote.youtube.YoutubeIds
import fm.rizx.player.data.repository.BlendingDashboardRepository
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
        dataSaver: DataSaverState,
        itunes: ItunesApi,
        lrcLib: LrcLibApi,
        lyricsOvh: LyricsOvhApi,
        audius: AudiusApi,
        audiusHosts: AudiusHostProvider,
        deezer: DeezerApi,
        deezerArtists: fm.rizx.player.data.remote.deezer.DeezerArtistSearch,
        netease: fm.rizx.player.data.remote.netease.NeteaseApi,
        kugou: fm.rizx.player.data.remote.kugou.KugouApi,
        musixmatch: fm.rizx.player.data.remote.musixmatch.MusixmatchClient,
        youtube: YoutubeExtractorClient,
        soundcloud: SoundcloudExtractorClient,
        okHttp: okhttp3.OkHttpClient,
        json: kotlinx.serialization.json.Json,
        appleRss: AppleMusicRssApi,
        region: RegionResolver,
    ): ProviderRegistry {
        // One shared instance: registered as the URL-import provider and reused by the charts dashboard.
        val spotifyPlaylists = SpotifyPlaylistProvider(okHttp, json)
        return DefaultProviderRegistry().apply {
            // The two fake *metadata* providers are gone: they were Phase-1 scaffolding that stayed
            // registered, and each carried an artificial `delay()`. Since `TrackArtworkEnricher` walks
            // every metadata provider in turn, every cover Deezer could not match paid 430 ms of pure
            // simulated latency — ~60 times per Home load. They also cluttered Sources and Search.
            // (The streaming fakes below still back the no-network dev path.)
            register(FakeStreamingProvider())
            register(FakeStreamingProviderB())
            // Real metadata providers (Phase 13/17): selectable in Sources; require network.
            register(ItunesMetadataProvider(itunes))
            // Deezer (Phase 17): keyless metadata — unified search + album/artist detail (track lists).
            register(DeezerMetadataProvider(deezer, artists = deezerArtists))
            // Apple Music and SoundCloud as selectable catalogues (ADR 0018): Search's Songs/Artists/
            // Albums tabs follow whichever metadata provider is active, so these cost nothing until
            // chosen. Both are keyless — Apple over the public iTunes Search API, SoundCloud over
            // NewPipe — and both are metadata-only; audio still comes from the streaming chain.
            val appleCatalogue = AppleMusicMetadataProvider(itunes)
            val applePlaylistPage = fm.rizx.player.data.remote.applemusic.AppleMusicPlaylistPage(okHttp, json)
            val appleBrowse = fm.rizx.player.data.remote.applemusic.AppleMusicBrowsePage(okHttp)
            register(appleCatalogue)
            register(SoundcloudMetadataProvider(soundcloud))
            // Deezer charts (Phase 19): dashboard fan-out source for the real Home feed.
            register(DeezerDashboardProvider(deezer))
            // Regional/global charts (recs): Spotify Top 50 / Viral 50 via the keyless embed page and
            // Apple Music most-played via the keyless RSS. Anything regional is consent-gated; both get
            // their own enable toggle in Plugins (dashboard = fan-out kind), and the blending decorator
            // dedups their overlap with Deezer keeping Deezer's copy.
            register(SpotifyChartsDashboardProvider(spotifyPlaylists, region, settings))
            // Apple contributes the most here: its 20+ "Top 100" country charts are discovered from
            // the browse page (one hourly request) alongside the RSS's curated rows.
            register(AppleMusicDashboardProvider(appleRss, region, settings, browse = appleBrowse))
            // SoundCloud's own chart kiosks — the fourth feed source, so "SoundCloud only" is a real
            // choice in the feed selector rather than an empty screen.
            register(SoundcloudChartsDashboardProvider(soundcloud))
            // Streaming providers in fallback priority (StreamingRepositoryImpl chains active-first,
            // then registration order): YouTube full tracks → Audius full tracks → iTunes 30s preview.
            // ADR 0014: native full-length YouTube audio. Gets NetworkMonitor + DataSaverState so quality
            // can adapt (max by default; lower whenever the user asked to save data, or on a weak link).
            register(YoutubeStreamingProvider(youtube, networkMonitor, dataSaver))
            // Native SoundCloud (indie/emerging), also NewPipe. Below YouTube in the fallback chain, but a
            // SoundCloud-owned track resolves against it directly (StreamingRepositoryImpl native-owner path).
            register(SoundcloudStreamingProvider(soundcloud, networkMonitor, dataSaver))
            register(AudiusStreamingProvider(audius, audiusHosts))
            register(ItunesStreamingProvider(itunes))
            // Lyrics providers, in fallback order — and order is priority, because activation is
            // first-wins and `LyricsRepositoryImpl` walks the chain from the active one.
            // The three karaoke sources come first (word-by-word timings, which the screen can light up
            // progressively), then LRCLIB for line timings, then lyrics.ovh as the prose floor.
            // NetEase leads: keyless, quick, and the most reliable of the word-timed three.
            register(NeteaseLyricsProvider(netease))
            register(KugouLyricsProvider(kugou))
            register(MusixmatchLyricsProvider(musixmatch))
            register(LrcLibProvider(lrcLib))
            register(LyricsOvhProvider(lyricsOvh))
            // Playlist import-by-URL providers (Phase 22). Service-specific first; the file provider is
            // last because its canHandle is broad (any http URL ending .json/.csv, gist/pastebin/raw).
            register(DeezerPlaylistProvider(deezer))
            // Keyless: NewPipe for YouTube/YT-Music playlists, the public embed page for Spotify.
            register(YoutubePlaylistProvider(youtube))
            register(spotifyPlaylists)
            // Apple's editorial playlists. Registered as a real PlaylistProvider so the cards the
            // dashboard emits can actually be opened — a card that opens empty is worse than absent.
            register(AppleMusicPlaylistProvider(applePlaylistPage, appleCatalogue))
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

                // Same preserve-then-reconcile for lyrics, now that there is a real choice to make.
                val lyricsApplied = settings.activeLyricsProviderId.first()
                    ?.let { runCatching { setActive(ProviderKind.LYRICS, it) }.isSuccess } ?: false
                if (!lyricsApplied) runCatching { setActive(ProviderKind.LYRICS, NeteaseLyricsProvider.ID) }
            }
        }
    }

    // Country for regional recommendations only (SIM → network → locale; no permission, no location).
    @Provides
    @Singleton
    fun provideRegionResolver(@ApplicationContext context: Context): RegionResolver =
        RegionResolver.fromContext(context)

    @Provides
    @Singleton
    fun provideMetadataRepository(registry: ProviderRegistry): MetadataRepository =
        MetadataRepositoryImpl(registry)

    /**
     * Turns a song's billing line into artist pages the active provider can actually open — one per
     * artist, each the most complete profile of that name rather than whichever row ranked first.
     * Singleton so its memo is shared.
     */
    @Provides
    @Singleton
    fun provideResolveTrackArtists(
        metadata: MetadataRepository,
        registry: ProviderRegistry,
    ): fm.rizx.player.domain.usecase.ResolveTrackArtistsUseCase =
        fm.rizx.player.domain.usecase.ResolveTrackArtistsUseCase(metadata, registry)

    @Provides
    @Singleton
    fun provideStreamingRepository(registry: ProviderRegistry, enabled: EnabledProviderStore): StreamingRepository =
        StreamingRepositoryImpl(registry, enabled)

    @Provides
    @Singleton
    fun provideLyricsRepository(registry: ProviderRegistry, store: LyricsStore): LyricsRepository =
        LyricsRepositoryImpl(registry, store)

    // Personalized "For you" rows + the regional-consent surface, as one seam for the Home ViewModel.
    @Provides
    @Singleton
    fun provideForYouRepository(
        favorites: fm.rizx.player.domain.repository.FavoritesRepository,
        recents: fm.rizx.player.domain.repository.RecentlyPlayedRepository,
        mix: fm.rizx.player.domain.provider.RadioMixSource,
        deezer: DeezerApi,
        settings: SettingsRepository,
        region: RegionResolver,
        deezerArtists: fm.rizx.player.data.remote.deezer.DeezerArtistSearch,
    ): fm.rizx.player.domain.repository.ForYouRepository =
        fm.rizx.player.data.repository.ForYouRepositoryImpl(
            favorites, recents, mix, deezer, settings, region, artists = deezerArtists,
        )

    /**
     * One instance app-wide: the Search screen, the player's artist link and the radio's id lookup all
     * ask Deezer the same question within milliseconds of a song starting, and this is what makes that
     * a single request.
     */
    @Provides
    @Singleton
    fun provideDeezerArtistSearch(deezer: DeezerApi): fm.rizx.player.data.remote.deezer.DeezerArtistSearch =
        fm.rizx.player.data.remote.deezer.DeezerArtistSearch(deezer)

    // Fan-out, then blend: dedup (Deezer's copy wins) + weighted interleave + borrowed covers.
    // The feed selection is read per call (a suspend lambda, not a snapshot) so switching platforms in
    // Settings takes effect on the very next load without rebuilding the graph.
    @Provides
    @Singleton
    fun provideDashboardRepository(
        registry: ProviderRegistry,
        enabled: EnabledProviderStore,
        artwork: fm.rizx.player.data.artwork.TrackArtworkEnricher,
        settings: SettingsRepository,
    ): DashboardRepository = BlendingDashboardRepository(
        inner = DashboardRepositoryImpl(registry, enabled, selection = { settings.feedProvider.first() }),
        blender = fm.rizx.player.domain.usecase.RecsBlender(),
        artwork = artwork,
    )

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
