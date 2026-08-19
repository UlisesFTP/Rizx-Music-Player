package fm.rizx.player.domain.account

import kotlinx.coroutines.flow.StateFlow

data class AccountProfile(
    val id: String,
    val email: String?,
    val isAnonymous: Boolean,
    /** Google's full name; null for email-OTP and guest sessions. */
    val displayName: String? = null,
    /** Google's profile photo URL; null for email-OTP and guest sessions. */
    val avatarUrl: String? = null,
)

sealed interface AccountState {
    data object LocalOnly : AccountState
    data object Disabled : AccountState
    data class Guest(val profile: AccountProfile) : AccountState
    data class SignedIn(val profile: AccountProfile) : AccountState
}

interface AccountRepository {
    val state: StateFlow<AccountState>
    val configured: Boolean

    suspend fun requestEmailOtp(email: String)
    suspend fun verifyEmailOtp(email: String, code: String)
    suspend fun signInWithGoogle(idToken: String, nonce: String)
    suspend fun ensureGuestSession(captchaToken: String? = null): AccountProfile
    suspend fun accessToken(): String?
    suspend fun signOut()
    suspend fun deleteCloudAccount()
}

class AccountUnavailableException(message: String) : Exception(message)
