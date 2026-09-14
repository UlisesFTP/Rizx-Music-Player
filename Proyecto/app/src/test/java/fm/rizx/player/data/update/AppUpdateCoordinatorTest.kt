package fm.rizx.player.data.update

import fm.rizx.player.domain.update.AppUpdate
import fm.rizx.player.domain.update.AppUpdateFailure
import fm.rizx.player.domain.update.AppUpdateRepository
import fm.rizx.player.domain.update.AppUpdateState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest

@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdateCoordinatorTest {

    @get:Rule val folder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val store = InMemoryAppUpdateStore()
    // Far enough from epoch that the first check is "stale" and actually asks the repository.
    private var now = 100L * 24 * 60 * 60 * 1000

    @Before fun start() { server = MockWebServer(); server.start() }
    @After fun stop() { server.shutdown() }

    private val bytes = ByteArray(50_000) { (it % 97).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun update(version: String = "1.1.0") = AppUpdate(
        versionName = version, tag = "v$version", title = "Rizx $version", notes = "- things", publishedAtIso = null,
        pageUrl = "https://x", apkUrl = server.url("/Rizx-$version-release.apk").toString(),
        apkName = "Rizx-$version-release.apk", apkBytes = bytes.size.toLong(), sha256 = sha,
    )

    private class FakeRepository(var answer: () -> AppUpdate?) : AppUpdateRepository {
        var calls = 0
        override suspend fun latest(): AppUpdate? { calls++; return answer() }
    }

    private fun coordinator(repository: AppUpdateRepository, scope: kotlinx.coroutines.CoroutineScope, installed: String = "1.0.0") =
        AppUpdateCoordinator(
            repository = repository,
            store = store,
            downloader = ApkDownloader(OkHttpClient(), File(folder.root, "updates")),
            installedVersion = installed,
            scope = scope,
            clock = { now },
        )

    @Test
    fun `a newer release is available, the same or older is up to date`() = runTest {
        val repo = FakeRepository { update("1.1.0") }
        val c = coordinator(repo, this)
        assertEquals(update("1.1.0"), c.check())
        assertEquals(AppUpdateState.Available(update("1.1.0")), c.state.value)

        repo.answer = { update("1.0.0") }
        assertNull(c.check(force = true))
        assertEquals(AppUpdateState.UpToDate("1.0.0"), c.state.value)

        repo.answer = { null }
        assertNull(c.check(force = true))
        assertEquals(AppUpdateState.UpToDate("1.0.0"), c.state.value)
    }

    @Test
    fun `within twelve hours the answer comes from the store, not GitHub`() = runTest {
        val repo = FakeRepository { update("1.1.0") }
        val c = coordinator(repo, this)
        c.check()
        assertEquals(1, repo.calls)
        now += 60 * 60 * 1000
        assertEquals(update("1.1.0"), c.check())
        assertEquals(1, repo.calls)
        assertEquals(AppUpdateState.Available(update("1.1.0")), c.state.value)
        now += 12 * 60 * 60 * 1000
        c.check()
        assertEquals(2, repo.calls)
        c.check(force = true)
        assertEquals(3, repo.calls)
    }

    @Test
    fun `a skipped version is up to date until a newer one appears`() = runTest {
        val repo = FakeRepository { update("1.1.0") }
        val c = coordinator(repo, this)
        c.check()
        c.skip()
        assertEquals(AppUpdateState.UpToDate("1.0.0", skippedVersion = "1.1.0"), c.state.value)
        assertNull(c.check(force = true))
        repo.answer = { update("1.2.0") }
        assertEquals(update("1.2.0"), c.check(force = true))
    }

    @Test
    fun `a failed lookup is reported as a network failure and retry asks again`() = runTest {
        val repo = FakeRepository { throw IOException("offline") }
        val c = coordinator(repo, this)
        assertNull(c.check())
        assertEquals(AppUpdateState.Failed(null, AppUpdateFailure.NETWORK), c.state.value)
        repo.answer = { update("1.1.0") }
        c.retry()
        assertEquals(AppUpdateState.Available(update("1.1.0")), c.state.value)
    }

    @Test
    fun `download moves through progress to ready, and a later check keeps ready`() = runTest {
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        val repo = FakeRepository { update("1.1.0") }
        val c = coordinator(repo, this)
        c.check()
        c.download()
        val done = c.state.first { it is AppUpdateState.Ready || it is AppUpdateState.Failed }
        assertTrue(done.toString(), done is AppUpdateState.Ready)
        assertTrue(File((done as AppUpdateState.Ready).filePath).isFile)
        assertEquals(update("1.1.0"), c.check(force = true))
        assertEquals(done, c.state.value)
        assertEquals(1, repo.calls)
    }

    @Test
    fun `a corrupt download fails as verification and retry goes back to available`() = runTest {
        server.enqueue(MockResponse().setBody(Buffer().write(bytes.copyOf(bytes.size - 1))))
        val c = coordinator(FakeRepository { update("1.1.0") }, this)
        c.check()
        c.download()
        val done = c.state.first { it is AppUpdateState.Ready || it is AppUpdateState.Failed }
        assertEquals(AppUpdateState.Failed(update("1.1.0"), AppUpdateFailure.VERIFICATION), done)
        c.retry()
        assertEquals(AppUpdateState.Available(update("1.1.0")), c.state.value)
    }

    @Test
    fun `a version is announced once`() = runTest {
        val c = coordinator(FakeRepository { update("1.1.0") }, this)
        assertTrue(c.claimNotification(update("1.1.0")))
        assertFalse(c.claimNotification(update("1.1.0")))
        assertTrue(c.claimNotification(update("1.2.0")))
    }

    @Test
    fun `an already downloaded file makes the update ready on the next launch`() = runTest {
        val dir = File(folder.root, "updates").apply { mkdirs() }
        File(dir, "Rizx-1.1.0-release.apk").writeBytes(bytes)
        val c = coordinator(FakeRepository { update("1.1.0") }, this)
        c.check()
        assertEquals(AppUpdateState.Ready(update("1.1.0"), File(dir, "Rizx-1.1.0-release.apk").absolutePath), c.state.value)
    }
}

/** The store as a map, for tests. */
class InMemoryAppUpdateStore : AppUpdateStore {
    private var checkedAt = 0L
    private var known: AppUpdate? = null
    private var skipped: String? = null
    private var notified: String? = null
    override suspend fun lastCheckedAtMs() = checkedAt
    override suspend fun lastKnown() = known
    override suspend fun saveCheck(update: AppUpdate?, atMs: Long) { known = update; checkedAt = atMs }
    override suspend fun skippedVersion() = skipped
    override suspend fun setSkippedVersion(version: String?) { skipped = version }
    override suspend fun notifiedVersion() = notified
    override suspend fun setNotifiedVersion(version: String) { notified = version }
}
