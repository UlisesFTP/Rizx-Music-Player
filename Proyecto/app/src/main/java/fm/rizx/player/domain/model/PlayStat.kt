package fm.rizx.player.domain.model

/**
 * One track and **how the listener has treated it** — the unit the recommendation engine reasons about.
 *
 * A history of tracks alone cannot answer the questions a music app is expected to answer ("what do you
 * keep coming back to", "what have you not heard in months", "what do you skip"). Those need counting,
 * so this carries the counters the log keeps: how often it started, how often it finished, how often it
 * was taken off early, when it was first and last played, and at what times of day.
 *
 * Timestamps are ISO-8601 strings, like every other timestamp in the domain (NUCLEAR_UPSTREAM_STUDY.md
 * §2). A blank or unparseable one is an ordinary case, not an error — see `TasteProfile`.
 */
data class PlayStat(
    val track: Track,
    /** How many times playback started. One per queue item, so pausing and resuming is not two plays. */
    val plays: Int = 1,
    val completions: Int = 0,
    val skips: Int = 0,
    val msListened: Long = 0,
    val firstPlayedAtIso: String = "",
    val lastPlayedAtIso: String = "",
    /** Plays per [Daypart], indexed by its ordinal. */
    val dayparts: List<Int> = List(Daypart.entries.size) { 0 },
) {

    /**
     * How often it is heard out rather than dropped, `0f..1f`.
     *
     * With no outcome recorded at all this is [NEUTRAL_COMPLETION] — the honest answer to "we don't
     * know", and the value that leaves such a track weighted exactly as it was before the log started
     * counting. Treating a missing outcome as a failure would bury every track played before this
     * feature existed.
     */
    val completionRate: Float
        get() {
            val outcomes = completions + skips
            return if (outcomes <= 0) NEUTRAL_COMPLETION else completions.toFloat() / outcomes
        }

    /** Share of plays that ended early. */
    val skipRate: Float get() = if (plays <= 0) 0f else (skips.toFloat() / plays).coerceIn(0f, 1f)

    /** Share of this track's plays that happened in [part], `0f..1f`. */
    fun share(part: Daypart): Float {
        val total = dayparts.sum()
        return if (total <= 0) 0f else (dayparts.getOrElse(part.ordinal) { 0 }.toFloat() / total)
    }

    companion object {
        const val NEUTRAL_COMPLETION = 0.5f
    }
}

/**
 * The four parts of a day, as coarse as the signal deserves.
 *
 * Coarser than an hour on purpose: what matters is that the music someone plays on the way to work is
 * not the music they play at midnight, and a listener never accumulates enough plays for 24 buckets to
 * mean anything.
 */
enum class Daypart {
    NIGHT,
    MORNING,
    AFTERNOON,
    EVENING,
    ;

    companion object {
        fun ofHour(hour: Int): Daypart = when (hour) {
            in 0..5 -> NIGHT
            in 6..11 -> MORNING
            in 12..17 -> AFTERNOON
            else -> EVENING
        }
    }
}

/** How a play ended. */
enum class PlayOutcome {
    COMPLETED,
    SKIPPED,
    ;

    companion object {

        /**
         * What to make of a play that just ended, or `null` when it says nothing either way.
         *
         * [manual] is the only thing the caller has to judge: did the listener take the track off, or
         * did it end by itself? A track that ran out is finished by definition. One the listener
         * skipped is only a *rejection* if they left early — pressing next over the outro is how people
         * listen, and counting it against the song would slowly bury everything they play most.
         */
        fun of(listenedMs: Long, durationMs: Long?, manual: Boolean): PlayOutcome? {
            if (!manual) return COMPLETED
            val total = durationMs?.takeIf { it > 0 } ?: return null // unknown length: nothing to judge
            return if (listenedMs < total * SKIP_BEFORE) SKIPPED else COMPLETED
        }

        /** Leaving before this share of the track is a skip; after it, an early exit from a song heard. */
        const val SKIP_BEFORE = 0.35f
    }
}
