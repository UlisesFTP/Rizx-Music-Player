package fm.rizx.player.core.concurrent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Collapses concurrent calls for the same key into **one** execution, the rest waiting on its result.
 *
 * The `groupcache` pattern. It exists because several unrelated parts of the app legitimately want the
 * same answer at the same moment — the player resolving the current artist while the queue's radio
 * refill resolves the same artist to seed itself — and each would otherwise make its own request.
 *
 * This is *not* a cache: nothing is retained after a call finishes. Pair it with a memo (or, for HTTP,
 * with the OkHttp disk cache) when repeats over time also matter.
 *
 * A failure propagates to everyone waiting, exactly as if each had called [block] itself. Cancellation
 * of the leader does **not** cancel the followers: they get a [CancellationException] of their own to
 * fail with rather than inheriting a scope they never joined, so one screen going away cannot cancel
 * work another screen is waiting on.
 */
class SingleFlight<K, V> {

    private val lock = Mutex()
    private val inFlight = mutableMapOf<K, CompletableDeferred<V>>()

    suspend fun run(key: K, block: suspend () -> V): V {
        val (deferred, isLeader) = lock.withLock {
            inFlight[key]?.let { return@withLock it to false }
            CompletableDeferred<V>().also { inFlight[key] = it } to true
        }
        if (!isLeader) return deferred.await()

        return try {
            block().also { deferred.complete(it) }
        } catch (e: CancellationException) {
            deferred.completeExceptionally(CancellationException("single-flight leader was cancelled"))
            throw e
        } catch (e: Throwable) {
            deferred.completeExceptionally(e)
            throw e
        } finally {
            lock.withLock { inFlight.remove(key) }
        }
    }
}
