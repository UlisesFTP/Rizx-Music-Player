package fm.rizx.player.data.sync

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Whether two cloud documents say the same thing — the test behind "nothing to apply". */
object SyncDocuments {
    const val PLAYLIST = "PLAYLIST"
    const val FAVORITE = "FAVORITE"
    const val TASTE = "TASTE"
    val TYPES = setOf(PLAYLIST, FAVORITE, TASTE)

    /** A playlist export is stamped when it is made; the stamp is not part of the playlist. */
    private val VOLATILE = setOf("exportedAtIso")

    fun same(entityType: String, a: JsonElement?, b: JsonElement?): Boolean {
        if (a == null || b == null) return a == b
        return if (entityType == PLAYLIST) strip(a) == strip(b) else a == b
    }

    private fun strip(element: JsonElement): JsonElement =
        (element as? JsonObject)?.let { JsonObject(it.filterKeys { key -> key !in VOLATILE }) } ?: element
}
