package fm.rizx.player.core.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fm.rizx.player.data.plugin.InstalledPluginStore
import fm.rizx.player.data.plugin.JsPluginRuntime
import fm.rizx.player.data.plugin.PluginRepositoryImpl
import fm.rizx.player.data.plugin.install.PluginInstaller
import fm.rizx.player.data.plugin.install.PluginRegistryClient
import fm.rizx.player.data.plugin.install.TsTranspiler
import fm.rizx.player.domain.plugin.PluginRepository
import fm.rizx.player.domain.provider.ProviderRegistry
import fm.rizx.player.domain.repository.SettingsRepository
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Singleton

/**
 * Wires the sandboxed JS plugin runtime and its download/install pipeline (ADR 0014). The QuickJS engine
 * and Sucrase transpiler are lazy inside their wrappers, so users who never install a plugin pay no cost.
 */
@Module
@InstallIn(SingletonComponent::class)
object PluginModule {

    private fun asset(context: Context, path: String): String =
        context.assets.open(path).bufferedReader().use { it.readText() }

    @Provides
    @Singleton
    fun provideTsTranspiler(@ApplicationContext context: Context, json: Json): TsTranspiler =
        TsTranspiler(asset(context, "plugin-runtime/sucrase.min.js"), json)

    @Provides
    @Singleton
    fun provideJsPluginRuntime(
        @ApplicationContext context: Context,
        client: OkHttpClient,
        json: Json,
        registry: ProviderRegistry,
        transpiler: TsTranspiler,
    ): JsPluginRuntime =
        JsPluginRuntime(client, asset(context, "plugin-runtime/bootstrap.js"), json, registry, transpiler)

    @Provides
    @Singleton
    fun providePluginRegistryClient(client: OkHttpClient, json: Json): PluginRegistryClient =
        PluginRegistryClient(client, json)

    @Provides
    @Singleton
    fun providePluginInstaller(@ApplicationContext context: Context, client: OkHttpClient, json: Json): PluginInstaller =
        PluginInstaller(client, json, File(context.filesDir, "plugins"))

    @Provides
    @Singleton
    fun provideInstalledPluginStore(@ApplicationContext context: Context, json: Json): InstalledPluginStore =
        InstalledPluginStore(context, json)

    @Provides
    @Singleton
    fun providePluginRepository(
        registryClient: PluginRegistryClient,
        installer: PluginInstaller,
        store: InstalledPluginStore,
        runtime: JsPluginRuntime,
        registry: ProviderRegistry,
        settings: SettingsRepository,
    ): PluginRepository = PluginRepositoryImpl(registryClient, installer, store, runtime, registry, settings)
}
