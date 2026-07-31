package fm.rizx.player.data.remote.wikipedia

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `action=query&list=search` — ranked page titles. */
@Serializable
data class WikipediaSearchDto(val query: WikipediaQueryDto = WikipediaQueryDto())

@Serializable
data class WikipediaQueryDto(val search: List<WikipediaSearchRowDto> = emptyList())

@Serializable
data class WikipediaSearchRowDto(val title: String? = null)

/**
 * `page/summary/<title>`.
 *
 * [type] is what marks a disambiguation page ("disambiguation"), and [description] is the one-line
 * "American rapper" / "banda de música" that says whether this page is about a musician at all — the
 * two fields that keep the wrong Ghost off the artist page.
 */
@Serializable
data class WikipediaSummaryDto(
    val type: String? = null,
    val title: String? = null,
    val description: String? = null,
    val extract: String? = null,
    @SerialName("content_urls") val contentUrls: WikipediaUrlsDto? = null,
)

@Serializable
data class WikipediaUrlsDto(val desktop: WikipediaUrlDto? = null)

@Serializable
data class WikipediaUrlDto(val page: String? = null)
