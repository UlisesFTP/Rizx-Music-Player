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
import fm.rizx.player.data.download.Mp3Transcoder
import fm.rizx.player.data.download.ServiceDownloadNotifier
import fm.rizx.player.data.download.TrackDownloader
import fm.rizx.player.data.local.store.DownloadIndexStore
import fm.rizx.player.data.repository.DownloadRepositoryImpl
import fm.rizx.player.core.network.DataSaverState
import fm.rizx.player.core.network.DownloadHttp
import fm.rizx.player.core.network.NetworkMonitor
import fm.rizx.player.domain.lossless.LosslessResolver
import fm.rizx.player.domain.repository.CachedAudioReader
import fm.rizx.player.domain.provider.ProviderRegistry
import fm.rizx.player.domain.repository.DownloadRepository
import fm.rizx.player.domain.repository.SettingsRepository
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
    fun provideTrackDownloader(
        @DownloadHttp client: OkHttpClient,
        @DownloadsDir dir: File,
        network: NetworkMonitor,
    ): TrackDownloader =
        TrackDownloader(
            client,
            dir,
            // On a struggling link, three parallel readers would only fight whatever the user is
            // actively listening to — one is the polite (and net faster) choice there.
            maxWorkers = { if (runCatching { network.snapshot().isBadSignal }.getOrDefault(false)) 1 else 3 },
        )

    @Provides
    @Singleton
    fun provideMediaStoreExporter(@ApplicationContext context: Context): MediaStoreExporter =
        MediaStoreExporterImpl(context)

    @Provides
    @Singleton
    fun provideDownloadNotifier(@ApplicationContext context: Context): DownloadNotifier =
        ServiceDownloadNotifier(context)

    /** Embeds cover/artist/album/date into finished downloads so they carry metadata to any other player. */
    @Provides
    @Singleton
    fun provideAudioTagWriter(client: OkHttpClient): AudioTagWriter = AudioTagWriter(client)

    /** Decodes with the framework, encodes with jump3r — the "MP3" download format's converter. */
    @Provides
    @Singleton
    fun provideMp3Transcoder(): Mp3Transcoder = Mp3Transcoder()

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
        lossless: LosslessResolver,
        settings: SettingsRepository,
        dataSaver: DataSaverState,
        transcoder: Mp3Transcoder,
        cachedAudio: CachedAudioReader,
    ): DownloadRepository = DownloadRepositoryImpl(
        store = store,
        downloader = downloader,
        resolver = resolver,
        exporter = exporter,
        notifier = notifier,
        tagWriter = tagWriter,
        registry = registry,
        lossless = lossless,
        settings = settings,
        dataSaver = dataSaver,
        transcoder = transcoder,
        cachedAudio = cachedAudio,
    )
}

/** The app-private folder holding downloaded audio. */
@javax.inject.Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DownloadsDir
