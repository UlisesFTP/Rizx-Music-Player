package fm.rizx.player.data.canvas

import fm.rizx.player.domain.model.CanvasCandidate
import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers what the providers said about a track, for a while.
 *
 * **Memory only, never persisted.** A resolved canvas URL is a signed, expiring token exactly like a
 * stream URL, and this project's rule is that those are never written down. A restart re-resolves; that
 * is the correct cost.
 *
 * Three lifetimes, because the three answers are worth remembering for very different lengths of time:
 *
 * - **a hit** is good until its URL dies. The previous cache kept hits for the life of the process,
 *   which meant that after a few hours of listening every canvas silently failed to play — the entry
 *   was still there, the token behind it wasn't.
 * - **a miss** ("this song has no video") lasts twenty minutes. Long enough that skipping back and
 *   forth costs nothing, short enough that a song which missed because the network hiccuped gets
 *   another chance in the same sitting. The old cache kept a miss forever.
 * - **an error** lasts two minutes, because the thing that caused it is usually already over.
 */
class CanvasResolutionCache(
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** identityKey → what we know, and until when. */
    private val entries = ConcurrentHashMap<String, Entry>()

    /**
     * What is remembered for [key], or `null` if nothing usable is.
     *
     * An expired entry is dropped on the way out, so a stale token can never be handed to the player.
     */
    fun get(key: String): CanvasCacheEntry? {
        val entry = entries[key] ?: return null
        if (now() >= entry.expiresAtMs) {
            entries.remove(key, entry)
            return null
        }
        return CanvasCacheEntry(entry.candidate, entry.spares, entry.resolvedAtMs, entry.expiresAtMs)
    }

    /**
     * Remembers a candidate until [POSITIVE_TTL_MS], or until its own URL expires — whichever is first.
     *
     * [spares] are the same provider's other answers, remembered alongside so that coming back to a song
     * whose first candidate failed doesn't re-extract to reach the second.
     */
    fun putHit(key: String, candidate: CanvasCandidate, spares: List<CanvasCandidate> = emptyList()) {
        val at = now()
        val ttlEnd = at + POSITIVE_TTL_MS
        // The margin matters: a URL that dies mid-loop restarts the video as an error the user can see,
        // whereas re-resolving a minute early costs one extraction nobody notices.
        val urlEnd = (listOf(candidate) + spares).mapNotNull { it.expiresAtMs }.minOrNull()
            ?.minus(EXPIRY_MARGIN_MS)
        val until = if (urlEnd != null) minOf(ttlEnd, urlEnd) else ttlEnd
        // A URL that is already past its expiry is not worth remembering at all.
        if (until <= at) return
        entries[key] = Entry(candidate, spares, at, until)
    }

    /** Remembers that no provider had a video for this track. */
    fun putMiss(key: String) {
        val at = now()
        entries[key] = Entry(null, emptyList(), at, at + NEGATIVE_TTL_MS)
    }

    /** Remembers a failure, briefly. */
    fun putError(key: String) {
        val at = now()
        entries[key] = Entry(null, emptyList(), at, at + ERROR_TTL_MS)
    }

    fun clear() = entries.clear()

    private data class Entry(
        val candidate: CanvasCandidate?,
        val spares: List<CanvasCandidate>,
        val resolvedAtMs: Long,
        val expiresAtMs: Long,
    )

    companion object {
        const val POSITIVE_TTL_MS = 4 * 60 * 60 * 1000L
        const val NEGATIVE_TTL_MS = 20 * 60 * 1000L
        const val ERROR_TTL_MS = 2 * 60 * 1000L

        /** Stop trusting a signed URL this long before it actually expires. */
        const val EXPIRY_MARGIN_MS = 5 * 60 * 1000L
    }
}

/** A cached answer. [candidate] is null for a remembered miss or error; [spares] are the runners-up. */
data class CanvasCacheEntry(
    val candidate: CanvasCandidate?,
    val spares: List<CanvasCandidate> = emptyList(),
    val resolvedAtMs: Long,
    val expiresAtMs: Long,
)
