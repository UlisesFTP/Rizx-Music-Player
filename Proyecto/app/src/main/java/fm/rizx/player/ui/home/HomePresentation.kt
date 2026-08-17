package fm.rizx.player.ui.home

import fm.rizx.player.domain.model.AppMix
import fm.rizx.player.domain.model.FeaturedPlaylist
import fm.rizx.player.domain.model.ForYouSection
import fm.rizx.player.domain.model.HomeFeed
import fm.rizx.player.domain.model.MixKind
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track

/** The time-sensitive copy in Home. Content order stays daily-stable; only the greeting changes. */
enum class HomeMoment { MORNING, AFTERNOON, EVENING }

fun homeMoment(hour: Int): HomeMoment = when (hour.coerceIn(0, 23)) {
    in 5..11 -> HomeMoment.MORNING
    in 12..18 -> HomeMoment.AFTERNOON
    else -> HomeMoment.EVENING
}

data class HomePlaybackIndicator(
    val source: ProviderRef? = null,
    val isPlaying: Boolean = false,
)

/**
 * A hero is typed instead of carrying callbacks so selection remains pure and the screen only translates
 * a selected item into the ViewModel action it already owns.
 */
sealed interface HomeHeroItem {
    val key: String
    val anchor: Track
    val tracks: List<Track>

    data class Mix(val mix: AppMix) : HomeHeroItem {
        override val key: String = "mix|${mix.id}"
        override val anchor: Track = mix.tracks.first()
        override val tracks: List<Track> = mix.tracks
    }

    data class Featured(
        val providerId: String,
        val providerName: String,
        val featured: FeaturedPlaylist,
    ) : HomeHeroItem {
        override val key: String = "featured|${featured.playlist.source.identityKey}"
        override val anchor: Track = featured.preview.first()
        override val tracks: List<Track> = featured.preview
    }

    data class Recommendation(val section: ForYouSection, override val tracks: List<Track>) : HomeHeroItem {
        override val key: String = "recommendation|${section.heroSubject()}|${tracks.first().source.identityKey}"
        override val anchor: Track = tracks.first()
    }
}

/**
 * One mix, one editorial and one personalised row: variety by kind, never three versions of one song.
 * The source lists are already daily-stable, so choosing the first eligible item keeps the pager stable.
 */
fun homeHeroItems(
    mixes: List<AppMix>,
    feed: HomeFeed,
    sections: List<ForYouSection>,
    limit: Int = 3,
): List<HomeHeroItem> {
    if (limit <= 0) return emptyList()
    val result = mutableListOf<HomeHeroItem>()
    val anchors = mutableSetOf<String>()

    fun add(item: HomeHeroItem?) {
        if (item == null || result.size >= limit) return
        if (anchors.add(item.anchor.source.identityKey)) result += item
    }

    val firstMix = mixes.firstOrNull { it.kind == MixKind.DAILY && it.tracks.isNotEmpty() }
        ?: mixes.firstOrNull { it.tracks.isNotEmpty() }
    add(firstMix?.let { HomeHeroItem.Mix(it) })

    val featured = feed.featured.asSequence()
        .flatMap { source -> source.items.asSequence().map { Triple(source.providerId, source.providerName, it) } }
        .firstOrNull { (_, _, item) ->
            item.preview.firstOrNull()?.source?.identityKey?.let { it !in anchors } == true
        }
    add(featured?.let { (providerId, providerName, item) ->
        HomeHeroItem.Featured(providerId, providerName, item)
    })

    val recommendation = sections.asSequence()
        .mapNotNull { section -> section.heroTracks().takeIf { it.isNotEmpty() }?.let { section to it } }
        .firstOrNull { (_, tracks) -> tracks.first().source.identityKey !in anchors }
    add(recommendation?.let { (section, tracks) -> HomeHeroItem.Recommendation(section, tracks) })

    return result.take(limit)
}

fun ForYouSection.heroTracks(): List<Track> = when (this) {
    is ForYouSection.Mix -> items
    is ForYouSection.BecauseYouLike -> items
    is ForYouSection.SimilarTo -> emptyList()
}

private fun ForYouSection.heroSubject(): String = when (this) {
    is ForYouSection.Mix -> "mix|$seedTitle"
    is ForYouSection.BecauseYouLike -> "artist|$artistName"
    is ForYouSection.SimilarTo -> "similar|$anchorName"
}
