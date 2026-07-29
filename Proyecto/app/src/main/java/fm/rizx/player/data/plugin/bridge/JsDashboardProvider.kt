package fm.rizx.player.data.plugin.bridge

import fm.rizx.player.domain.model.AlbumRef
import fm.rizx.player.domain.model.ArtistRef
import fm.rizx.player.domain.model.DashboardCapability
import fm.rizx.player.domain.model.PlaylistRef
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.provider.DashboardProvider
import fm.rizx.player.domain.provider.ProviderKind
import kotlinx.serialization.json.Json

/**
 * Bridges a JS-registered dashboard provider into the Kotlin [DashboardProvider] contract (ADR
 * 0014/0019). Nuclear's shape:
 * `fetchTopTracks/fetchTopArtists/fetchTopAlbums/fetchEditorialPlaylists/fetchNewReleases` (no args).
 *
 * A capability is offered only when the plugin **declared** it *and* actually defined its method —
 * a declared-but-missing method would fail on every Home load, and a defined-but-undeclared one is
 * how plugins written before the `capabilities` array still work.
 */
class JsDashboardProvider(
    override val id: String,
    override val name: String,
    override val version: String,
    override val pluginId: String?,
    private val uid: String,
    private val descriptorId: String,
    private val methods: Set<String>,
    /** The plugin's declared `capabilities` array (upstream shape); empty for older plugins. */
    declaredCapabilities: Set<String> = emptySet(),
    private val invoker: JsProviderInvoker,
    private val json: Json,
) : DashboardProvider {

    override val kind: ProviderKind = ProviderKind.DASHBOARD

    override val dashboardCapabilities: Set<DashboardCapability> = buildSet {
        // The declared list is authoritative when present, but a declared capability without its
        // method would 500 on every Home load — so it must ALSO have the method. Undeclared+present
        // stays the fallback for plugins that predate the capabilities array.
        fun has(capName: String, method: String) =
            method in methods && (declaredCapabilities.isEmpty() || capName in declaredCapabilities)
        if (has("topTracks", "fetchTopTracks")) add(DashboardCapability.TOP_TRACKS)
        if (has("topArtists", "fetchTopArtists")) add(DashboardCapability.TOP_ARTISTS)
        if (has("topAlbums", "fetchTopAlbums")) add(DashboardCapability.TOP_ALBUMS)
        if (has("editorialPlaylists", "fetchEditorialPlaylists")) add(DashboardCapability.EDITORIAL_PLAYLISTS)
        if (has("newReleases", "fetchNewReleases")) add(DashboardCapability.NEW_RELEASES)
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
        invoker.invoke(uid, method, "[]", timeoutMs = 20_000) ?: "[]"
}
