package fm.rizx.player.data.remote.supabase

import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the wire shape of Supabase requests. GoTrue requires fields whose Kotlin values are
 * declared as defaults (`provider = "google"`, `type = "email"`); the app-wide Json omits defaults,
 * which produced `400 invalid request: provider or client_id and issuer required` on every Google
 * sign-in. These tests pin the dedicated encoder to the shape the server actually needs.
 */
class SupabaseWireJsonTest {

    @Test
    fun `google token request carries the provider field`() {
        val body = SupabaseWireJson.encodeToString(
            GoogleTokenRequest.serializer(),
            GoogleTokenRequest(idToken = "tok", nonce = "n"),
        )
        assertTrue(body, body.contains("\"provider\":\"google\""))
        assertTrue(body, body.contains("\"id_token\":\"tok\""))
        assertTrue(body, body.contains("\"nonce\":\"n\""))
    }

    @Test
    fun `otp verification carries its type`() {
        val body = SupabaseWireJson.encodeToString(
            VerifyOtpRequest.serializer(),
            VerifyOtpRequest(email = "a@b.c", token = "123456"),
        )
        assertTrue(body, body.contains("\"type\":\"email\""))
    }

    @Test
    fun `otp request states create_user explicitly`() {
        val body = SupabaseWireJson.encodeToString(OtpRequest.serializer(), OtpRequest("a@b.c"))
        assertTrue(body, body.contains("\"create_user\":true"))
    }

    @Test
    fun `anonymous request without captcha omits the security envelope`() {
        val body = SupabaseWireJson.encodeToString(AnonymousRequest.serializer(), AnonymousRequest())
        assertFalse(body, body.contains("gotrue_meta_security"))
        assertTrue(body, body.contains("\"data\":{}"))
    }

    @Test
    fun `sync delete operation omits its null document`() {
        val body = SupabaseWireJson.encodeToString(
            SyncOperationRequest.serializer(),
            SyncOperationRequest(operationId = "op", entityType = "playlist", entityId = "p1", operation = "delete"),
        )
        assertFalse(body, body.contains("document"))
    }

    @Test
    fun `google user metadata surfaces the photo and name`() {
        val session = SupabaseWireJson.decodeFromString(
            SupabaseSessionDto.serializer(),
            """{"access_token":"a","refresh_token":"r","user":{"id":"u","email":"x@y.z",
               "user_metadata":{"avatar_url":"https://p/img.jpg","full_name":"Ulises FTP","iss":"https://accounts.google.com"}}}""",
        )
        assertEquals("https://p/img.jpg", session.user.metadata?.avatarUrl)
        assertEquals("Ulises FTP", session.user.metadata?.fullName)
        // Email-OTP users have no metadata at all — the field must default, not fail.
        val otp = SupabaseWireJson.decodeFromString(
            SupabaseSessionDto.serializer(),
            """{"access_token":"a","refresh_token":"r","user":{"id":"u","email":"x@y.z"}}""",
        )
        assertEquals(null, otp.user.metadata)
    }

    @Test
    fun `decoding stays tolerant like the shared Json`() {
        val session = SupabaseWireJson.decodeFromString(
            SupabaseSessionDto.serializer(),
            """{"access_token":"a","refresh_token":"r","user":{"id":"u","unknown_field":1}}""",
        )
        assertEquals("u", session.user.id)
        assertEquals(3600, session.expiresIn)
        assertFalse(session.user.isAnonymous)
        // And a null where a default exists coerces rather than crashes.
        assertEquals(JsonNull, SupabaseWireJson.parseToJsonElement("null"))
    }
}
