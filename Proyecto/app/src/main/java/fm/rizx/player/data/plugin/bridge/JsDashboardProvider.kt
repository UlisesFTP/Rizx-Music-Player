package fm.rizx.player.data.plugin.bridge

import fm.rizx.player.data.plugin.engine.QuickJsEngine
import fm.rizx.player.domain.model.AlbumRef
import fm.rizx.player.domain.model.ArtistRef
import fm.rizx.player.domain.model.DashboardCapability
import fm.rizx.player.domain.model.PlaylistRef
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.provider.DashboardProvider
import fm.rizx.player.domain.provider.ProviderKind
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Bridges a JS-registered dashboard provider into the Kotlin [DashboardProvider] contract (ADR 0014).
 * Nuclear's shape: `fetchTopTracks/fetchTopArtists/fetchTopAlbums/fetchEditorialPlaylists/fetchNewReleases`
 * (no args). Capabilities are derived from which of those methods the plugin actually defined, so a broken
 * or missing method never runs and the Home fan-out simply skips that section.
 */
class JsDashboardProvider(
    override val id: String,
    override val name: String,
    override val version: String,
    override val pluginId: String?,
    private val uid: String,
    private val descriptorId: String,
    private val methods: Set<String>,
    private val engine: QuickJsEngine,
    private val json: Json,
) : DashboardProvider {

    override val kind: ProviderKind = ProviderKind.DASHBOARD

    override val dashboardCapabilities: Set<DashboardCapability> = buildSet {
        if ("fetchTopTracks" in methods) add(DashboardCapability.TOP_TRACKS)
        if ("fetchTopArtists" in methods) add(DashboardCapability.TOP_ARTISTS)
        if ("fetchTopAlbums" in methods) add(DashboardCapability.TOP_ALBUMS)
        if ("fetchEditorialPlaylists" in methods) add(DashboardCapability.EDITORIAL_PLAYLISTS)
        if ("fetchNewReleases" in methods) add(DashboardCapability.NEW_RELEASES)
    }

    override suspend fun topTracks(limit: Int): List<Track> =
        JsModelMappers.parseTracks(invoke("fetchTopTracks"), descriptorId, json).take(limit)

    override suspend fun topArtists(limit: Int): List<ArtistRef> =
        JsModelMappers.parseArtistRefs(invoke("fetchTopArtists"), descriptorId, json).take(limit)

    override suspend fun topAlbums(limit: Int): List<AlbumRef> =
        JsModelMappers.parseAlbumRefs(invoke("fetchTopAlbums"), descriptorId, json).take(limit)

    override suspend fun editorialPlaylists(limit: Int): List<PlaylistRef> =
        JsModelMappers.parsePlaylistRefs(invoke("fetchEditorialPlaylists"), descriptorId, json).take(limit)

    override suspend fun newReleases(limit: Int): List<AlbumRef> =
        JsModelMappers.parseAlbumRefs(invoke("fetchNewReleases"), descriptorId, json).take(limit)

    private suspend fun invoke(method: String): String =
        engine.evalCaptured("globalThis.__rizx.invokeAndCapture(${enc(uid)}, ${enc(method)}, ${enc("[]")})") ?: "[]"

    private fun enc(s: String): String = json.encodeToString(String.serializer(), s)
}
