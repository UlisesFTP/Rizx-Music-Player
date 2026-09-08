package fm.rizx.player.ui.util

import fm.rizx.player.domain.model.Track

/**
 * The little arithmetic the Library's editorial surfaces print — running times, artist counts, the
 * four-cover collage. Pure, so the numbers on screen are checked by JVM tests rather than by eye.
 */
object LibraryStats {

    /** `3 h 24 min` / `18 min`, exactly as the feed design writes a running time. */
    fun formatLongDuration(ms: Long): String {
        val minutes = (ms / 60_000.0).let { Math.round(it) }
        val hours = minutes / 60
        return if (hours > 0) "$hours h ${padded((minutes % 60).toInt())} min" else "$minutes min"
    }

    /** `3:07` — a track's running time; an unknown one prints as `–:––` rather than as zero. */
    fun clock(ms: Long?): String {
        if (ms == null || ms < 0L) return "–:––"
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "$minutes:${seconds.toString().padStart(2, '0')}"
    }

    /** `01`, `09`, `12` — the two-digit serials on tiles and eyebrows. */
    fun padded(value: Int): String = value.toString().padStart(2, '0')

    /** Sum of the known durations; a track with no duration counts as zero rather than poisoning the total. */
    fun totalMs(tracks: List<Track>): Long = tracks.sumOf { it.durationMs ?: 0L }

    /** Distinct credited artists, case-insensitively — "Rosalía" and "ROSALÍA" are one person. */
    fun distinctArtists(tracks: List<Track>): Int =
        tracks.asSequence().flatMap { it.artists.asSequence() }.map { it.name.trim().lowercase() }
            .filter { it.isNotEmpty() }.toSet().size

    /**
     * The first four entries, cycled so a short list still fills a 2×2 collage; an empty list stays
     * empty so the caller can draw its fallback instead of four blanks.
     */
    fun <T> collage(items: List<T>): List<T> =
        if (items.isEmpty()) emptyList() else List(4) { index -> items[index % items.size] }
}
