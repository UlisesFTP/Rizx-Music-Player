package fm.rizx.player.data.repository

import fm.rizx.player.data.local.db.RecognitionHistoryDao
import fm.rizx.player.data.local.db.RecognitionHistoryEntity
import fm.rizx.player.domain.model.ArtistCredit
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.recognition.MicrophoneRecorder
import fm.rizx.player.domain.recognition.RecognitionAudio
import fm.rizx.player.domain.recognition.RecognitionError
import fm.rizx.player.domain.recognition.RecognitionMatch
import fm.rizx.player.domain.recognition.RecognitionOutcome
import fm.rizx.player.domain.recognition.RecognitionProvider
import fm.rizx.player.domain.recognition.RecognitionState
import fm.rizx.player.domain.recognition.RecognitionTrackResolver
import fm.rizx.player.domain.recognition.RecordingFailure
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The state machine, and the two things about it that are easy to get quietly wrong: what happens when
 * someone presses cancel, and whether a result from an abandoned attempt can still reach the screen.
 *
 * States are collected into a list rather than awaited one by one, because `StateFlow` conflates: a
 * transition that happens without the collector being resumed in between simply never appears, and a
 * test written to await each one would be flaky rather than strict.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecognitionRepositoryImplTest {

    private val match = RecognitionMatch(
        provider = "shazam",
        providerTrackId = "1",
        title = "Get Lucky",
        artist = "Daft Punk",
        isrc = "USQX91300108",
    )

    private val resolved = Track(
        title = "Get Lucky",
        artists = listOf(ArtistCredit(name = "Daft Punk")),
        source = ProviderRef("deezer", "67238735"),
    )

    // -- fakes ----------------------------------------------------------------------------------

    private class FakeRecorder : MicrophoneRecorder {
        var available = true
        var failWith: RecognitionError? = null
        var holdMs = 0L
        var released = false
        var amplitudeSink: ((Float) -> Unit)? = null

        override fun isAvailable() = available

        override suspend fun record(targetDurationMs: Long, onAmplitude: (Float) -> Unit): RecognitionAudio {
            amplitudeSink = onAmplitude
            failWith?.let { throw RecordingFailure(it) }
            try {
                onAmplitude(0.5f)
                if (holdMs > 0) delay(holdMs)
                return RecognitionAudio(ByteArray(32), 16_000, 1, targetDurationMs)
            } finally {
                released = true
            }
        }
    }

    private class FakeProvider(var outcome: RecognitionOutcome) : RecognitionProvider {
        override val id = "fake"
        var calls = 0
        var holdMs = 0L

        override suspend fun recognize(audio: RecognitionAudio): RecognitionOutcome {
            calls++
            if (holdMs > 0) delay(holdMs)
            return outcome
        }
    }

    private class FakeResolver(var track: Track?, var boom: Boolean = false) : RecognitionTrackResolver {
        override suspend fun resolve(match: RecognitionMatch): Track? {
            if (boom) error("catalogue is down")
            return track
        }
    }

    private class FakeHistoryDao : RecognitionHistoryDao {
        val rows = MutableStateFlow<List<RecognitionHistoryEntity>>(emptyList())
        var pruned = 0

        override suspend fun insert(entry: RecognitionHistoryEntity) {
            rows.value = listOf(entry) + rows.value
        }

        override fun observe(limit: Int): Flow<List<RecognitionHistoryEntity>> = rows.map { it.take(limit) }

        override suspend fun delete(id: String) {
            rows.value = rows.value.filterNot { it.id == id }
        }

        override suspend fun prune(keep: Int) {
            pruned++
        }

        override suspend fun clear() {
            rows.value = emptyList()
        }
    }

    private fun TestScope.repository(
        recorder: FakeRecorder = FakeRecorder(),
        provider: FakeProvider = FakeProvider(RecognitionOutcome.Matched(match)),
        resolver: FakeResolver = FakeResolver(resolved),
        dao: FakeHistoryDao = FakeHistoryDao(),
    ) = RecognitionRepositoryImpl(
        recorder = recorder,
        provider = provider,
        resolver = resolver,
        historyDao = dao,
        io = UnconfinedTestDispatcher(testScheduler),
        nowIso = { "2026-08-06T12:00:00Z" },
        newId = { "event-1" },
    )

    /**
     * Records everything the screen would have rendered, in order.
     *
     * Collected on an unconfined dispatcher so the collector is already running *before* the session
     * starts — otherwise the first thing it ever sees is whatever state the machine had already
     * reached, and `Idle` is missed. On [TestScope.backgroundScope] so this endless collection does
     * not keep the test itself from finishing.
     */
    private fun TestScope.record(repo: RecognitionRepositoryImpl): List<RecognitionState> {
        val seen = mutableListOf<RecognitionState>()
        repo.state.onEach { seen += it }.launchIn(backgroundScope + UnconfinedTestDispatcher(testScheduler))
        return seen
    }

    // -- tests ----------------------------------------------------------------------------------

    @Test
    fun `a successful recognition walks idle to listening to processing to matched`() = runTest {
        val recorder = FakeRecorder().apply { holdMs = 100 }
        val provider = FakeProvider(RecognitionOutcome.Matched(match)).apply { holdMs = 100 }
        val repo = repository(recorder = recorder, provider = provider)
        val seen = record(repo)

        repo.start()
        advanceUntilIdle()

        assertTrue(seen.first() is RecognitionState.Idle)
        assertTrue(seen.any { it is RecognitionState.Listening })
        assertTrue(seen.any { it is RecognitionState.Processing })
        val last = seen.last() as RecognitionState.Matched
        assertEquals("Get Lucky", last.match.title)
        assertEquals("67238735", last.resolvedTrack?.source?.id)
    }

    @Test
    fun `the meter reports what the microphone is hearing`() = runTest {
        val repo = repository(recorder = FakeRecorder().apply { holdMs = 100 })
        val seen = record(repo)

        repo.start()
        advanceUntilIdle()

        assertTrue(seen.filterIsInstance<RecognitionState.Listening>().any { it.amplitude == 0.5f })
    }

    @Test
    fun `an unknown song is not an error`() = runTest {
        val repo = repository(provider = FakeProvider(RecognitionOutcome.NoMatch))
        repo.start()
        advanceUntilIdle()

        assertEquals(RecognitionState.NoMatch, repo.state.value)
    }

    @Test
    fun `a service failure offers another try`() = runTest {
        val repo = repository(provider = FakeProvider(RecognitionOutcome.Failed(RecognitionError.NETWORK)))
        repo.start()
        advanceUntilIdle()

        val failed = repo.state.value as RecognitionState.Failed
        assertEquals(RecognitionError.NETWORK, failed.category)
        assertTrue(failed.retryable)
    }

    @Test
    fun `a revoked permission does not offer another try`() = runTest {
        val repo = repository(recorder = FakeRecorder().apply { failWith = RecognitionError.PERMISSION })
        repo.start()
        advanceUntilIdle()

        val failed = repo.state.value as RecognitionState.Failed
        assertEquals(RecognitionError.PERMISSION, failed.category)
        assertTrue(!failed.retryable)
    }

    @Test
    fun `a device with no microphone says so instead of listening`() = runTest {
        val provider = FakeProvider(RecognitionOutcome.Matched(match))
        val repo = repository(recorder = FakeRecorder().apply { available = false }, provider = provider)
        repo.start()
        advanceUntilIdle()

        assertEquals(RecognitionError.MICROPHONE_UNAVAILABLE, (repo.state.value as RecognitionState.Failed).category)
        assertEquals(0, provider.calls)
    }

    @Test
    fun `cancelling while listening frees the microphone and returns to idle`() = runTest {
        val recorder = FakeRecorder().apply { holdMs = 10_000 }
        val provider = FakeProvider(RecognitionOutcome.Matched(match))
        val repo = repository(recorder = recorder, provider = provider)

        repo.start()
        assertTrue(repo.state.value is RecognitionState.Listening)
        repo.cancel()
        advanceUntilIdle()

        assertEquals(RecognitionState.Idle, repo.state.value)
        assertTrue("the recorder must run its cleanup", recorder.released)
        assertEquals("nothing should have been sent", 0, provider.calls)
    }

    @Test
    fun `cancelling while the service is answering returns to idle`() = runTest {
        val repo = repository(provider = FakeProvider(RecognitionOutcome.Matched(match)).apply { holdMs = 10_000 })

        // No `advanceUntilIdle` here: it would run the virtual clock past the service's delay and the
        // answer would already have arrived, leaving nothing to cancel.
        repo.start()
        assertTrue(repo.state.value is RecognitionState.Processing)
        repo.cancel()
        advanceUntilIdle()

        assertEquals(RecognitionState.Idle, repo.state.value)
    }

    @Test
    fun `an abandoned session can no longer repaint the screen`() = runTest {
        val recorder = FakeRecorder().apply { holdMs = 10_000 }
        val repo = repository(recorder = recorder)

        repo.start()
        val stale = recorder.amplitudeSink!!
        repo.cancel()
        advanceUntilIdle()

        // Exactly what a late callback from the abandoned capture would do.
        stale(0.9f)

        assertEquals(RecognitionState.Idle, repo.state.value)
    }

    @Test
    fun `pressing listen twice does not open a second microphone`() = runTest {
        val provider = FakeProvider(RecognitionOutcome.Matched(match))
        val repo = repository(recorder = FakeRecorder().apply { holdMs = 1_000 }, provider = provider)

        repo.start()
        repo.start()
        advanceUntilIdle()

        assertEquals(1, provider.calls)
    }

    @Test
    fun `a match is written to the history exactly once`() = runTest {
        val dao = FakeHistoryDao()
        val repo = repository(dao = dao)

        repo.start()
        advanceUntilIdle()

        assertEquals(1, dao.rows.value.size)
        val row = dao.rows.value.single()
        assertEquals("Get Lucky", row.title)
        assertEquals("USQX91300108", row.isrc)
        assertEquals("deezer", row.resolvedProvider)
        assertEquals("2026-08-06T12:00:00Z", row.recognizedAtIso)
        assertEquals(1, dao.pruned)
    }

    @Test
    fun `nothing that could identify the listener is written down`() = runTest {
        val dao = FakeHistoryDao()
        repository(dao = dao).apply { start() }
        advanceUntilIdle()

        val row = dao.rows.value.single()
        // No audio, no fingerprint, no response body — and the encoded track carries no stream URL.
        assertTrue(row.resolvedTrackJson!!.contains("\"title\""))
        assertTrue(!row.resolvedTrackJson!!.contains("streamCandidates"))
    }

    @Test
    fun `an unresolvable song is still recognised, remembered and shown`() = runTest {
        val dao = FakeHistoryDao()
        val repo = repository(resolver = FakeResolver(track = null), dao = dao)

        repo.start()
        advanceUntilIdle()

        val state = repo.state.value as RecognitionState.Matched
        assertEquals("Get Lucky", state.match.title)
        assertNull(state.resolvedTrack)
        assertNull(dao.rows.value.single().resolvedProvider)
    }

    @Test
    fun `a catalogue that throws does not cost the user their result`() = runTest {
        val repo = repository(resolver = FakeResolver(track = null, boom = true))

        repo.start()
        advanceUntilIdle()

        val state = repo.state.value as RecognitionState.Matched
        assertEquals("Get Lucky", state.match.title)
        assertNull(state.resolvedTrack)
    }

    @Test
    fun `a nothing-happened attempt can be retried straight away`() = runTest {
        val provider = FakeProvider(RecognitionOutcome.Failed(RecognitionError.NETWORK))
        val repo = repository(provider = provider)

        repo.start()
        advanceUntilIdle()
        provider.outcome = RecognitionOutcome.Matched(match)
        repo.start()
        advanceUntilIdle()

        assertTrue(repo.state.value is RecognitionState.Matched)
        assertEquals(2, provider.calls)
    }
}
