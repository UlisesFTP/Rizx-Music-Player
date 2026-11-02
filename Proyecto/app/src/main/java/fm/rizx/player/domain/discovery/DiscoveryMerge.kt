package fm.rizx.player.domain.discovery

import fm.rizx.player.domain.model.PlaylistRef

/**
 * Joins several platforms' answers into one shelf, **round-robin**: first of each, then second of each.
 *
 * Concatenating would hand the whole visible head of the row to whichever platform answered first, so a
 * shelf that promises Spotify, Apple, Deezer and YouTube would look like a Deezer shelf. Identity is the
 * playlist's source key, so the same playlist offered twice (it happens: two queries, one result) is one
 * card. Pure — no IO.
 */
object DiscoveryMerge {

    fun interleave(lists: List<List<PlaylistRef>>, limit: Int): List<PlaylistRef> {
        if (limit <= 0) return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<PlaylistRef>(limit)
        val depth = lists.maxOfOrNull { it.size } ?: 0
        for (rank in 0 until depth) {
            for (list in lists) {
                val item = list.getOrNull(rank) ?: continue
                if (seen.add(item.source.identityKey)) out += item
                if (out.size == limit) return out
            }
        }
        return out
    }
}
