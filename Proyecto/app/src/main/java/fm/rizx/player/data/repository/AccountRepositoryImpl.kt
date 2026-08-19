package fm.rizx.player.data.repository

import fm.rizx.player.data.remote.supabase.SessionStore
import fm.rizx.player.data.remote.supabase.StoredAuthSession
import fm.rizx.player.data.remote.supabase.SupabaseAuthGateway
import fm.rizx.player.data.remote.supabase.SupabaseSessionDto
import fm.rizx.player.domain.account.AccountProfile
import fm.rizx.player.domain.account.AccountRepository
import fm.rizx.player.domain.account.AccountState
import fm.rizx.player.domain.account.AccountUnavailableException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import java.time.Instant

class AccountRepositoryImpl(
    override val configured: Boolean,
    private val gateway: SupabaseAuthGateway,
    private val store: SessionStore,
    scope: CoroutineScope,
    private val nowEpochSeconds: () -> Long = { Instant.now().epochSecond },
) : AccountRepository {
    override val state: StateFlow<AccountState> = if (!configured) {
        MutableStateFlow(AccountState.Disabled)
    } else {
        store.session.map { session ->
            when {
                session == null -> AccountState.LocalOnly
                session.isAnonymous -> AccountState.Guest(session.profile())
                else -> AccountState.SignedIn(session.profile())
            }
        }.stateIn(scope, SharingStarted.Eagerly, AccountState.LocalOnly)
    }

    override suspend fun requestEmailOtp(email: String) {
        requireConfigured()
        gateway.requestOtp(normalizeEmail(email))
    }

    override suspend fun verifyEmailOtp(email: String, code: String) {
        requireConfigured()
        savePermanent(gateway.verifyOtp(normalizeEmail(email), code.trim()))
    }

    override suspend fun signInWithGoogle(idToken: String, nonce: String) {
        requireConfigured()
        savePermanent(gateway.google(idToken, nonce))
    }

    override suspend fun ensureGuestSession(captchaToken: String?): AccountProfile {
        requireConfigured()
        val current = store.session.first()
        if (current != null) return current.profile()
        val response = gateway.anonymous(captchaToken)
        save(response)
        return response.toStored().profile()
    }

    override suspend fun accessToken(): String? {
        if (!configured) return null
        val current = store.session.first() ?: return null
        if (current.expiresAtEpochSeconds > nowEpochSeconds() + REFRESH_MARGIN_SECONDS) return current.accessToken
        return runCatching {
            val refreshed = gateway.refresh(current.refreshToken)
            save(refreshed)
            refreshed.accessToken
        }.getOrElse {
            store.clear()
            null
        }
    }

    override suspend fun signOut() {
        val token = store.session.first()?.accessToken
        if (token != null) runCatching { gateway.logout(token) }
        store.clear()
    }

    override suspend fun deleteCloudAccount() {
        val token = accessToken() ?: throw AccountUnavailableException("La sesión expiró")
        gateway.deleteAccount(token)
        store.clear()
    }

    private suspend fun save(dto: SupabaseSessionDto) = store.save(dto.toStored())

    private suspend fun savePermanent(dto: SupabaseSessionDto) {
        val guest = store.session.first()?.takeIf { it.isAnonymous }
        store.save(dto.toStored())
        if (guest != null && !dto.user.isAnonymous) {
            runCatching { gateway.claimGuest(guest.accessToken, dto.accessToken) }
        }
    }

    private fun SupabaseSessionDto.toStored() = StoredAuthSession(
        accessToken, refreshToken, nowEpochSeconds() + expiresIn, user.id, user.email, user.isAnonymous,
        displayName = user.metadata?.let { it.fullName ?: it.name },
        avatarUrl = user.metadata?.let { it.avatarUrl ?: it.picture },
    )

    private fun StoredAuthSession.profile() =
        AccountProfile(userId, email, isAnonymous, displayName, avatarUrl)

    private fun normalizeEmail(value: String): String {
        val email = value.trim().lowercase()
        require(email.length in 3..254 && EMAIL.matches(email)) { "Escribe un correo válido" }
        return email
    }

    private fun requireConfigured() {
        if (!configured) throw AccountUnavailableException("La sincronización todavía no está configurada")
    }

    private companion object {
        val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
        const val REFRESH_MARGIN_SECONDS = 60L
    }
}
