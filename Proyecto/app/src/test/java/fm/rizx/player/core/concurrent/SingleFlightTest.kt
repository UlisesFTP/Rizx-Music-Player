package fm.rizx.player.core.concurrent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SingleFlightTest {

    @Test
    fun `callers sharing a key share one execution`() = runTest {
        val flight = SingleFlight<String, Int>()
        val runs = AtomicInteger()
        val gate = CompletableDeferred<Unit>()

        val calls = (1..5).map { async { flight.run("k") { gate.await(); runs.incrementAndGet() } } }
        // The test scheduler runs coroutines one at a time, so without this the first call would have
        // finished — and freed its slot — before the second even started, and nothing would overlap.
        testScheduler.runCurrent()
        gate.complete(Unit)

        assertEquals(listOf(1, 1, 1, 1, 1), calls.awaitAll())
        assertEquals(1, runs.get())
    }

    @Test
    fun `different keys don't share anything`() = runTest {
        val flight = SingleFlight<String, String>()
        val gate = CompletableDeferred<Unit>()

        val calls = listOf("a", "b").map { key -> async { flight.run(key) { gate.await(); key.uppercase() } } }
        testScheduler.runCurrent()
        gate.complete(Unit)

        assertEquals(listOf("A", "B"), calls.awaitAll())
    }

    @Test
    fun `a failure reaches everyone waiting`() = runTest {
        val flight = SingleFlight<String, Int>()
        val gate = CompletableDeferred<Unit>()

        // `runCatching` inside each: an `async` that throws would otherwise sink the whole test scope.
        val leader = async { runCatching { flight.run("k") { gate.await(); throw IOException("down") } } }
        val follower = async { runCatching { flight.run("k") { error("the follower must not run") } } }
        testScheduler.runCurrent()
        gate.complete(Unit)

        assertTrue(leader.await().exceptionOrNull() is IOException)
        assertTrue("the follower should fail rather than silently succeed", follower.await().isFailure)
    }

    @Test
    fun `the slot is released, so the next call runs again`() = runTest {
        // Request collapsing, not caching — nothing is retained once a call finishes.
        val flight = SingleFlight<String, Int>()
        val runs = AtomicInteger()

        flight.run("k") { runs.incrementAndGet() }
        flight.run("k") { runs.incrementAndGet() }

        assertEquals(2, runs.get())
    }
}
