package fm.rizx.player.data.remote.supabase

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

/**
 * The encoder for every request sent to Supabase. It exists because the app-wide Json leaves
 * `encodeDefaults` off (kotlinx's own default), so any field whose Kotlin value equals its default was
 * silently dropped from request bodies — and for Supabase those defaults ARE wire data:
 * [GoogleTokenRequest.provider]` = "google"` and [VerifyOtpRequest.type]` = "email"` are required by
 * GoTrue, which answered every Google sign-in with `400 invalid request: provider or client_id and
 * issuer required` while the app showed "No se pudo iniciar sesión con Google".
 *
 * `encodeDefaults = true` writes them; `explicitNulls = false` keeps optional absent fields (captcha
 * token, a delete operation's document) off the wire instead of turning them into `"field": null`.
 * Decoding behaves exactly like the shared Json — the flags only change what is written.
 */
@OptIn(ExperimentalSerializationApi::class)
internal val SupabaseWireJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    isLenient = true
    encodeDefaults = true
    explicitNulls = false
}
