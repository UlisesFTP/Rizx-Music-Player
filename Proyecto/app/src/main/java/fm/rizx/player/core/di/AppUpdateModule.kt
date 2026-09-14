package fm.rizx.player.core.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fm.rizx.player.BuildConfig
import fm.rizx.player.core.network.DownloadHttp
import fm.rizx.player.data.remote.github.GitHubReleasesApi
import fm.rizx.player.data.update.ApkDownloader
import fm.rizx.player.data.update.AppUpdateCoordinator
import fm.rizx.player.data.update.AppUpdateInstaller
import fm.rizx.player.data.update.AppUpdateNotifier
import fm.rizx.player.data.update.AppUpdateScheduler
import fm.rizx.player.data.update.AppUpdateStore
import fm.rizx.player.data.update.DataStoreAppUpdateStore
import fm.rizx.player.data.update.GitHubAppUpdateRepository
import fm.rizx.player.domain.update.AppUpdateInbox
import fm.rizx.player.domain.update.AppUpdateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File
import javax.inject.Singleton

private val Context.appUpdateDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_update")

/**
 * In-app updates from GitHub Releases (spec 024 / ADR 0032). The repository the app watches is the
 * project's own public one; the values are public facts, not configuration, so they live here.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppUpdateModule {

    private const val GITHUB_API_BASE_URL = "https://api.github.com/"
    private const val GITHUB_OWNER = "UlisesFTP"
    private const val GITHUB_REPO = "Rizx-Music-Player"
    private const val UPDATES_DIR = "updates"

    @Provides
    @Singleton
    fun provideGitHubReleasesApi(client: OkHttpClient, json: Json): GitHubReleasesApi =
        Retrofit.Builder()
            .baseUrl(GITHUB_API_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(GitHubReleasesApi::class.java)

    @Provides
    @Singleton
    fun provideAppUpdateRepository(api: GitHubReleasesApi): AppUpdateRepository =
        GitHubAppUpdateRepository(api, GITHUB_OWNER, GITHUB_REPO)

    @Provides
    @Singleton
    fun provideAppUpdateStore(@ApplicationContext context: Context, json: Json): AppUpdateStore =
        DataStoreAppUpdateStore(context.appUpdateDataStore, json)

    @Provides
    @Singleton
    fun provideApkDownloader(@ApplicationContext context: Context, @DownloadHttp client: OkHttpClient): ApkDownloader =
        ApkDownloader(client, File(context.filesDir, UPDATES_DIR))

    @Provides
    @Singleton
    fun provideAppUpdateCoordinator(
        repository: AppUpdateRepository,
        store: AppUpdateStore,
        downloader: ApkDownloader,
    ): AppUpdateCoordinator = AppUpdateCoordinator(
        repository = repository,
        store = store,
        downloader = downloader,
        installedVersion = BuildConfig.VERSION_NAME,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    @Provides
    @Singleton
    fun provideAppUpdateInbox(): AppUpdateInbox = AppUpdateInbox()

    @Provides
    @Singleton
    fun provideAppUpdateNotifier(@ApplicationContext context: Context): AppUpdateNotifier = AppUpdateNotifier(context)

    @Provides
    @Singleton
    fun provideAppUpdateScheduler(@ApplicationContext context: Context): AppUpdateScheduler = AppUpdateScheduler(context)

    @Provides
    @Singleton
    fun provideAppUpdateInstaller(@ApplicationContext context: Context): AppUpdateInstaller = AppUpdateInstaller(context)
}
