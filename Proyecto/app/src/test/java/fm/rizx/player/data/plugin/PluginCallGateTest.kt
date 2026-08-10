package fm.rizx.player.data.plugin

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Who gets blamed when a plugin call is slow.
 *
 * All plugin JS runs on one engine thread. The timeout used to wrap the *wait* for that thread as well
 * as the work, so a call could burn its whole budget queued behind another plugin, "time out" without
 * ever running, and be recorded as that plugin's failure — five of which disable it permanently. A Home
 * load asks several providers at once, so this fired on healthy plugins as soon as a few were installed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PluginCallGateTest {

    @Test
    fun `waiting for the engine does not spend the caller's own budget`() = runTest {
        val gate = PluginCallGate()

        // Holds the engine for 10 s.
        val slow = async { gate.run(timeoutMs = 30_000) { delay(10_000); "slow" } }
        runCurrent()

        // Queued behind it with a 1 s budget — far less than the wait. Its own work is instant, so it
        // must succeed: before the fix this threw, and the blame landed here rather than on `slow`.
        val quick = async { gate.run(timeoutMs = 1_000) { "quick" } }

        advanceTimeBy(11_000)
        assertEquals("slow", slow.await())
        assertEquals("quick", quick.await())
    }

    @Test
    fun `a call that is genuinely too slow times out as its own fault`() = runTest {
        val gate = PluginCallGate()
        try {
            gate.run(timeoutMs = 1_000) { delay(5_000) }
            fail("expected the call's own budget to run out")
        } catch (e: PluginCallTimeoutException) {
            // The runtime records this one against the plugin, which is correct.
        }
    }

    @Test
    fun `an engine that never frees up reports busy, not a plugin failure`() = runTest {
        val gate = PluginCallGate(queueBudgetMs = 500)
        val hog = async { gate.run(timeoutMs = 60_000) { delay(60_000) } }
        runCurrent()

        val waiter = async {
            runCatching { gate.run(timeoutMs = 10_000) { "never runs" } }.exceptionOrNull()
        }
        advanceTimeBy(600)

        // Busy is a statement about the engine. The distinction is the whole point: the caller must not
        // be charged a failure for somebody else holding the thread.
        assertTrue(waiter.await() is PluginBusyException)
        hog.cancel()
    }

    @Test
    fun `cancellation from outside is not turned into a timeout`() = runTest {
        val gate = PluginCallGate()
        var seen: Throwable? = null

        // An outer budget — exactly the shape of the health probe, which wraps each provider in 5 s.
        val job = launch {
            seen = runCatching {
                withTimeout(1_000) { gate.run(timeoutMs = 30_000) { delay(30_000) } }
            }.exceptionOrNull()
        }
        advanceTimeBy(2_000)
        job.join()

        // Must stay a cancellation. As a PluginCallTimeoutException the runtime would count it against
        // the plugin, so navigating away from a screen mid-load would quarantine it.
        assertTrue("was $seen", seen is CancellationException)
        assertTrue("was $seen", seen !is PluginCallTimeoutException)
    }

    @Test
    fun `the engine is free again after a call fails`() = runTest {
        val gate = PluginCallGate(queueBudgetMs = 1_000)
        runCatching { gate.run(timeoutMs = 100) { delay(5_000) } }
        // A leaked permit would make every later call report busy for the rest of the session.
        assertEquals("after", gate.run(timeoutMs = 1_000) { "after" })
    }
}
