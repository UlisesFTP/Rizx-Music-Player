package fm.rizx.player.domain.lossless

import fm.rizx.player.domain.model.Track

/**
 * A provider that can also answer *"is there a lossless file for this track?"*.
 *
 * Separate from [fm.rizx.player.domain.provider.StreamingProvider] rather than folded into it because
 * the two answer different questions and carry different shapes: a `StreamCandidate` has no artist
 * field, and the artist is exactly the signal that stops a same-titled recording by somebody else from
 * being played. So the index keeps its own row type.
 *
 * Implemented by the plugin bridge, which is how the index stays **out of this repository**: Rizx ships
 * the matching, the verification and the playback, and whoever installs a plugin supplies the list of
 * URLs. A plugin that doesn't expose the method reports [hasLosslessIndex] false and costs nothing.
 */
interface LosslessIndexProvider {

    /** Whether the backing plugin actually exposes an index. False for every ordinary provider. */
    val hasLosslessIndex: Boolean get() = false

    /**
     * Whether serving the index is *all* this provider does.
     *
     * Such a provider is kept out of the normal streaming fallback chain: it has no ordinary search to
     * contribute, and consulting it in Standard mode would be exactly the extra request the feature
     * promises not to make.
     */
    val isLosslessOnly: Boolean get() = false

    /** Candidate rows for [track]. Empty is the normal "not in this index" answer, never an error. */
    suspend fun losslessLookup(track: Track): List<LosslessIndexItem> = emptyList()
}

/**
 * Every installed index, asked as one.
 *
 * [isAvailable] is what makes "Prefer Lossless" a real setting rather than a dead switch: with no index
 * plugin installed there is nothing for the mode to do, so the row says so instead of silently never
 * working. It is a poll rather than a flow because the provider registry has no change stream — the
 * screen re-reads it when it comes back to the front, which is exactly when a plugin can have appeared.
 */
interface LosslessIndexSource {

    /** Whether any enabled plugin publishes an index right now. */
    fun isAvailable(): Boolean

    /** Rows from every index that has one for [track]. Empty is the normal answer, never an error. */
    suspend fun lookup(track: Track): List<LosslessIndexItem>
}

/**
 * Picks the index rows that could plausibly be [track], newest evidence first.
 *
 * Two stages, because the minimal index shape gives nothing to corroborate with: [candidates] can only
 * weigh title and artist, and [confirmWithDuration] applies the duration once the file's own header has
 * been read. A candidate that never reaches the second stage is never played.
 */
interface LosslessMatcher {

    /** Stage one — metadata only, no network. Ordered best first; empty when nothing is close enough. */
    fun candidates(track: Track, items: List<LosslessIndexItem>): List<LosslessCandidate>

    /**
     * Stage two — the file's real duration against the track's. Returns the candidate re-scored, or
     * `null` when the header disagrees badly enough that this is a different recording.
     */
    fun confirmWithDuration(track: Track, candidate: LosslessCandidate, flacDurationMs: Long): LosslessCandidate?

    /**
     * Whether the top two are too close to choose between — same rule as the canvas matcher, for the
     * same reason: a source that offers two equally-good different files has not identified anything.
     */
    fun tooCloseToCall(a: Int, b: Int): Boolean
}

/** Reads a remote file's FLAC header. Returns `null` for anything that isn't a valid FLAC bitstream. */
interface FlacInspector {
    suspend fun inspect(url: String): FlacStreamInfo?
}

/**
 * The whole feature behind one call: settings, index, matching, verification and cache.
 *
 * Returns `null` for every ordinary "no" — feature off, wrong quality mode, no plugin installed, on
 * cellular with Wi-Fi-only set, nothing matched, nothing verified, the server was down. The caller
 * treats all of those the same way, by carrying on with the normal resolver, which is what makes a
 * missing FLAC invisible to the user.
 */
interface LosslessResolver {
    suspend fun resolve(track: Track): ValidatedLosslessStream?

    /** Forgets a track's verdict — used when a file that verified fine then failed to play. */
    suspend fun invalidate(trackKey: String)
}
