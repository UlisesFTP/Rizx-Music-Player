package fm.rizx.player.data.local.media

/** One audio document found while walking a picked folder. */
data class FolderAudioEntry(val uri: String, val displayName: String, val mimeType: String?)

/** What of the folder actually becomes the queue, and what was left out. */
data class FolderPlan(val entries: List<FolderAudioEntry>, val skipped: Int)

/**
 * Turns a folder walk into a play order — the pure half of "open a folder", so the ordering and the cap
 * test on the JVM while `DocumentsContract` stays in the repository.
 *
 * **Natural order by file name**, because that is the order the person who named the files intended:
 * `2 - Intro` before `10 - Outro`, which plain lexicographic order famously gets backwards. Numbers
 * embedded anywhere in the name compare as numbers; everything else compares case-insensitively.
 *
 * The cap is explicit and *reported* ([FolderPlan.skipped]) rather than silent: a 3 000-file dump
 * becomes a 500-song queue that says "y 2 500 más se quedaron fuera", not one that pretends it took
 * everything.
 */
fun planFolderQueue(entries: List<FolderAudioEntry>, maxEntries: Int = FOLDER_QUEUE_CAP): FolderPlan {
    val audio = entries.filter { it.mimeType?.startsWith("audio/") == true || it.mimeType == "application/ogg" }
    val ordered = audio.sortedWith(compareBy(NaturalOrder) { it.displayName })
    return FolderPlan(
        entries = ordered.take(maxEntries),
        skipped = (ordered.size - maxEntries).coerceAtLeast(0),
    )
}

const val FOLDER_QUEUE_CAP = 500

/** Case-insensitive natural comparison: digit runs compare numerically, text runs textually. */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ia = i
                while (ia < a.length && a[ia].isDigit()) ia++
                var jb = j
                while (jb < b.length && b[jb].isDigit()) jb++
                // Compare as numbers without parsing to Long (a 30-digit run must not overflow):
                // strip leading zeros, then longer run wins, then lexicographic on equal length.
                val na = a.substring(i, ia).trimStart('0')
                val nb = b.substring(j, jb).trimStart('0')
                val byLength = na.length.compareTo(nb.length)
                val cmp = if (byLength != 0) byLength else na.compareTo(nb)
                if (cmp != 0) return cmp
                i = ia
                j = jb
            } else {
                val cmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (cmp != 0) return cmp
                i++
                j++
            }
        }
        return (a.length - i).compareTo(b.length - j)
    }
}
