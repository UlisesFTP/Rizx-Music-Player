package fm.rizx.player.core.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fm.rizx.player.data.local.media.SafAudioRepository
import fm.rizx.player.data.local.store.OpenedFilesStore
import fm.rizx.player.data.repository.LocalLibraryRepositoryImpl
import fm.rizx.player.domain.repository.LocalLibraryRepository
import fm.rizx.player.domain.repository.OpenedFilesRepository
import java.io.File
import javax.inject.Singleton

/** The on-device music library (a MediaStore scanner + the SAF-opened files). Singletons — shared state. */
@Module
@InstallIn(SingletonComponent::class)
object LocalLibraryModule {

    @Provides
    @Singleton
    fun provideOpenedFilesRepository(@ApplicationContext context: Context): OpenedFilesRepository =
        SafAudioRepository(context, OpenedFilesStore(File(context.filesDir, "opened-files.json")))

    @Provides
    @Singleton
    fun provideLocalLibraryRepository(
        @ApplicationContext context: Context,
        openedFiles: OpenedFilesRepository,
    ): LocalLibraryRepository =
        LocalLibraryRepositoryImpl(context, openedFiles = openedFiles)
}
