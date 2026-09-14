package fm.rizx.player.data.remote.supabase

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SupabaseUserDto(
    val id: String,
    val email: String? = null,
    @SerialName("is_anonymous") val isAnonymous: Boolean = false,
    @SerialName("user_metadata") val metadata: SupabaseUserMetadata? = null,
)

/**
 * The slice of GoTrue's free-form `user_metadata` the app shows. Google writes both spellings of
 * each field (`avatar_url`/`picture`, `full_name`/`name`); email-OTP users have neither and every
 * field stays null, so the UI must always have a non-photo fallback.
 */
@Serializable
data class SupabaseUserMetadata(
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val picture: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    val name: String? = null,
)

@Serializable
data class SupabaseSessionDto(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Long = 3600,
    @SerialName("token_type") val tokenType: String = "bearer",
    val user: SupabaseUserDto,
)

@Serializable data class OtpRequest(val email: String, @SerialName("create_user") val createUser: Boolean = true)
@Serializable data class VerifyOtpRequest(val email: String, val token: String, val type: String = "email")
@Serializable data class GoogleTokenRequest(val provider: String = "google", @SerialName("id_token") val idToken: String, val nonce: String)
@Serializable data class RefreshRequest(@SerialName("refresh_token") val refreshToken: String)
@Serializable data class AnonymousRequest(val data: Map<String, String> = emptyMap(), @SerialName("gotrue_meta_security") val security: CaptchaSecurity? = null)
@Serializable data class CaptchaSecurity(@SerialName("captcha_token") val captchaToken: String)
@Serializable data class GuestClaimRequest(@SerialName("guest_access_token") val guestAccessToken: String)

@Serializable
data class StoredAuthSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochSeconds: Long,
    val userId: String,
    val email: String? = null,
    val isAnonymous: Boolean = false,
    // Defaults are load-bearing: sessions stored before these fields existed must keep decoding.
    // They backfill themselves on the next token refresh, whose response carries the user again.
    val displayName: String? = null,
    val avatarUrl: String? = null,
)
