package fm.rizx.player.data.remote.supabase

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

interface SupabaseAuthApi {
    @POST("auth/v1/otp")
    suspend fun requestOtp(@Body request: OtpRequest): Response<Unit>

    @POST("auth/v1/verify")
    suspend fun verifyOtp(@Body request: VerifyOtpRequest): Response<SupabaseSessionDto>

    @POST("auth/v1/token")
    suspend fun signInWithIdToken(
        @Query("grant_type") grantType: String = "id_token",
        @Body request: GoogleTokenRequest,
    ): Response<SupabaseSessionDto>

    @POST("auth/v1/token")
    suspend fun refresh(
        @Query("grant_type") grantType: String = "refresh_token",
        @Body request: RefreshRequest,
    ): Response<SupabaseSessionDto>

    @POST("auth/v1/signup")
    suspend fun anonymous(@Body request: AnonymousRequest): Response<SupabaseSessionDto>

    @POST("auth/v1/logout")
    suspend fun logout(@Header("Authorization") authorization: String): Response<Unit>

    @POST("functions/v1/account-delete")
    suspend fun deleteAccount(@Header("Authorization") authorization: String): Response<Unit>

    @POST("functions/v1/guest-claim")
    suspend fun claimGuest(
        @Header("Authorization") authorization: String,
        @Body request: GuestClaimRequest,
    ): Response<Unit>
}

interface SupabaseAuthGateway {
    suspend fun requestOtp(email: String)
    suspend fun verifyOtp(email: String, code: String): SupabaseSessionDto
    suspend fun google(idToken: String, nonce: String): SupabaseSessionDto
    suspend fun anonymous(captchaToken: String?): SupabaseSessionDto
    suspend fun refresh(refreshToken: String): SupabaseSessionDto
    suspend fun logout(accessToken: String)
    suspend fun deleteAccount(accessToken: String)
    suspend fun claimGuest(guestAccessToken: String, permanentAccessToken: String)
}

class RetrofitSupabaseAuthGateway(private val api: SupabaseAuthApi) : SupabaseAuthGateway {
    override suspend fun requestOtp(email: String) {
        api.requestOtp(OtpRequest(email)).requireSuccess("No se pudo enviar el código")
    }

    override suspend fun verifyOtp(email: String, code: String): SupabaseSessionDto =
        api.verifyOtp(VerifyOtpRequest(email, code)).requireBody("El código no es válido o expiró")

    override suspend fun google(idToken: String, nonce: String): SupabaseSessionDto =
        api.signInWithIdToken(request = GoogleTokenRequest(idToken = idToken, nonce = nonce))
            .requireBody("No se pudo iniciar sesión con Google")

    override suspend fun anonymous(captchaToken: String?): SupabaseSessionDto =
        api.anonymous(AnonymousRequest(security = captchaToken?.let(::CaptchaSecurity)))
            .requireBody("No se pudo crear la sesión de invitado")

    override suspend fun refresh(refreshToken: String): SupabaseSessionDto =
        api.refresh(request = RefreshRequest(refreshToken)).requireBody("La sesión expiró")

    override suspend fun logout(accessToken: String) {
        api.logout("Bearer $accessToken")
    }

    override suspend fun deleteAccount(accessToken: String) {
        api.deleteAccount("Bearer $accessToken").requireSuccess("No se pudo eliminar la cuenta")
    }

    override suspend fun claimGuest(guestAccessToken: String, permanentAccessToken: String) {
        api.claimGuest("Bearer $permanentAccessToken", GuestClaimRequest(guestAccessToken))
            .requireSuccess("No se pudieron transferir los enlaces temporales")
    }

    private fun Response<*>.requireSuccess(fallback: String) {
        if (!isSuccessful) throw SupabaseRequestException(errorMessage(fallback), code())
    }

    private fun <T> Response<T>.requireBody(fallback: String): T {
        if (!isSuccessful) throw SupabaseRequestException(errorMessage(fallback), code())
        return body() ?: throw SupabaseRequestException(fallback, code())
    }

    private fun Response<*>.errorMessage(fallback: String): String {
        val raw = runCatching { errorBody()?.string().orEmpty() }.getOrDefault("")
        return when {
            code() == 429 -> "Demasiados intentos. Espera un momento."
            raw.contains("expired", true) -> "El código o la sesión expiró."
            raw.contains("captcha", true) -> "Completa la verificación de seguridad."
            else -> fallback
        }
    }
}

class SupabaseRequestException(message: String, val statusCode: Int) : Exception(message)
