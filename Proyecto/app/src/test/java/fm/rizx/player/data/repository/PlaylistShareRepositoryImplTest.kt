package fm.rizx.player.data.repository

import fm.rizx.player.data.local.store.PlaylistShareStore
import fm.rizx.player.data.local.store.StoredPlaylistShare
import fm.rizx.player.data.remote.supabase.SupabaseShareApi
import fm.rizx.player.domain.account.AccountProfile
import fm.rizx.player.domain.account.AccountRepository
import fm.rizx.player.domain.account.AccountState
import fm.rizx.player.domain.repository.PlaylistExportArtifact
import fm.rizx.player.domain.repository.PlaylistExportFormat
import fm.rizx.player.domain.repository.PlaylistExportRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File
import java.time.Instant

/**
 * The link belongs to a playlist only locally: `rizx_playlist_shares` stores an anonymous snapshot plus a
 * token hash, so `list` can say a share is alive but never which playlist it came from.
 */
class PlaylistShareRepositoryImplTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var api: SupabaseShareApi
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(SupabaseShareApi::class.java)
    }

    @After
    fun tearDown() = server.shutdown()

    private class FakeAccount(private val token: String?) : AccountRepository {
        override val state: StateFlow<AccountState> =
            MutableStateFlow(AccountState.Guest(AccountProfile("u1", null, isAnonymous = true)))
        override val configured = true
        override suspend fun requestEmailOtp(email: String) = Unit
        override suspend fun verifyEmailOtp(email: String, code: String) = Unit
        override suspend fun signInWithGoogle(idToken: String, nonce: String) = Unit
        override suspend fun ensureGuestSession(captchaToken: String?) =
            AccountProfile("u1", null, isAnonymous = true)
        override suspend fun accessToken(): String? = token
        override suspend fun signOut() = Unit
        override suspend fun deleteCloudAccount() = Unit
    }

    private object FakeExports : PlaylistExportRepository {
        override suspend fun export(playlistId: String, format: PlaylistExportFormat) =
            PlaylistExportArtifact("p.json", "application/json", """{"name":"P","items":[]}""", 0, 0)
    }

    private fun store() = PlaylistShareStore(File(tmp.root, "playlist_shares.json"))

    private fun repo(
        store: PlaylistShareStore,
        token: String? = "tok",
        configured: Boolean = true,
        now: Instant = Instant.parse("2026-08-21T00:00:00Z"),
    ) = PlaylistShareRepositoryImpl(
        configured = configured,
        account = FakeAccount(token),
        exports = FakeExports,
        api = api,
        json = json,
        store = store,
        now = { now },
    )

    private fun stored(id: String = "share-1", expires: String = "2026-08-27T16:49:59Z") =
        StoredPlaylistShare(id, "https://example.test/s/token", expires, "2026-08-20T16:49:59Z")

    private fun listBody(vararg ids: String) = ids.joinToString(",", "[", "]") {
        """{"id":"$it","url":"https://example.test/s/$it","expires_at":"2026-08-27T16:49:59Z"}"""
    }

    @Test
    fun `creating a link remembers which playlist it belongs to`() = runTest {
        val store = store()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"share-9","url":"https://example.test/s/nine","expires_at":"2026-08-27T16:49:59Z","created_at":"2026-08-20T16:49:59Z"}""",
            ),
        )

        val created = repo(store).create("playlist-a")

        assertEquals("share-9", created.id)
        val remembered = store.get("playlist-a")!!
        assertEquals("share-9", remembered.id)
        assertEquals("https://example.test/s/nine", remembered.url)
    }

    @Test
    fun `an existing link comes back, confirmed against the server`() = runTest {
        val store = store()
        store.put("playlist-a", stored("share-1"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(listBody("share-1", "share-2")))

        val found = repo(store).existing("playlist-a")

        assertNotNull(found)
        assertEquals("share-1", found!!.id)
        assertEquals("https://example.test/s/token", found.url)
    }

    @Test
    fun `a share revoked from another install is dropped once the server says so`() = runTest {
        val store = store()
        store.put("playlist-a", stored("share-1"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(listBody("share-2")))

        assertNull(repo(store).existing("playlist-a"))
        assertNull("and it is forgotten, not re-checked forever", store.get("playlist-a"))
    }

    @Test
    fun `a playlist that never had a link costs no request`() = runTest {
        val store = store()

        assertNull(repo(store).existing("playlist-a"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an expired link is dropped without asking the server`() = runTest {
        val store = store()
        store.put("playlist-a", stored(expires = "2026-08-20T16:49:59Z"))

        assertNull(repo(store).existing("playlist-a"))
        assertEquals(0, server.requestCount)
        assertNull(store.get("playlist-a"))
    }

    @Test
    fun `without a session the local record is trusted rather than hidden`() = runTest {
        val store = store()
        store.put("playlist-a", stored("share-1"))

        val found = repo(store, token = null).existing("playlist-a")

        assertEquals("share-1", found?.id)
        assertEquals(0, server.requestCount)
        assertNotNull("a signed-out user still owns the link they copied", store.get("playlist-a"))
    }

    @Test
    fun `a server error keeps the link instead of pretending it is gone`() = runTest {
        val store = store()
        store.put("playlist-a", stored("share-1"))
        server.enqueue(MockResponse().setResponseCode(500))

        assertEquals("share-1", repo(store).existing("playlist-a")?.id)
        assertNotNull(store.get("playlist-a"))
    }

    @Test
    fun `revoking forgets the link so the sheet offers a fresh one`() = runTest {
        val store = store()
        store.put("playlist-a", stored("share-1"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        repo(store).revoke("share-1")

        assertNull(store.get("playlist-a"))
    }

    @Test
    fun `with cloud sharing unconfigured nothing is looked up`() = runTest {
        val store = store()
        store.put("playlist-a", stored("share-1"))

        assertNull(repo(store, configured = false).existing("playlist-a"))
        assertEquals(0, server.requestCount)
    }
}
