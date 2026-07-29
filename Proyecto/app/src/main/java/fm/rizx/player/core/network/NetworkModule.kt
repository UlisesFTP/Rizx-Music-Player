package fm.rizx.player.core.network

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fm.rizx.player.BuildConfig
import fm.rizx.player.data.remote.applemusic.AppleMusicRssApi
import fm.rizx.player.data.remote.audius.AudiusApi
import fm.rizx.player.data.remote.audius.AudiusHostProvider
import fm.rizx.player.data.remote.deezer.DeezerApi
import fm.rizx.player.data.remote.itunes.ItunesApi
import fm.rizx.player.data.remote.kugou.KugouApi
import fm.rizx.player.data.remote.lrclib.LrcLibApi
import fm.rizx.player.data.remote.musixmatch.MusixmatchClient
import fm.rizx.player.data.remote.netease.NeteaseApi
import fm.rizx.player.data.remote.lyricsovh.LyricsOvhApi
import fm.rizx.player.data.provider.AppleMusicMetadataProvider
import fm.rizx.player.data.provider.MetadataRadioMixSource
import fm.rizx.player.data.provider.SoundcloudMetadataProvider
import fm.rizx.player.data.remote.soundcloud.NewPipeSoundcloudExtractorClient
import fm.rizx.player.data.remote.soundcloud.SoundcloudExtractorClient
import fm.rizx.player.domain.model.RadioMode
import fm.rizx.player.domain.provider.RadioMixSource
import fm.rizx.player.data.search.DefaultPlaylistSourcesSearch
import fm.rizx.player.data.search.NewPipeStreamingSourcesSearch
import fm.rizx.player.data.search.PlaylistSourcesSearch
import fm.rizx.player.data.search.StreamingSourcesSearch
import fm.rizx.player.data.remote.youtube.NewPipeDownloaderImpl
import fm.rizx.player.data.remote.youtube.NewPipeYoutubeExtractorClient
import fm.rizx.player.data.remote.youtube.YoutubeExtractorClient
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Shared HTTP stack for real providers (ADR 0006). Provides the app-wide lenient [Json], an
 * [OkHttpClient] with mandatory timeouts + a descriptive User-Agent (and body logging in debug), and
 * the iTunes [Retrofit] service. No secrets are embedded — the iTunes Search API is public.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private const val ITUNES_BASE_URL = "https://itunes.apple.com/"
    private const val APPLE_RSS_BASE_URL = "https://rss.marketingtools.apple.com/"
    private const val NETEASE_BASE_URL = "https://music.163.com/"
    private const val KUGOU_LYRICS_BASE_URL = "https://lyrics.kugou.com/"
    private const val LYRICS_OVH_BASE_URL = "https://api.lyrics.ovh/"
    private const val LRCLIB_BASE_URL = "https://lrclib.net/"
    private const val AUDIUS_BASE_URL = "https://api.audius.co/"
    private const val DEEZER_BASE_URL = "https://api.deezer.com/"
    private const val USER_AGENT = "RizxPlayer/0.1 (+https://github.com/nukeop/nuclear)"
    private const val HTTP_CACHE_DIR = "http"
    private const val HTTP_CACHE_BYTES = 20L * 1024 * 1024

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    /**
     * The shared client. Beyond timeouts and the User-Agent it now carries a disk cache: the catalogue
     * endpoints behind the Home feed and the cover-art enricher are keyless and idempotent, but answer
     * `no-cache`, so nothing was ever stored and every launch repeated the same tens of requests. The
     * two interceptors in `HttpCaching.kt` make those responses cacheable and fall back to the cache
     * when the network is unreachable; everything else (notably NewPipe's token-bearing calls) is left
     * exactly as it was.
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(@ApplicationContext context: Context): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
        }
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .cache(Cache(File(context.cacheDir, HTTP_CACHE_DIR), HTTP_CACHE_BYTES))
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
            }
            .addInterceptor(OfflineCacheFallbackInterceptor())
            .addNetworkInterceptor(CatalogueCacheControlInterceptor())
            .addInterceptor(logging)
            .build()
    }

    /** Per-country most-played charts (keyless RSS) for the regional Home rows. */
    @Provides
    @Singleton
    fun provideAppleMusicRssApi(client: OkHttpClient, json: Json): AppleMusicRssApi =
        Retrofit.Builder()
            .baseUrl(APPLE_RSS_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(AppleMusicRssApi::class.java)

    @Provides
    @Singleton
    fun provideItunesApi(client: OkHttpClient, json: Json): ItunesApi =
        Retrofit.Builder()
            .baseUrl(ITUNES_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ItunesApi::class.java)

    @Provides
    @Singleton
    fun provideLyricsOvhApi(client: OkHttpClient, json: Json): LyricsOvhApi =
        Retrofit.Builder()
            .baseUrl(LYRICS_OVH_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(LyricsOvhApi::class.java)

    /** Word-by-word (`yrc`) lyrics — keyless public read endpoints. */
    @Provides
    @Singleton
    fun provideNeteaseApi(client: OkHttpClient, json: Json): NeteaseApi =
        Retrofit.Builder()
            .baseUrl(NETEASE_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(NeteaseApi::class.java)

    /** Word-by-word (`krc`) lyrics — keyless. */
    @Provides
    @Singleton
    fun provideKugouApi(client: OkHttpClient, json: Json): KugouApi =
        Retrofit.Builder()
            .baseUrl(KUGOU_LYRICS_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(KugouApi::class.java)

    /**
     * Musixmatch richsync. Not Retrofit: it signs each call with a secret recovered from the site's own
     * JavaScript at runtime, so it drives OkHttp directly (same shape as the Spotify embed scrape).
     */
    @Provides
    @Singleton
    fun provideMusixmatchClient(client: OkHttpClient, json: Json): MusixmatchClient =
        MusixmatchClient(client, json)

    /** Timed (LRC) lyrics — keyless. The descriptive User-Agent LRCLIB asks for is set above. */
    @Provides
    @Singleton
    fun provideLrcLibApi(client: OkHttpClient, json: Json): LrcLibApi =
        Retrofit.Builder()
            .baseUrl(LRCLIB_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(LrcLibApi::class.java)

    @Provides
    @Singleton
    fun provideAudiusApi(client: OkHttpClient, json: Json): AudiusApi =
        // Base URL is a placeholder — every Audius call passes an absolute @Url (host is dynamic).
        Retrofit.Builder()
            .baseUrl(AUDIUS_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(AudiusApi::class.java)

    @Provides
    @Singleton
    fun provideAudiusHostProvider(api: AudiusApi): AudiusHostProvider = AudiusHostProvider(api)

    @Provides
    @Singleton
    fun provideDeezerApi(client: OkHttpClient, json: Json): DeezerApi =
        Retrofit.Builder()
            .baseUrl(DEEZER_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(DeezerApi::class.java)

    // YouTube (ADR 0014): NewPipeExtractor over the shared OkHttp client for full-length audio.
    @Provides
    @Singleton
    fun provideNewPipeDownloader(client: OkHttpClient): NewPipeDownloaderImpl =
        NewPipeDownloaderImpl(client)

    @Provides
    @Singleton
    fun provideYoutubeExtractorClient(downloader: NewPipeDownloaderImpl): YoutubeExtractorClient =
        NewPipeYoutubeExtractorClient(downloader)

    // RadioMode.YOUTUBE refills: the YT Mix (YT Music's own autoplay) as a domain-facing radio source.
    // The enricher swaps each mix row's video thumbnail for the real Deezer cover.
    @Provides
    @Singleton
    fun provideRadioMixSource(
        youtube: YoutubeExtractorClient,
        artwork: fm.rizx.player.data.artwork.TrackArtworkEnricher,
    ): RadioMixSource = fm.rizx.player.data.remote.youtube.YoutubeMixSource(youtube, artwork)

    /**
     * Every non-Deezer "up next" engine, keyed by the mode that selects it. A map rather than more
     * qualified bindings so [fm.rizx.player.playback.service.PlaybackService] picks an engine by
     * lookup instead of a `when` that must be edited for each new one — and so a mode with no engine
     * simply falls through to the artist radio.
     *
     * [fm.rizx.player.domain.model.RadioMode.ARTIST] is deliberately absent: it is the fallback the
     * others degrade to, served by the active metadata provider rather than a mix source.
     */
    @Provides
    @Singleton
    fun provideRadioMixSources(
        youtubeMix: RadioMixSource,
        itunes: ItunesApi,
        soundcloud: SoundcloudExtractorClient,
    ): Map<RadioMode, @JvmSuppressWildcards RadioMixSource> = mapOf(
        RadioMode.YOUTUBE to youtubeMix,
        // Their own instances rather than the registered providers: the up-next engine is the user's
        // choice independently of which catalogue Search uses, so disabling one must not mute the other.
        RadioMode.APPLEMUSIC to MetadataRadioMixSource(AppleMusicMetadataProvider(itunes)),
        RadioMode.SOUNDCLOUD to MetadataRadioMixSource(SoundcloudMetadataProvider(soundcloud)),
    )

    // SoundCloud (indie/emerging): the same NewPipe engine + shared downloader, pointed at ServiceList.SoundCloud.
    @Provides
    @Singleton
    fun provideSoundcloudExtractorClient(downloader: NewPipeDownloaderImpl): SoundcloudExtractorClient =
        NewPipeSoundcloudExtractorClient(downloader)

    // The Search "Underground" tab: YouTube + SoundCloud in parallel via NewPipe.
    @Provides
    @Singleton
    fun provideStreamingSourcesSearch(
        youtube: YoutubeExtractorClient,
        soundcloud: SoundcloudExtractorClient,
    ): StreamingSourcesSearch = NewPipeStreamingSourcesSearch(youtube, soundcloud)

    // The Search "Playlists" tab: Deezer + YouTube playlists in parallel (keyless; Spotify excluded).
    @Provides
    @Singleton
    fun providePlaylistSourcesSearch(
        deezerApi: DeezerApi,
        youtube: YoutubeExtractorClient,
    ): PlaylistSourcesSearch = DefaultPlaylistSourcesSearch(deezerApi, youtube)
}
