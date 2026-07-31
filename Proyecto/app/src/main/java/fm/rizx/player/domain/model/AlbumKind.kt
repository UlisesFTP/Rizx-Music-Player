package fm.rizx.player.domain.model

/**
 * What kind of record a release is.
 *
 * A discography read as one undifferentiated list is unusable for a prolific artist: forty entries
 * where a two-track single sits between two studio albums. Every catalogue publishes this distinction
 * (Deezer as `record_type`), so the app keeps it rather than flattening it — the artist page shows
 * albums and singles as separate shelves, the way every music service does.
 *
 * [UNKNOWN] is the honest answer for a source that says nothing; those releases are shown with the
 * albums, which is where an unlabelled record most likely belongs.
 */
enum class AlbumKind {
    ALBUM,
    SINGLE,
    EP,
    COMPILATION,
    UNKNOWN,
    ;

    companion object {
        /** Maps a catalogue's own wording ("single", "EP", "compilation"…) onto a family. */
        fun of(raw: String?): AlbumKind = when (raw?.trim()?.lowercase()) {
            "album" -> ALBUM
            "single" -> SINGLE
            "ep" -> EP
            "compilation" -> COMPILATION
            else -> UNKNOWN
        }
    }
}
