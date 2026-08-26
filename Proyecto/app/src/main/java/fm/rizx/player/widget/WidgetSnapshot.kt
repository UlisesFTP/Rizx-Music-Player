package fm.rizx.player.widget

import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.model.thumbnailUrl

/**
 * Everything a home screen widget shows, frozen at one moment. Pure data so the rendering can be
 * driven from the playback service, from a cold provider update reading the disk snapshot, and from
 * tests, all through the same door.
 */
data class WidgetSnapshot(
    val title: String? = null,
    val artist: String? = null,
    val artworkUrl: String? = null,
    /** `provider:id` of the track, so a re-render can tell "same song, new position" from a change of song. */
    val identityKey: String? = null,
    val isPlaying: Boolean = false,
    val liked: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    /** The track itself, kept only so a cover can be borrowed for songs that arrived without one. */
    val track: Track? = null,
) {
    val hasTrack: Boolean get() = title != null

    /** 0..1 of the song, 0 when the duration is unknown. */
    val progress: Float
        get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    companion object {
        val EMPTY = WidgetSnapshot()

        fun of(track: Track?, isPlaying: Boolean, liked: Boolean, positionMs: Long, durationMs: Long?): WidgetSnapshot {
            if (track == null) return EMPTY
            return WidgetSnapshot(
                title = track.title,
                artist = track.artists.joinToString { it.name }.ifBlank { null },
                artworkUrl = track.artwork.thumbnailUrl(),
                identityKey = track.source.identityKey,
                isPlaying = isPlaying,
                liked = liked,
                positionMs = positionMs.coerceAtLeast(0L),
                durationMs = (durationMs?.takeIf { it > 0L } ?: track.durationMs ?: 0L),
                track = track,
            )
        }
    }
}

/** `m:ss`, or `h:mm:ss` past the hour; never negative. */
fun formatClock(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L) / 1_000L)
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}
