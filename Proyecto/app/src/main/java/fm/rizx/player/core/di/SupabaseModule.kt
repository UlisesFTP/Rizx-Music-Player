package fm.rizx.player.core.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fm.rizx.player.BuildConfig
import fm.rizx.player.data.remote.supabase.RetrofitSupabaseAuthGateway
import fm.rizx.player.data.remote.supabase.SecureSessionStore
import fm.rizx.player.data.remote.supabase.SessionStore
import fm.rizx.player.data.remote.supabase.SupabaseAuthApi
import fm.rizx.player.data.remote.supabase.SupabaseAuthGateway
import fm.rizx.player.data.remote.supabase.SupabaseShareApi
import fm.rizx.player.data.remote.supabase.SupabaseSyncApi
import fm.rizx.player.data.remote.supabase.SupabaseWireJson
import fm.rizx.player.data.repository.AccountRepositoryImpl
import fm.rizx.player.data.local.store.PlaylistShareStore
import fm.rizx.player.data.repository.PlaylistShareRepositoryImpl
import fm.rizx.player.domain.account.AccountRepository
import fm.rizx.player.domain.repository.PlaylistExportRepository
import fm.rizx.player.domain.share.PlaylistShareRepository
import fm.rizx.player.domain.share.ShareLinkInbox
import fm.rizx.player.domain.sync.SyncCoordinator
import android.util.Log
import fm.rizx.player.core.network.CatalogueCacheControlInterceptor
import fm.rizx.player.core.network.DataSaverState
import fm.rizx.player.core.network.OfflineCacheFallbackInterceptor
import fm.rizx.player.data.local.db.SyncDao
import fm.rizx.player.data.local.store.SyncPrefsStore
import fm.rizx.player.data.sync.LibraryJournal
import fm.rizx.player.data.sync.NoInvalidations
import fm.rizx.player.data.sync.PlaylistSyncEngine
import fm.rizx.player.data.sync.SyncEvents
import fm.rizx.player.data.sync.SyncInvalidationSocket
import fm.rizx.player.data.sync.SyncInvalidations
import fm.rizx.player.data.sync.SyncRunner
import fm.rizx.player.data.sync.SyncScheduler
import fm.rizx.player.data.sync.WorkManagerSyncCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AccountScope

@Module
@InstallIn(SingletonComponent::class)
object SupabaseModule {
    @Provides
    @Singleton
    @AccountScope
    fun provideAccountScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides
    @Singleton
    fun provideSessionStore(@ApplicationContext context: Context, json: Json): SessionStore =
        SecureSessionStore(context, json)

    // The three Retrofit APIs use SupabaseWireJson (not the injected app Json): GoTrue requires
    // fields whose Kotlin values are defaults, and the shared Json does not encode defaults.
    @Provides
    @Singleton
    fun provideSupabaseAuthApi(client: OkHttpClient): SupabaseAuthApi {
        val configured = BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_PUBLISHABLE_KEY.isNotBlank()
        val baseUrl = if (configured) BuildConfig.SUPABASE_URL.trimEnd('/') + "/" else "https://invalid.local/"
        val authClient = client.newBuilder()
            .cache(null)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
                        .header("Cache-Control", "no-store")
                        .build(),
                )
            }
            .build()
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(authClient)
            .addConverterFactory(SupabaseWireJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(SupabaseAuthApi::class.java)
    }

    @Provides
    @Singleton
    fun provideSupabaseShareApi(client: OkHttpClient): SupabaseShareApi {
        val configured = BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_PUBLISHABLE_KEY.isNotBlank()
        val baseUrl = if (configured) BuildConfig.SUPABASE_URL.trimEnd('/') + "/" else "https://invalid.local/"
        val apiClient = client.newBuilder().cache(null).addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
                    .header("Cache-Control", "no-store")
                    .build(),
            )
        }.build()
        return Retrofit.Builder().baseUrl(baseUrl).client(apiClient)
            .addConverterFactory(SupabaseWireJson.asConverterFactory("application/json".toMediaType()))
            .build().create(SupabaseShareApi::class.java)
    }

    @Provides
    @Singleton
    fun provideSupabaseSyncApi(client: OkHttpClient): SupabaseSyncApi {
        val configured = BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_PUBLISHABLE_KEY.isNotBlank()
        val baseUrl = if (configured) BuildConfig.SUPABASE_URL.trimEnd('/') + "/" else "https://invalid.local/"
        // The function applies each operation with its own RPC, so a batch answers slowly by design:
        // give it well past the shared client's read timeout before calling the run a failure.
        val apiClient = client.newBuilder().cache(null)
            .readTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder()
                    .header("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
                    .header("Cache-Control", "no-store").build())
            }.build()
        return Retrofit.Builder().baseUrl(baseUrl).client(apiClient)
            .addConverterFactory(SupabaseWireJson.asConverterFactory("application/json".toMediaType()))
            .build().create(SupabaseSyncApi::class.java)
    }

    @Provides
    @Singleton
    fun provideSyncCoordinator(implementation: WorkManagerSyncCoordinator): SyncCoordinator = implementation

    @Provides
    @Singleton
    fun provideSyncRunner(
        account: AccountRepository,
        syncDao: SyncDao,
        api: SupabaseSyncApi,
        exports: PlaylistExportRepository,
        engine: PlaylistSyncEngine,
        journal: LibraryJournal,
        prefs: SyncPrefsStore,
        events: SyncEvents,
        json: Json,
        dataSaver: DataSaverState,
    ): SyncRunner = SyncRunner(
        account = account, syncDao = syncDao, api = api, exports = exports, applier = engine, journal = journal,
        prefs = prefs, events = events, json = json,
        tasteUploadsPaused = dataSaver::blocksBulkTransfer,
        log = { Log.w("Sync", it) },
    )

    /**
     * The account's invalidation channel on the shared OkHttp, minus the two catalogue interceptors
     * (a WebSocket has nothing to cache and must not be answered from the offline cache). The
     * publishable key opens the socket; the user's JWT, sent on join, is what the channel's RLS judges.
     */
    @Provides
    @Singleton
    fun provideSyncInvalidations(
        client: OkHttpClient,
        account: AccountRepository,
        @AccountScope scope: CoroutineScope,
    ): SyncInvalidations {
        val configured = BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_PUBLISHABLE_KEY.isNotBlank()
        if (!configured) return NoInvalidations
        val host = BuildConfig.SUPABASE_URL.trimEnd('/').removePrefix("https://").removePrefix("http://")
        val socketClient = client.newBuilder().cache(null).apply {
            interceptors().removeAll { it is OfflineCacheFallbackInterceptor }
            networkInterceptors().removeAll { it is CatalogueCacheControlInterceptor }
        }.build()
        return SyncInvalidationSocket(
            client = socketClient,
            endpoint = "wss://$host/realtime/v1/websocket?apikey=${BuildConfig.SUPABASE_PUBLISHABLE_KEY}&vsn=1.0.0",
            tokenProvider = { account.accessToken() },
            scope = scope,
            log = { Log.w("Sync", it) },
        )
    }

    @Provides
    @Singleton
    fun provideSyncScheduler(
        account: AccountRepository,
        sync: SyncCoordinator,
        syncDao: SyncDao,
        prefs: SyncPrefsStore,
        journal: LibraryJournal,
        invalidations: SyncInvalidations,
    ): SyncScheduler = SyncScheduler(account, sync, syncDao, prefs, journal, invalidations)

    @Provides
    @Singleton
    fun provideSupabaseAuthGateway(api: SupabaseAuthApi): SupabaseAuthGateway = RetrofitSupabaseAuthGateway(api)

    @Provides
    @Singleton
    fun provideAccountRepository(
        gateway: SupabaseAuthGateway,
        store: SessionStore,
        @AccountScope scope: CoroutineScope,
    ): AccountRepository = AccountRepositoryImpl(
        configured = BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_PUBLISHABLE_KEY.isNotBlank(),
        gateway = gateway,
        store = store,
        scope = scope,
    )

    /** One slot for the whole process: the Activity drops a scanned link in, the Library takes it out. */
    @Provides
    @Singleton
    fun provideShareLinkInbox(): ShareLinkInbox = ShareLinkInbox()

    @Provides
    @Singleton
    fun providePlaylistShareRepository(
        account: AccountRepository,
        exports: PlaylistExportRepository,
        api: SupabaseShareApi,
        json: Json,
        store: PlaylistShareStore,
    ): PlaylistShareRepository = PlaylistShareRepositoryImpl(
        configured = BuildConfig.SUPABASE_URL.isNotBlank() &&
            BuildConfig.SUPABASE_PUBLISHABLE_KEY.isNotBlank() && BuildConfig.SHARE_BASE_URL.isNotBlank(),
        account = account,
        exports = exports,
        api = api,
        json = json,
        store = store,
    )
}
