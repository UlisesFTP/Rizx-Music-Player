package fm.rizx.player.core.di

import android.content.Context
import androidx.media3.common.util.UnstableApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fm.rizx.player.core.network.DownloadHttp
import fm.rizx.player.data.download.AudioTagWriter
import fm.rizx.player.data.download.DownloadNotifier
import fm.rizx.player.data.download.Jump3rMp3Encoder
import fm.rizx.player.data.download.MediaStoreExporter
import fm.rizx.player.data.download.Mp3Transcoder
import fm.rizx.player.data.download.SpatialMp3Encoder
import fm.rizx.player.data.download.TrackDownloader
import fm.rizx.player.data.genre.TrackGenreResolver
import fm.rizx.player.data.local.store.SpatialAudioProfileStore
import fm.rizx.player.data.local.store.SpatialRenderStore
import fm.rizx.player.data.repository.SpatialRenderRepositoryImpl
import fm.rizx.player.domain.playback.SpatialAudioController
import fm.rizx.player.domain.repository.SettingsRepository
import fm.rizx.player.domain.repository.SpatialRenderRepository
import fm.rizx.player.domain.usecase.StreamingResolver
import fm.rizx.player.playback.spatial.SmartSpatialController
import fm.rizx.player.playback.spatial.SmartSpatialEngine
import fm.rizx.player.playback.spatial.SpatialSinkState
import fm.rizx.player.playback.spatial.StereoPcmTransform
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Singleton

/**
 * Wires adaptive stereo spatialization.
 *
 * **The engine and the sink state are singletons on purpose.** They are the one place the controller
 * and the audio sink meet: the controller decides what the profile should be, the sink runs it, and if
 * they were separate instances the toggle would appear to work and nothing would happen.
 */
@UnstableApi
@Module
@InstallIn(SingletonComponent::class)
object SpatialAudioModule {

    /** Exposed as the interface, so the DSP itself stays internal to the playback layer. */
    @Provides
    @Singleton
    fun provideSpatialEngine(): StereoPcmTransform = SmartSpatialEngine()

    @Provides
    @Singleton
    fun provideSpatialSinkState(): SpatialSinkState = SpatialSinkState()

    @Provides
    @Singleton
    fun provideSpatialProfileStore(@ApplicationContext context: Context): SpatialAudioProfileStore =
        SpatialAudioProfileStore(File(context.filesDir, "spatial_audio_profiles.json"))

    @Provides
    @Singleton
    fun provideSpatialAudioController(controller: SmartSpatialController): SpatialAudioController = controller

    // ---- 8D renders (see DownloadsModule for the rest of the wiring) ----

    /**
     * Renders live beside downloads but in a folder of their own — [TrackDownloader] sweeps everything
     * in its directory that its caller does not claim, so sharing one would have each index delete the
     * other's files at startup.
     */
    @Provides
    @Singleton
    @SpatialRendersDir
    fun provideSpatialRendersDir(@ApplicationContext context: Context): File =
        File(
            context.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC)
                ?: File(context.filesDir, "Music"),
            "renders8d",
        )

    @Provides
    @Singleton
    fun provideSpatialRenderStore(@ApplicationContext context: Context): SpatialRenderStore =
        SpatialRenderStore(File(context.filesDir, "spatial_renders.json"))

    @Provides
    @Singleton
    @SpatialRendersDir
    fun provideSpatialRenderDownloader(
        @DownloadHttp client: OkHttpClient,
        @SpatialRendersDir dir: File,
    ): TrackDownloader = TrackDownloader(client, dir)

    @Provides
    @Singleton
    fun provideSpatialRenderRepository(
        store: SpatialRenderStore,
        @SpatialRendersDir downloader: TrackDownloader,
        resolver: StreamingResolver,
        profiles: SpatialAudioProfileStore,
        genres: TrackGenreResolver,
        exporter: MediaStoreExporter,
        tagWriter: AudioTagWriter,
        settings: SettingsRepository,
        notifier: DownloadNotifier,
    ): SpatialRenderRepository = SpatialRenderRepositoryImpl(
        store = store,
        downloader = downloader,
        resolver = resolver,
        profiles = profiles,
        genres = genres,
        // **A fresh engine per render, never the playback singleton.** That one is mid-song, holding a
        // profile, an envelope and a room full of tail; borrowing it would have the render and whatever
        // is playing overwrite each other's state.
        newTranscoder = { profile ->
            Mp3Transcoder(
                newEncoder = { rate, channels ->
                    SpatialMp3Encoder(
                        engine = SmartSpatialEngine(),
                        profile = profile,
                        sampleRateHz = rate,
                        sourceChannels = channels,
                        // Two channels whatever arrived: a spatializer has nowhere to put a mono file.
                        delegate = Jump3rMp3Encoder(rate, 2),
                    )
                },
            )
        },
        notifier = notifier,
        exporter = exporter,
        tagWriter = tagWriter,
        settings = settings,
    )
}

/** The app-private folder holding rendered 8D files. */
@javax.inject.Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SpatialRendersDir
