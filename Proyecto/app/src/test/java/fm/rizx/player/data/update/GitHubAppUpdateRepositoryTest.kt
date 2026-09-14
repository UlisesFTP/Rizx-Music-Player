package fm.rizx.player.data.update

import fm.rizx.player.data.remote.github.GitHubAssetDto
import fm.rizx.player.data.remote.github.GitHubReleasesApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * The release → update mapping, against the real response shape (verified live on 2026-09-13: assets
 * carry `digest: "sha256:…"`, `releases/latest` answers 404 for a repository with no release).
 */
class GitHubAppUpdateRepositoryTest {

    private lateinit var server: MockWebServer

    @Before fun start() { server = MockWebServer(); server.start() }
    @After fun stop() { server.shutdown() }

    private fun repository(): GitHubAppUpdateRepository {
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(GitHubReleasesApi::class.java)
        return GitHubAppUpdateRepository(api, "UlisesFTP", "Rizx-Music-Player")
    }

    private fun asset(name: String, size: Long = 11_811_995, digest: String? = "sha256:$SHA") = """
        {"name":"$name","size":$size,"content_type":"application/vnd.android.package-archive",
         "browser_download_url":"https://github.com/UlisesFTP/Rizx-Music-Player/releases/download/v1.1.0/$name"
         ${if (digest != null) ",\"digest\":\"$digest\"" else ""}}
    """.trimIndent()

    private fun release(
        tag: String = "v1.1.0",
        name: String? = "Rizx 1.1.0",
        body: String? = "## Changes\n- Ambient lights\n- Section marks",
        draft: Boolean = false,
        prerelease: Boolean = false,
        assets: List<String> = listOf(asset("Rizx-1.1.0-release.apk")),
    ) = """
        {"tag_name":"$tag","name":${name?.let { "\"$it\"" } ?: "null"},"body":${body?.let { "\"${it.replace("\n", "\\n")}\"" } ?: "null"},
         "draft":$draft,"prerelease":$prerelease,"published_at":"2026-09-13T18:00:00Z",
         "html_url":"https://github.com/UlisesFTP/Rizx-Music-Player/releases/tag/$tag",
         "assets":[${assets.joinToString(",")}],"reactions":{"+1":3}}
    """.trimIndent()

    @Test
    fun `maps the latest release to an update, reading the digest and stripping the v`() = runBlocking {
        server.enqueue(MockResponse().setBody(release()))
        val update = repository().latest()!!
        assertEquals("1.1.0", update.versionName)
        assertEquals("v1.1.0", update.tag)
        assertEquals("Rizx 1.1.0", update.title)
        assertEquals("## Changes\n- Ambient lights\n- Section marks", update.notes)
        assertEquals("Rizx-1.1.0-release.apk", update.apkName)
        assertEquals(11_811_995L, update.apkBytes)
        assertEquals(SHA, update.sha256)
        assertEquals("https://github.com/UlisesFTP/Rizx-Music-Player/releases/download/v1.1.0/Rizx-1.1.0-release.apk", update.apkUrl)
        val request = server.takeRequest()
        assertEquals("/repos/UlisesFTP/Rizx-Music-Player/releases/latest", request.path)
        assertEquals("application/vnd.github+json", request.getHeader("Accept"))
    }

    @Test
    fun `no release yet is null, not an error`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"Not Found"}"""))
        assertNull(repository().latest())
    }

    @Test
    fun `other HTTP failures propagate`() {
        server.enqueue(MockResponse().setResponseCode(503))
        assertThrows(HttpException::class.java) { runBlocking { repository().latest() } }
    }

    @Test
    fun `a release without an apk, a draft or a prerelease offers nothing`() = runBlocking {
        server.enqueue(MockResponse().setBody(release(assets = listOf(asset("Rizx-1.1.0.sha256")))))
        assertNull(repository().latest())
        server.enqueue(MockResponse().setBody(release(draft = true)))
        assertNull(repository().latest())
        server.enqueue(MockResponse().setBody(release(prerelease = true)))
        assertNull(repository().latest())
    }

    @Test
    fun `a tag that is not a version offers nothing`() = runBlocking {
        server.enqueue(MockResponse().setBody(release(tag = "latest")))
        assertNull(repository().latest())
    }

    @Test
    fun `the release apk wins over smoke-test and debug builds attached to the same release`() {
        val picked = GitHubAppUpdateRepository.pickApk(
            listOf(
                dto("Rizx-1.1.0-releaseTest.apk"),
                dto("Rizx-1.1.0-debug.apk"),
                dto("Rizx-1.1.0-release.apk"),
                dto("Rizx-1.1.0-release.apk.sha256"),
            ),
        )
        assertEquals("Rizx-1.1.0-release.apk", picked?.name)
        assertNull(GitHubAppUpdateRepository.pickApk(listOf(dto("Rizx-1.1.0-releaseTest.apk"), dto("notes.txt"))))
        assertEquals("rizx.apk", GitHubAppUpdateRepository.pickApk(listOf(dto("rizx.apk")))?.name)
    }

    @Test
    fun `missing title falls back to the tag and a missing digest reads as null`() = runBlocking {
        server.enqueue(MockResponse().setBody(release(name = null, body = null, assets = listOf(asset("Rizx-1.1.0-release.apk", digest = null)))))
        val update = repository().latest()!!
        assertEquals("v1.1.0", update.title)
        assertEquals("", update.notes)
        assertNull(update.sha256)
    }

    @Test
    fun `only a well-formed sha256 digest is trusted`() {
        assertEquals(SHA, GitHubAppUpdateRepository.parseSha256("sha256:${SHA.uppercase()}"))
        assertNull(GitHubAppUpdateRepository.parseSha256("md5:abc"))
        assertNull(GitHubAppUpdateRepository.parseSha256("sha256:abc"))
        assertNull(GitHubAppUpdateRepository.parseSha256("garbage"))
    }

    private fun dto(name: String) = GitHubAssetDto(name = name, size = 1, browserDownloadUrl = "https://x/$name")

    private companion object {
        const val SHA = "3457b39172a44e3dde59e8fd56c2a4c15060e4dff64cd5a878e4cee1f4a424d1"
    }
}
