package fm.rizx.player.data.local.store

import android.content.Context
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.QueueContext
import fm.rizx.player.domain.model.QueueItem
import fm.rizx.player.domain.model.QueueSourceKind
import fm.rizx.player.domain.model.RadioMode
import fm.rizx.player.domain.model.RepeatMode
import fm.rizx.player.domain.model.Track
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class PlaybackSessionStoreTest {

    private fun store(dir: java.io.File): PlaybackSessionStore {
        val context = mockk<Context> { every { filesDir } returns dir }
        return PlaybackSessionStore(context)
    }

    private fun snapshot(context: QueueContext) = PlaybackSessionSnapshot(
        items = listOf(
            QueueItem(
                id = "q1",
                track = Track(title = "Seed", source = ProviderRef("deezer", "42")),
                addedAtIso = "2026-07-24T00:00:00Z",
            ),
        ),
        currentIndex = 0,
        repeatMode = RepeatMode.OFF,
        positionMs = 1_000L,
        context = context,
        shuffleOn = false,
        unshuffledIds = null,
    )

    @Test
    fun `radio mode round-trips through persistence`() = runTest {
        val dir = Files.createTempDirectory("rizx-session").toFile()
        val store = store(dir)

        store.save(
            snapshot(
                QueueContext(
                    kind = QueueSourceKind.RADIO,
                    label = "Mix · Seed",
                    radioSeed = ProviderRef("deezer", "42"),
                    radioMode = RadioMode.YOUTUBE,
                ),
            ),
        )
        val restored = store(dir).load()

        requireNotNull(restored)
        assertEquals(QueueSourceKind.RADIO, restored.context.kind)
        assertEquals(RadioMode.YOUTUBE, restored.context.radioMode)
    }

    @Test
    fun `a session persisted before radio modes existed restores as ARTIST`() = runTest {
        val dir = Files.createTempDirectory("rizx-session").toFile()
        val store = store(dir)
        store.save(snapshot(QueueContext(kind = QueueSourceKind.RADIO, label = "Radio · X")))

        // Rewrite the file as an old build would have written it: without the radioMode field.
        val file = dir.listFiles()!!.single()
        file.writeText(file.readText().replace(Regex(""","radioMode":"[a-zA-Z]+""""), ""))

        val restored = store(dir).load()

        requireNotNull(restored)
        assertEquals(RadioMode.ARTIST, restored.context.radioMode)
    }
}
