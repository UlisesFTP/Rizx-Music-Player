package fm.rizx.player.data.local.store

import fm.rizx.player.domain.model.DownloadedTrack
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Stream
import fm.rizx.player.domain.model.StreamCandidate
import fm.rizx.player.domain.model.StreamProtocol
import fm.rizx.player.domain.model.Track
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DownloadIndexStoreTest {

    @get:Rule val temp = TemporaryFolder()

    private val ref = ProviderRef("deezer", "123")

    private fun file(): File = File(temp.root, "downloads.json")

    private fun downloaded(
        track: Track = Track(title = "Velvet Hours", source = ref),
        fileName: String = "deezer_123.m4a",
    ) = DownloadedTrack(
        track = track,
        fileName = fileName,
        sizeBytes = 4_200_000,
        container = "m4a",
        mimeType = "audio/mp4",
        downloadedAtIso = "2026-07-16T10:00:00Z",
    )

    @Test
    fun `index round-trips through disk`() = runBlocking {
        val entry = downloaded()
        val store = DownloadIndexStore(file())

        store.save(mapOf(entry.key to entry))

        assertEquals(mapOf(entry.key to entry), DownloadIndexStore(file()).load())
    }

    @Test
    fun `entries are keyed by the track's provider ref`() = runBlocking {
        val entry = downloaded()
        val store = DownloadIndexStore(file())

        store.save(mapOf(entry.key to entry))

        // The key is derived on read, not stored — it can never disagree with the track it points at.
        assertEquals(setOf("deezer:123"), store.load().keys)
    }

    @Test
    fun `a missing file reads as nothing downloaded`() {
        assertEquals(emptyMap<String, DownloadedTrack>(), DownloadIndexStore(file()).load())
    }

    @Test
    fun `a corrupt file reads as nothing downloaded instead of throwing`() {
        file().writeText("{ this is not json")

        assertEquals(emptyMap<String, DownloadedTrack>(), DownloadIndexStore(file()).load())
    }

    @Test
    fun `saving an empty index clears the file`() = runBlocking {
        val entry = downloaded()
        val store = DownloadIndexStore(file())
        store.save(mapOf(entry.key to entry))
        assertTrue(file().exists())

        store.save(emptyMap())

        assertFalse(file().exists())
        assertEquals(emptyMap<String, DownloadedTrack>(), store.load())
    }

    @Test
    fun `ephemeral stream urls are never written to disk`() = runBlocking {
        val withStream = Track(
            title = "Velvet Hours",
            source = ref,
            streamCandidates = listOf(
                StreamCandidate(
                    id = "c1",
                    title = "Velvet Hours",
                    stream = Stream(
                        url = "https://cdn/ephemeral-token.m4a",
                        protocol = StreamProtocol.HTTPS,
                        source = ProviderRef("youtube", "c1"),
                    ),
                    source = ProviderRef("youtube", "c1"),
                ),
            ),
        )
        val entry = downloaded(track = withStream)
        val store = DownloadIndexStore(file())

        store.save(mapOf(entry.key to entry))

        assertFalse("resolved stream url must not be persisted", file().readText().contains("ephemeral-token"))
        assertTrue(store.load().getValue(entry.key).track.streamCandidates.isEmpty())
    }

    @Test
    fun `a temp file is never left behind by a successful save`() = runBlocking {
        val entry = downloaded()

        DownloadIndexStore(file()).save(mapOf(entry.key to entry))

        assertEquals(listOf("downloads.json"), temp.root.list()!!.sorted())
    }
}
