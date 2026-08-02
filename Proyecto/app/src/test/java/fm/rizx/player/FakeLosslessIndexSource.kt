package fm.rizx.player

import fm.rizx.player.domain.lossless.LosslessIndexItem
import fm.rizx.player.domain.lossless.LosslessIndexSource
import fm.rizx.player.domain.model.Track

/**
 * A community index, in memory.
 *
 * [available] is separate from [rows] on purpose: "a plugin is installed" and "it has a row for this
 * song" are different facts, and most of the interesting behaviour lives in the gap between them —
 * an installed index that simply doesn't have the track is the *normal* case, not a failure.
 */
class FakeLosslessIndexSource(
    var available: Boolean = false,
    var rows: List<LosslessIndexItem> = emptyList(),
    /** Set to throw from [lookup], to prove a broken plugin can't stop a song from playing. */
    var failure: Exception? = null,
) : LosslessIndexSource {

    var lookups = 0
        private set

    override fun isAvailable(): Boolean = available

    override suspend fun lookup(track: Track): List<LosslessIndexItem> {
        lookups++
        failure?.let { throw it }
        return rows
    }
}
