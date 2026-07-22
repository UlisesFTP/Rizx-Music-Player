package fm.rizx.player.core.di

import android.content.Context
import android.os.Environment
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fm.rizx.player.data.download.AudioTagWriter
import fm.rizx.player.data.download.DownloadNotifier
import fm.rizx.player.data.download.MediaStoreExporter
import fm.rizx.player.data.download.MediaStoreExporterImpl
import fm.rizx.player.data.canvas.CanvasSource
import fm.rizx.player.data.canvas.YoutubeCanvasSource
import fm.rizx.player.data.download.ServiceDownloadNotifier
import fm.rizx.player.data.download.TrackDownloader
import fm.rizx.player.data.local.store.DownloadIndexStore
import fm.rizx.player.data.remote.youtube.YoutubeExtractorClient
import fm.rizx.player.data.repository.DownloadRepositoryImpl
import fm.rizx.player.domain.provider.ProviderRegistry
import fm.rizx.player.domain.repository.DownloadRepository
import fm.rizx.player.domain.usecase.StreamingResolver
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Singleton

/**
 * Wires the offline library: the download index, the fetcher, and the repository that owns both.
 *
 * Downloaded audio lives in **app-private external storage** — no storage permission on any API level,
 * and it disappears cleanly on uninstall. Sharing is the Export action's job (`MediaStoreExporterImpl`),
 * which copies into `Music/Rizx` on demand.
 */
@Module
@InstallIn(SingletonComponent::class)
object DownloadsModule {

    /**
     * Where downloaded audio lives. `getExternalFilesDir` returns null when external storage is
     * unmounted, so fall back to internal storage rather than crashing — a broken storage state must
     * degrade to "downloads unavailable", never take the app down.
     */
    @Provides
    @Singleton
    @DownloadsDir
    fun provideDownloadsDir(@ApplicationContext context: Context): File =
        File(
            context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: File(context.filesDir, "Music"),
            "downloads",
        )

    @Provides
    @Singleton
    fun provideDownloadIndexStore(@ApplicationContext context: Context): DownloadIndexStore =
        DownloadIndexStore(File(context.filesDir, "downloads.json"))

    @Provides
    @Singleton
    fun provideTrackDownloader(client: OkHttpClient, @DownloadsDir dir: File): TrackDownloader =
        TrackDownloader(client, dir)

    @Provides
    @Singleton
    fun provideMediaStoreExporter(@ApplicationContext context: Context): MediaStoreExporter =
        MediaStoreExporterImpl(context)

    @Provides
    @Singleton
    fun provideDownloadNotifier(@ApplicationContext context: Context): DownloadNotifier =
        ServiceDownloadNotifier(context)

    /**
     * The Now Playing canvas. Reuses the YouTube extractor the streaming provider already depends on —
     * no new dependency, and no other provider has a video to offer (Deezer's public API returns images
     * only; Spotify's Canvas endpoint is neither public nor keyless).
     */
    @Provides
    @Singleton
    fun provideCanvasSource(youtube: YoutubeExtractorClient): CanvasSource = YoutubeCanvasSource(youtube)

    /** Embeds cover/artist/album/date into finished downloads so they carry metadata to any other player. */
    @Provides
    @Singleton
    fun provideAudioTagWriter(client: OkHttpClient): AudioTagWriter = AudioTagWriter(client)

    @Provides
    @Singleton
    fun provideDownloadRepository(
        store: DownloadIndexStore,
        downloader: TrackDownloader,
        resolver: StreamingResolver,
        exporter: MediaStoreExporter,
        notifier: DownloadNotifier,
        tagWriter: AudioTagWriter,
        registry: ProviderRegistry,
    ): DownloadRepository =
        DownloadRepositoryImpl(store, downloader, resolver, exporter, notifier, tagWriter, registry)
}

/** The app-private folder holding downloaded audio. */
@javax.inject.Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DownloadsDir
