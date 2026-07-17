package fm.rizx.player.core.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fm.rizx.player.data.repository.LocalLibraryRepositoryImpl
import fm.rizx.player.domain.repository.LocalLibraryRepository
import javax.inject.Singleton

/** The on-device music library (a MediaStore scanner). Singleton so its scanned [songs] is shared. */
@Module
@InstallIn(SingletonComponent::class)
object LocalLibraryModule {

    @Provides
    @Singleton
    fun provideLocalLibraryRepository(@ApplicationContext context: Context): LocalLibraryRepository =
        LocalLibraryRepositoryImpl(context)
}
