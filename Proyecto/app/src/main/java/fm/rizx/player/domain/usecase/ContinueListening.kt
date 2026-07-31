package fm.rizx.player.domain.usecase

import fm.rizx.player.domain.model.PlayStat
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track

/**
 * The Home's "Continue listening" row: a way back into your own music, not a log.
 *
 * The row used to be `history.take(12)` — literally the last twelve songs. That answers one question
 * ("what was I playing?") and no other: everything you love but did not play this morning is invisible,
 * and the row looks identical every time you open the app. This composes three answers instead, in
 * fixed slots ([PATTERN]):
 *
 * | | |
 * |---|---|
 * | **recent** | what you were playing, newest first |
 * | **on repeat** | what you keep coming back to ([MIN_REPEAT_PLAYS]+ plays) |
 * | **rediscover** | something you liked enough to replay and have not heard in [REDISCOVER_AFTER_DAYS] days |
 *
 * Two rules the row lives by:
 *
 * - **Slot 0 is always the last thing played.** That is what "continue listening" means; a row that
 *   opens on a song from March would be a different feature wearing this one's name.
 * - **Only songs actually played may appear.** No recommendations, nothing "similar", nothing new — the
 *   row's title is a promise about where these songs come from.
 *
 * The two non-recent pools **rotate with the day**, so the row is not the same three old songs every
 * morning, and stays put for the whole day: pure and deterministic, so it never reshuffles under a
 * thumb. With a thin history every pool but "recent" is empty and the row degrades to exactly what it
 * was before — plain recency.
 */
object ContinueListening {

    private enum class Slot { RECENT, REPEAT, REDISCOVER }

    /** Repeated to fill the row: eight recent, two on-repeat, two rediscovered over twelve slots. */
    private val PATTERN = listOf(Slot.RECENT, Slot.RECENT, Slot.REPEAT, Slot.RECENT, Slot.REDISCOVER)

    /**
     * The row, at most [limit] tracks. [dayEpoch] is the day number (`LocalDate.toEpochDay()`) and is
     * the only thing that changes the selection when nothing has been played.
     */
    fun build(profile: TasteProfile, dayEpoch: Long = 0L, limit: Int = SLOTS): List<Track> {
        if (limit <= 0) return emptyList()
        val recent = ArrayDeque(profile.recentFirst)
        val onRepeat = ArrayDeque(
            profile.mostPlayed.filter { it.plays >= MIN_REPEAT_PLAYS }.rotatedBy(dayEpoch),
        )
        val rediscover = ArrayDeque(
            profile.recentFirst
                .filter { stat ->
                    (profile.ageDays(stat) ?: 0f) >= REDISCOVER_AFTER_DAYS &&
                        // Played more than once, or by an artist they return to — the second half
                        // matters because every song counts as one play until the log has been
                        // running a while, and an empty shelf is not a feature.
                        (stat.plays >= MIN_REDISCOVER_PLAYS || profile.playsOf(stat.track) >= MIN_REDISCOVER_PLAYS)
                }
                .rotatedBy(dayEpoch),
        )

        val out = ArrayList<Track>(limit)
        val used = HashSet<ProviderRef>()

        fun take(pool: ArrayDeque<PlayStat>): Boolean {
            while (pool.isNotEmpty()) {
                val stat = pool.removeFirst()
                // A song can qualify for two pools; it may still only take one slot.
                if (used.add(stat.track.source)) {
                    out += stat.track
                    return true
                }
            }
            return false
        }

        while (out.size < limit) {
            // A slot whose own pool has run dry is handed to the next-best one rather than left empty:
            // a short row reads as a bug, and every pool holds songs this listener has played.
            val filled = when (PATTERN[out.size % PATTERN.size]) {
                Slot.RECENT -> take(recent) || take(onRepeat) || take(rediscover)
                Slot.REPEAT -> take(onRepeat) || take(recent) || take(rediscover)
                Slot.REDISCOVER -> take(rediscover) || take(recent) || take(onRepeat)
            }
            if (!filled) break // nothing left anywhere
        }
        return out
    }

    /** One carousel's worth — the row is a way back in, not the whole log. */
    const val SLOTS = 12

    /** Below this a song is not "on repeat", it is just a song you played twice. */
    const val MIN_REPEAT_PLAYS = 3

    /** Rediscovery needs evidence it was liked: something played once and dropped is not a memory. */
    const val MIN_REDISCOVER_PLAYS = 2

    /** Three weeks away is long enough that hearing it again is a small event. */
    const val REDISCOVER_AFTER_DAYS = 21f
}
