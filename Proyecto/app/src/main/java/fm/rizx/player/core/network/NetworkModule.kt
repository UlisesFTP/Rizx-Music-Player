package fm.rizx.player.core.network

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fm.rizx.player.BuildConfig
import fm.rizx.player.data.remote.audius.AudiusApi
import fm.rizx.player.data.remote.audius.AudiusHostProvider
import fm.rizx.player.data.remote.deezer.DeezerApi
import fm.rizx.player.data.remote.itunes.ItunesApi
import fm.rizx.player.data.remote.lyricsovh.LyricsOvhApi
import fm.rizx.player.data.remote.soundcloud.NewPipeSoundcloudExtractorClient
import fm.rizx.player.data.remote.soundcloud.SoundcloudExtractorClient
import fm.rizx.player.data.search.DefaultPlaylistSourcesSearch
import fm.rizx.player.data.search.NewPipeStreamingSourcesSearch
import fm.rizx.player.data.search.PlaylistSourcesSearch
import fm.rizx.player.data.search.StreamingSourcesSearch
import fm.rizx.player.data.remote.youtube.NewPipeDownloaderImpl
import fm.rizx.player.data.remote.youtube.NewPipeYoutubeExtractorClient
import fm.rizx.player.data.remote.youtube.YoutubeExtractorClient
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
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
    private const val LYRICS_OVH_BASE_URL = "https://api.lyrics.ovh/"
    private const val AUDIUS_BASE_URL = "https://api.audius.co/"
    private const val DEEZER_BASE_URL = "https://api.deezer.com/"
    private const val USER_AGENT = "RizxPlayer/0.1 (+https://github.com/nukeop/nuclear)"

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
        }
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
            }
            .addInterceptor(logging)
            .build()
    }

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
