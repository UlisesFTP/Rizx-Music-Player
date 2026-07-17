package fm.rizx.player.core

import kotlin.math.floor
import kotlin.math.max

/** Formats seconds as `m:ss` (e.g. 84 -> "1:24"). */
fun formatClock(seconds: Double): String {
    val total = max(0.0, floor(seconds)).toInt()
    val m = total / 60
    val s = total % 60
    return "$m:${s.toString().padStart(2, '0')}"
}

/** Formats a millisecond duration as `m:ss`; blank when null. */
fun formatDuration(ms: Long?): String = if (ms == null) "" else formatClock(ms / 1000.0)
