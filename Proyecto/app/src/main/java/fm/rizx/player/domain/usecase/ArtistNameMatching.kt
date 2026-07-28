package fm.rizx.player.domain.usecase

/**
 * Reads a YouTube **channel** name as the **artist** name behind it.
 *
 * A YouTube-sourced track credits whoever uploaded it — "ModjoOfficial", "DualipaVEVO",
 * "Radiohead - Topic" — and no catalogue knows those. Verified live: `search/artist?q=ModjoOfficial`
 * comes back empty from Deezer, while `q=modjo` finds the band. So both the artist link in the player
 * and the cover-art lookup have to ask for the *artist*, not the channel.
 *
 * Pure and Android-free. Folding is [RecsBlender.nameKey], the same normalization the feed's dedup uses,
 * so "Rosalía" and "ROSALIA" agree here exactly as they do there.
 */
object ArtistNameMatching {

    private val blender = RecsBlender()

    /**
     * Every spelling that should count as this artist: the folded, space-free name, plus the same name
     * with a trailing channel word removed.
     *
     * Only *trailing* words go, and only when [MIN_STEM] characters survive — so "Music Soulchild"
     * keeps its "music", and "The Band" keeps its "band" rather than collapsing to a "the" that would
     * match any other "The …".
     */
    fun keys(name: String): Set<String> {
        val tokens = blender.nameKey(name).split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptySet()
        // Spaces are dropped so "Dua Lipa" and the channel "DuaLipaVEVO" can meet at all.
        val glued = tokens.joinToString("")
        val keys = mutableSetOf(glued)

        // "Radiohead - Topic": the channel word is its own token, at the end.
        if (tokens.size > 1 && tokens.last() in CHANNEL_WORDS) {
            tokens.dropLast(1).joinToString("").takeIf { it.length >= MIN_STEM }?.let(keys::add)
        }
        // "Official Arctic Monkeys": and sometimes at the front.
        if (tokens.size > 1 && tokens.first() in LEADING_CHANNEL_WORDS) {
            tokens.drop(1).joinToString("").takeIf { it.length >= MIN_STEM }?.let(keys::add)
        }
        // "ModjoOfficial" / "OfficialModjo": glued onto the name, either side.
        CHANNEL_WORDS
            .firstOrNull { glued.endsWith(it) && glued.length - it.length >= MIN_STEM }
            ?.let { keys += glued.dropLast(it.length) }
        LEADING_CHANNEL_WORDS
            .firstOrNull { glued.startsWith(it) && glued.length - it.length >= MIN_STEM }
            ?.let { keys += glued.drop(it.length) }

        return keys
    }

    /** True when [a] and [b] name the same artist, allowing for channel decoration on either side. */
    fun sameArtist(a: String, b: String): Boolean {
        val left = keys(a)
        return left.isNotEmpty() && keys(b).any { it in left }
    }

    /**
     * What to actually type into a catalogue search: the de-channelized name when the credit looks like
     * a channel, otherwise the name as given. One query, not two — the stem is strictly the better bet
     * when it exists, and identical to the input when it doesn't.
     */
    fun searchName(name: String): String {
        val trimmed = name.trim()
        val folded = blender.nameKey(trimmed).split(' ').filter { it.isNotEmpty() }.joinToString("")
        return keys(trimmed).firstOrNull { it != folded } ?: trimmed
    }

    /** [searchName] first, then the raw name — so a wrong guess still gets its chance. */
    fun queries(name: String): List<String> = listOf(searchName(name), name.trim())
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

    /**
     * What must be left after dropping a suffix for the remainder to still name an artist. Four, so
     * "The Band" keeps its "band" — stripping it would leave a stem short enough to collide with
     * anything.
     */
    private const val MIN_STEM = 4

    /**
     * How YouTube channels decorate the **end** of an artist's name. Longest first, so "officialvevo"
     * is tried before "vevo". Already folded (lowercase, no spaces or punctuation) to match
     * [RecsBlender.nameKey].
     */
    private val CHANNEL_WORDS = listOf(
        "officialvevo", "vevomusic", "officialmusic", "officialchannel",
        "official", "vevo", "topic", "channel", "music", "band", "tv", "hd",
    )

    /**
     * The ones that also appear at the **front** ("Official Arctic Monkeys"). A deliberately smaller
     * list: plenty of real names *start* with a word from the list above — "Band of Horses",
     * "TV on the Radio", "Music Soulchild" — and stripping those would be inventing an artist.
     */
    private val LEADING_CHANNEL_WORDS = listOf("officialvevo", "official", "vevo", "topic", "channel")
}
