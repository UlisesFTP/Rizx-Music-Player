package fm.rizx.player.data.repository

import fm.rizx.player.data.remote.supabase.CreateShareRequest
import fm.rizx.player.data.remote.supabase.RevokeShareRequest
import fm.rizx.player.data.remote.supabase.ShareResponse
import fm.rizx.player.data.remote.supabase.SupabaseRequestException
import fm.rizx.player.data.remote.supabase.SupabaseShareApi
import fm.rizx.player.domain.account.AccountRepository
import fm.rizx.player.domain.account.AccountState
import fm.rizx.player.domain.repository.PlaylistExportFormat
import fm.rizx.player.domain.repository.PlaylistExportRepository
import fm.rizx.player.domain.share.PlaylistShare
import fm.rizx.player.domain.share.PlaylistShareRepository
import kotlinx.serialization.json.Json
import retrofit2.Response

class PlaylistShareRepositoryImpl(
    override val configured: Boolean,
    private val account: AccountRepository,
    private val exports: PlaylistExportRepository,
    private val api: SupabaseShareApi,
    private val json: Json,
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
        ).requireBody("No se pudo crear el enlace").domain()
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

