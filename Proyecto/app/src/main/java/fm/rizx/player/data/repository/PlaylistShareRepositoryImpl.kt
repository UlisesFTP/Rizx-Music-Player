package fm.rizx.player.data.repository

import fm.rizx.player.data.remote.supabase.CreateShareRequest
import fm.rizx.player.data.remote.supabase.RevokeShareRequest
import fm.rizx.player.data.remote.supabase.ShareResponse
import fm.rizx.player.data.remote.supabase.SupabaseRequestException
import fm.rizx.player.data.remote.supabase.SupabaseShareApi
import fm.rizx.player.data.local.store.PlaylistShareStore
import fm.rizx.player.data.local.store.StoredPlaylistShare
import fm.rizx.player.domain.account.AccountRepository
import fm.rizx.player.domain.account.AccountState
import fm.rizx.player.domain.repository.PlaylistExportFormat
import fm.rizx.player.domain.repository.PlaylistExportRepository
import fm.rizx.player.domain.share.PlaylistShare
import fm.rizx.player.domain.share.PlaylistShareRepository
import kotlinx.serialization.json.Json
import retrofit2.Response
import java.time.Instant

class PlaylistShareRepositoryImpl(
    override val configured: Boolean,
    private val account: AccountRepository,
    private val exports: PlaylistExportRepository,
    private val api: SupabaseShareApi,
    private val json: Json,
    private val store: PlaylistShareStore,
    private val now: () -> Instant = { Instant.now() },
) : PlaylistShareRepository {
    override suspend fun create(playlistId: String, captchaToken: String?): PlaylistShare {
        require(configured) { "Los enlaces todavía no están configurados" }
        if (account.state.value == AccountState.LocalOnly) account.ensureGuestSession(captchaToken)
        val token = account.accessToken() ?: error("La sesión expiró")
        val artifact = exports.export(playlistId, PlaylistExportFormat.RIZX_JSON)
            ?: error("La playlist ya no existe")
        require(artifact.content.toByteArray().size <= MAX_SHARE_BYTES) { "La playlist supera 5 MiB" }
        val days = if (account.state.value is AccountState.SignedIn) 30 else 7
        return api.create(
            "Bearer $token",
            CreateShareRequest(json.parseToJsonElement(artifact.content), days),
        ).requireBody("No se pudo crear el enlace").domain().also { share ->
            // The server keeps only a hash of the token, so if this write is skipped the app can never
            // find its own link again — not to copy it, and not to revoke it.
            store.put(playlistId, StoredPlaylistShare(share.id, share.url, share.expiresAtIso, share.createdAtIso))
        }
    }

    /**
     * Local record first, server as the veto.
     *
     * `list` cannot answer "which link belongs to this playlist" — shares are stored anonymously — so the
     * mapping is local and the server is only asked to confirm the share is still live. The local gate
     * comes first on purpose: a playlist that never had a link costs no request at all. A failed check
     * (no session, no network) keeps the local record rather than hiding a link that probably still works.
     */
    override suspend fun existing(playlistId: String): PlaylistShare? {
        if (!configured) return null
        val stored = store.get(playlistId) ?: return null
        if (!stored.isLiveAt(now())) {
            store.remove(playlistId)
            return null
        }
        val share = PlaylistShare(stored.id, stored.url, stored.expiresAtIso, stored.createdAtIso)
        val token = account.accessToken() ?: return share
        val listed = runCatching {
            api.list("Bearer $token").requireBody("No se pudieron cargar los enlaces")
        }.getOrNull() ?: return share
        if (listed.any { it.id == stored.id }) return share
        // The server answered and this share is gone: revoked from another install, or expired server-side.
        store.remove(playlistId)
        return null
    }

    override suspend fun active(): List<PlaylistShare> {
        if (!configured) return emptyList()
        val token = account.accessToken() ?: return emptyList()
        return api.list("Bearer $token").requireBody("No se pudieron cargar los enlaces").map { it.domain() }
    }

    override suspend fun revoke(shareId: String) {
        val token = account.accessToken() ?: error("La sesión expiró")
        val response = api.revoke("Bearer $token", RevokeShareRequest(shareId))
        if (!response.isSuccessful) throw SupabaseRequestException("No se pudo revocar el enlace", response.code())
        store.removeByShareId(shareId)
    }

    private fun ShareResponse.domain() = PlaylistShare(id, url, expiresAt, createdAt)

    private fun <T> Response<T>.requireBody(message: String): T {
        if (!isSuccessful) throw SupabaseRequestException(
            if (code() == 429) "Alcanzaste el límite de enlaces. Inténtalo más tarde." else message,
            code(),
        )
        return body() ?: throw SupabaseRequestException(message, code())
    }

    private companion object { const val MAX_SHARE_BYTES = 5 * 1024 * 1024 }
}

