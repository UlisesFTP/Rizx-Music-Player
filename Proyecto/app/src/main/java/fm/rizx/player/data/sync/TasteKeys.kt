package fm.rizx.player.data.sync

/**
 * How a taste row is addressed in the cloud versus in the local outbox.
 *
 * Locally a row is `provider:sourceId` — the track. In the cloud it is `<deviceId>|provider:sourceId`,
 * because counters are per device: two phones each publish their own row for a track and every reader
 * adds them up. The prefix is put on at send time, not when the play is journaled, so a play recorded
 * before sign-in still lands under the device that made it.
 *
 * Rows from before this scheme carry no `|`. They are read back as the pseudo-device [LEGACY] and
 * retired by a delete addressed with [LEGACY_PREFIX] in the outbox, which goes out un-prefixed.
 */
object TasteKeys {
    const val SEPARATOR = '|'
    const val LEGACY = "legacy"
    const val LEGACY_PREFIX = "legacy|"

    data class Parsed(val deviceId: String, val provider: String, val sourceId: String) {
        val isLegacy: Boolean get() = deviceId == LEGACY
    }

    /** The id to send for an outbox row. */
    fun wireId(deviceId: String, outboxId: String): String =
        if (outboxId.startsWith(LEGACY_PREFIX)) outboxId.removePrefix(LEGACY_PREFIX) else "$deviceId$SEPARATOR$outboxId"

    /** A cloud id back into its parts, or null when it is not a taste key at all. */
    fun parse(wireId: String): Parsed? {
        val bar = wireId.indexOf(SEPARATOR)
        val deviceId = if (bar >= 0) wireId.substring(0, bar) else LEGACY
        val rest = if (bar >= 0) wireId.substring(bar + 1) else wireId
        val colon = rest.indexOf(':')
        if (deviceId.isEmpty() || colon <= 0 || colon == rest.length - 1) return null
        return Parsed(deviceId, rest.substring(0, colon), rest.substring(colon + 1))
    }

    fun isOwn(wireId: String, deviceId: String): Boolean = wireId.startsWith("$deviceId$SEPARATOR")
}
