package fm.rizx.player.data.remote.itunes

import kotlinx.serialization.Serializable

/**
 * Wire DTOs for the iTunes Search API (https://itunes.apple.com). These are the raw provider payload
 * and **must not leak into the domain** — they are mapped explicitly in [ItunesMappers] (ADR 0006).
 * Only the fields the app uses are declared; the shared [kotlinx.serialization.json.Json] is configured
 * with `ignoreUnknownKeys = true`, so the many extra iTunes fields are dropped safely.
 */
@Serializable
data class ItunesSearchResponse(
    val resultCount: Int = 0,
    val results: List<ItunesResultDto> = emptyList(),
)

/** One `song` result (from `/search` or `/lookup`). Every field is nullable — iTunes omits freely. */
@Serializable
data class ItunesResultDto(
    val wrapperType: String? = null,
    val kind: String? = null,
    val trackId: Long? = null,
    val artistId: Long? = null,
    val collectionId: Long? = null,
    val trackName: String? = null,
    val artistName: String? = null,
    val collectionName: String? = null,
    val previewUrl: String? = null,
    val artworkUrl60: String? = null,
    val artworkUrl100: String? = null,
    val trackTimeMillis: Long? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val trackViewUrl: String? = null,
    val collectionViewUrl: String? = null,
    val artistViewUrl: String? = null,
    val primaryGenreName: String? = null,
    /** Present on `album` rows (`wrapperType = "collection"`). */
    val trackCount: Int? = null,
    val releaseDate: String? = null,
    val collectionArtistName: String? = null,
)
