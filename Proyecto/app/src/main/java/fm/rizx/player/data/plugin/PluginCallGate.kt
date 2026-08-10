package fm.rizx.player.data.plugin

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

/** The engine is busy with somebody else's work. Nobody's fault, and nobody's failure to record. */
class PluginBusyException : Exception("plugin runtime busy")

/** This call's own budget ran out while it was executing. */
class PluginCallTimeoutException : Exception("plugin call timed out")

/**
 * Admission control for the single QuickJS thread every plugin shares.
 *
 * It exists for one reason: **a call's budget must measure its own execution, not the queue.** All
 * plugin work is serialized on one engine thread, but the timeout used to be wrapped around the wait
 * as well, so a call could spend its whole budget queued behind another plugin and then "time out"
 * having never run. Worse, the runtime charged that to the plugin that waited, so a Home load fanning
 * out several providers at once quarantined healthy plugins.
 *
 * So the wait comes first and is bounded separately: losing that race is [PluginBusyException], which
 * is a statement about the engine, not about the caller. Only [PluginCallTimeoutException] means the
 * plugin itself was too slow.
 *
 * Extracted from `JsPluginRuntime` because that class builds a native QuickJS engine in a property
 * initializer and cannot be instantiated in a JVM test — this policy is the part worth testing.
 */
class PluginCallGate(private val queueBudgetMs: Long = QUEUE_BUDGET_MS) {

    private val gate = Semaphore(1)

    /**
     * Waits for the engine (up to [queueBudgetMs]), then runs [block] with its own [timeoutMs].
     *
     * @throws PluginBusyException if the engine never came free
     * @throws PluginCallTimeoutException if [block] outlived its budget
     */
    suspend fun <T> run(timeoutMs: Long, block: suspend () -> T): T {
        // `acquired` closes the race where the permit is won exactly as the budget expires: without it
        // that permit is never released and the engine looks busy for the rest of the session.
        var acquired = false
        val entered = withTimeoutOrNull(queueBudgetMs) { gate.acquire(); acquired = true } != null
        if (!entered) {
            if (acquired) gate.release()
            throw PluginBusyException()
        }
        try {
            return withTimeout(timeoutMs) { block() }
        } catch (e: TimeoutCancellationException) {
            // Cancelled from outside — a caller's own budget (the health probe's), or the user leaving
            // a screen mid-load — arrives here as a TimeoutCancellationException too. Rethrowing the
            // cancellation keeps it out of the plugin's failure count, which is what used to make
            // healthy plugins quarantine themselves.
            coroutineContext.ensureActive()
            throw PluginCallTimeoutException()
        } finally {
            gate.release()
        }
    }

    private companion object {
        /**
         * How long a call will queue before giving up. Generous, because queueing is normal — a Home
         * load legitimately asks several providers at once — but bounded, so a plugin that wedges the
         * engine leaves the screen with empty sections instead of loading forever.
         */
        const val QUEUE_BUDGET_MS = 45_000L
    }
}
