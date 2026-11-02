package fm.rizx.player.data.discovery

import fm.rizx.player.core.concurrent.SingleFlight
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A small time-boxed memo with request collapsing, shared by the discovery sources.
 *
 * Editorial playlists change weekly at most, and the Home asks for the same artists on every refresh,
 * so the answer is kept for [ttlMs] (6 h by default). Concurrent askers share one request
 * ([SingleFlight]); a failure is remembered as empty for [missTtlMs] only, so a flaky network costs a
 * few minutes of a missing shelf and not six hours.
 */
class DiscoveryMemo<V>(
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val ttlMs: Long = 6 * 60 * 60_000L,
    private val missTtlMs: Long = 5 * 60_000L,
    private val maxEntries: Int = 64,
    private val empty: V,
) {
    private val flight = SingleFlight<String, V>()
    private val lock = Mutex()
    private val entries = LinkedHashMap<String, Entry<V>>()

    suspend fun get(key: String, isEmpty: (V) -> Boolean, load: suspend () -> V): V {
        lock.withLock { entries[key]?.takeIf { nowMs() - it.atMs < it.ttlMs } }?.let { return it.value }
        val value = try {
            flight.run(key) { load() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            empty
        }
        lock.withLock {
            entries[key] = Entry(nowMs(), value, if (isEmpty(value)) missTtlMs else ttlMs)
            while (entries.size > maxEntries) entries.keys.firstOrNull()?.let(entries::remove) ?: break
        }
        return value
    }

    private class Entry<V>(val atMs: Long, val value: V, val ttlMs: Long)
}
