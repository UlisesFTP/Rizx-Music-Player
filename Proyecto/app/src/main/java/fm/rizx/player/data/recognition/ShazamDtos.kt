package fm.rizx.player.data.recognition

import fm.rizx.player.domain.recognition.RecognitionMatch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire types for the recognition service, and the one place they are allowed to exist. Nothing below
 * leaves this file except a [RecognitionMatch].
 *
 * Written against a real response rather than a specification, because there isn't one. Two habits
 * follow from that: **every field is optional**, and **every list element is nullable** — sections
 * appear and disappear per track (the video section is often simply absent), their order is not
 * stable, and a null inside an array would otherwise abort the whole parse over a field nobody reads.
 */

// -- request ------------------------------------------------------------------------------------

@Serializable
internal data class ShazamTagRequest(
    val geolocation: ShazamGeolocation,
    val signature: ShazamSignature,
    val timestamp: Long,
    val timezone: String,
)

/**
 * Deliberately fixed at the origin. The service accepts it — verified against the live endpoint —
 * and recognition has no business knowing where its user is standing, so this app neither asks for a
 * location permission nor invents a plausible-looking coordinate to send instead.
 */
@Serializable
internal data class ShazamGeolocation(
    val altitude: Double,
    val latitude: Double,
    val longitude: Double,
) {
    internal companion object {
        /**
         * Written out rather than defaulted, because the encoder omits default values: as defaults
         * these three would leave as `"geolocation":{}` and the neutrality would be an accident of
         * whatever the service assumes for a missing object.
         */
        val NEUTRAL = ShazamGeolocation(altitude = 0.0, latitude = 0.0, longitude = 0.0)
    }
}

@Serializable
internal data class ShazamSignature(
    val samplems: Long,
    val timestamp: Long,
    val uri: String,
)

// -- response -----------------------------------------------------------------------------------

@Serializable
internal data class ShazamTagResponse(
    val matches: List<ShazamMatchDto?> = emptyList(),
    val track: ShazamTrackDto? = null,
    val tagid: String? = null,
)

@Serializable
internal data class ShazamMatchDto(val id: String? = null, val offset: Double? = null)

@Serializable
internal data class ShazamTrackDto(
    val key: String? = null,
    val title: String? = null,
    /** The billing line — "Daft Punk, Pharrell Williams & Nile Rodgers" — not a single artist. */
    val subtitle: String? = null,
    val url: String? = null,
    val isrc: String? = null,
    val images: ShazamImagesDto? = null,
    val genres: ShazamGenresDto? = null,
    val hub: ShazamHubDto? = null,
    val sections: List<ShazamSectionDto?> = emptyList(),
)

@Serializable
internal data class ShazamImagesDto(
    val coverart: String? = null,
    val coverarthq: String? = null,
    val background: String? = null,
)

@Serializable
internal data class ShazamGenresDto(val primary: String? = null)

@Serializable
internal data class ShazamHubDto(val actions: List<ShazamActionDto?> = emptyList())

@Serializable
internal data class ShazamActionDto(
    val name: String? = null,
    val type: String? = null,
    /** Present on the `applemusicplay` action: Apple's `adamid` for the recording. */
    val id: String? = null,
    val uri: String? = null,
)

@Serializable
internal data class ShazamSectionDto(
    val type: String? = null,
    val metadata: List<ShazamMetadataDto?> = emptyList(),
)

@Serializable
internal data class ShazamMetadataDto(
    @SerialName("title") val label: String? = null,
    val text: String? = null,
)

// -- mapping ------------------------------------------------------------------------------------

/**
 * Narrows a response to a [RecognitionMatch], or `null` when there is nothing usable in it.
 *
 * A result without a title or an artist is refused: it cannot be shown, searched for, or resolved, so
 * carrying it forward would only mean an empty card and a "play" button that does nothing.
 */
internal fun ShazamTagResponse.toMatch(providerId: String): RecognitionMatch? {
    val track = track ?: return null
    val title = track.title.clean() ?: return null
    val artist = track.subtitle.clean() ?: return null

    val songSection = track.sections.filterNotNull().firstOrNull { it.type == "SONG" }
    fun metadata(label: String): String? = songSection?.metadata?.filterNotNull()
        ?.firstOrNull { it.label.equals(label, ignoreCase = true) }?.text.clean()

    val appleTrackId = track.hub?.actions?.filterNotNull()
        ?.firstOrNull { it.type == "applemusicplay" }?.id.clean()

    return RecognitionMatch(
        provider = providerId,
        providerTrackId = track.key.clean() ?: title,
        title = title,
        artist = artist,
        album = metadata("Album"),
        isrc = track.isrc.clean()?.uppercase(),
        artworkUrl = track.images?.coverart.clean(),
        artworkHqUrl = track.images?.coverarthq.clean() ?: track.images?.coverart.clean(),
        genre = track.genres?.primary.clean(),
        releaseDate = metadata("Released"),
        label = metadata("Label"),
        externalUrl = track.url.clean(),
        appleTrackId = appleTrackId,
    )
}

/** Trimmed, inner runs of whitespace collapsed, and blank treated as absent. */
private fun String?.clean(): String? =
    this?.trim()?.replace(WHITESPACE, " ")?.takeIf { it.isNotEmpty() }

private val WHITESPACE = Regex("\\s+")
