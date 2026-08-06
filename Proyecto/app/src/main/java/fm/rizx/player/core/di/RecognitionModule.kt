package fm.rizx.player.core.di

import android.content.Context
import fm.rizx.player.data.local.db.RecognitionHistoryDao
import fm.rizx.player.data.recognition.AndroidMicrophoneRecorder
import fm.rizx.player.data.recognition.DefaultRecognitionTrackResolver
import fm.rizx.player.data.recognition.Pcm16Resampler
import fm.rizx.player.data.recognition.RecognitionMatcher
import fm.rizx.player.data.recognition.ShazamRecognitionClient
import fm.rizx.player.data.recognition.ShazamRecognitionProvider
import fm.rizx.player.data.recognition.ShazamSignatureGenerator
import fm.rizx.player.data.remote.deezer.DeezerApi
import fm.rizx.player.data.remote.itunes.ItunesApi
import fm.rizx.player.data.repository.RecognitionRepositoryImpl
import fm.rizx.player.domain.recognition.MicrophoneRecorder
import fm.rizx.player.domain.recognition.RecognitionProvider
import fm.rizx.player.domain.recognition.RecognitionRepository
import fm.rizx.player.domain.recognition.RecognitionTrackResolver
import fm.rizx.player.domain.repository.MetadataRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import javax.inject.Singleton

/**
 * Wires ambient music recognition: the microphone, the fingerprint, the service, and the resolver that
 * turns an identification back into something this app can play.
 *
 * The internals stay internal — only the four domain contracts are exposed — so the recognition
 * backend can be swapped without anything outside this file noticing.
 */
@Module
@InstallIn(SingletonComponent::class)
object RecognitionModule {

    @Provides
    @Singleton
    fun provideMicrophoneRecorder(@ApplicationContext context: Context): MicrophoneRecorder =
        AndroidMicrophoneRecorder(context, Dispatchers.IO)

    @Provides
    @Singleton
    fun provideRecognitionProvider(client: OkHttpClient, json: Json): RecognitionProvider =
        ShazamRecognitionProvider(
            client = ShazamRecognitionClient(client, json),
            signatures = ShazamSignatureGenerator(),
            resampler = Pcm16Resampler(),
            io = Dispatchers.IO,
        )

    @Provides
    @Singleton
    fun provideRecognitionTrackResolver(
        deezer: DeezerApi,
        itunes: ItunesApi,
        metadata: MetadataRepository,
    ): RecognitionTrackResolver =
        DefaultRecognitionTrackResolver(
            deezer = deezer,
            itunes = itunes,
            metadata = metadata,
            matcher = RecognitionMatcher(),
            io = Dispatchers.IO,
        )

    @Provides
    @Singleton
    fun provideRecognitionRepository(
        recorder: MicrophoneRecorder,
        provider: RecognitionProvider,
        resolver: RecognitionTrackResolver,
        historyDao: RecognitionHistoryDao,
    ): RecognitionRepository =
        RecognitionRepositoryImpl(
            recorder = recorder,
            provider = provider,
            resolver = resolver,
            historyDao = historyDao,
            io = Dispatchers.IO,
        )
}
