package fm.rizx.player.domain.usecase

import fm.rizx.player.domain.model.AttributedResult
import fm.rizx.player.domain.model.ForYouSection
import fm.rizx.player.domain.model.HomeFeed

/**
 * Makes the Home feed say each thing **once**.
 *
 * The rows are assembled independently — two YT mixes seeded from the same taste, an artist radio over
 * that same taste, a chart — so the *same* song, album or artist legitimately comes back from several
 * of them. [RecsBlender] already dedups within a section; this closes the other axis, **across** rows,
 * using that blender's identity keys so "Corazón (feat. X)" and "Corazon" still count as one thing.
 *
 * Priority is reading order: the "For you" rows are rendered first and keep what they claim, then the
 * charts below drop anything already shown. A personalized row that falls under [minRowItems] after
 * filtering is dropped entirely rather than left as a stub. Pure — no IO, no Android.
 *
 * [dedupe]'s `feedFirst` flips that priority, and exists for one reason: the Home now renders the charts
 * as soon as they land and fills the "For you" rows in afterwards. Re-running the normal direction at
 * that point would yank items out of rows the user is already looking at, so once the feed is on screen
 * the feed wins and the arriving rows give way instead.
 */
class HomeFeedDeduper(
    private val blender: RecsBlender = RecsBlender(),
) {

    data class Deduped(val sections: List<ForYouSection>, val feed: HomeFeed)

    /**
     * @param feedFirst `true` when [feed] is already rendered, so it must come out untouched and
     *  [sections] absorb the pruning; `false` (default) for a single combined render, where the more
     *  personal "For you" rows win.
     */
    fun dedupe(
        feed: HomeFeed,
        sections: List<ForYouSection>,
        minRowItems: Int = MIN_ROW_ITEMS,
        feedFirst: Boolean = false,
    ): Deduped {
        val seen = Seen()
        return if (feedFirst) {
            val keptFeed = feed.prunedBy(seen)
            Deduped(sections = sections.prunedBy(seen, minRowItems), feed = keptFeed)
        } else {
            val keptSections = sections.prunedBy(seen, minRowItems)
            Deduped(sections = keptSections, feed = feed.prunedBy(seen))
        }
    }

    /** The identity keys already claimed, one set per shelf. */
    private class Seen {
        val tracks = mutableSetOf<String>()
        val artists = mutableSetOf<String>()
        val albums = mutableSetOf<String>()
        val playlists = mutableSetOf<String>()
    }

    private fun List<ForYouSection>.prunedBy(seen: Seen, minRowItems: Int): List<ForYouSection> =
        mapNotNull { section ->
            when (section) {
                is ForYouSection.Mix ->
                    section.items.filter { seen.tracks.add(blender.trackKey(it)) }
                        .takeIf { it.size >= minRowItems }?.let { section.copy(items = it) }

                is ForYouSection.BecauseYouLike ->
                    section.items.filter { seen.tracks.add(blender.trackKey(it)) }
                        .takeIf { it.size >= minRowItems }?.let { section.copy(items = it) }

                is ForYouSection.ArtistsForYou ->
                    section.items.filter { seen.artists.add(blender.artistKey(it)) }
                        .takeIf { it.size >= minRowItems }?.let { section.copy(items = it) }

                is ForYouSection.AlbumsForYou ->
                    section.items.filter { seen.albums.add(blender.albumKey(it)) }
                        .takeIf { it.size >= minRowItems }?.let { section.copy(items = it) }
            }
        }

    /** Albums and new releases share one set — they're the same shelf twice over when a release charts. */
    private fun HomeFeed.prunedBy(seen: Seen) = HomeFeed(
        topTracks = topTracks.filterItems { seen.tracks.add(blender.trackKey(it)) },
        topArtists = topArtists.filterItems { seen.artists.add(blender.artistKey(it)) },
        topAlbums = topAlbums.filterItems { seen.albums.add(blender.albumKey(it)) },
        editorialPlaylists = editorialPlaylists.filterItems { seen.playlists.add(blender.playlistKey(it)) },
        newReleases = newReleases.filterItems { seen.albums.add(blender.albumKey(it)) },
    )

    /** Keeps only the items passing [predicate]; a section left with nothing disappears. */
    private fun <T> List<AttributedResult<T>>.filterItems(
        predicate: (T) -> Boolean,
    ): List<AttributedResult<T>> = mapNotNull { result ->
        result.items.filter(predicate).takeIf { it.isNotEmpty() }?.let { result.copy(items = it) }
    }

    private companion object {
        /** Below this a personalized row reads as an accident, so it's dropped instead. */
        const val MIN_ROW_ITEMS = 3
    }
}
