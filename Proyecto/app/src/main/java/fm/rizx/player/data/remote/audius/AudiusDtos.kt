package fm.rizx.player.data.remote.audius

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire DTOs for the public, keyless Audius API (discovery nodes behind `https://api.audius.co`). Audius
 * serves **full-length** tracks. DTOs stay in this layer (ADR 0006); the shared lenient `Json` drops the
 * many fields we don't use.
 */

/** Host-discovery response: `GET https://api.audius.co` → `{ "data": ["https://…", …] }`. */
@Serializable
data class AudiusHostsResponse(
    val data: List<String> = emptyList(),
)

/** Track list response (`/v1/tracks/search`, `/v1/tracks/trending`). */
@Serializable
data class AudiusTracksResponse(
    val data: List<AudiusTrackDto> = emptyList(),
)

@Serializable
data class AudiusTrackDto(
    val id: String? = null,
    val title: String? = null,
    val user: AudiusUserDto? = null,
    /** Track length in **seconds** (Audius convention). */
    val duration: Int? = null,
    val artwork: AudiusArtworkDto? = null,
    val genre: String? = null,
    @SerialName("is_streamable") val isStreamable: Boolean? = null,
    @SerialName("permalink") val permalink: String? = null,
)

@Serializable
data class AudiusUserDto(
    val name: String? = null,
    val handle: String? = null,
)

/** Artwork variants; JSON keys are literal pixel sizes, remapped via [SerialName]. */
@Serializable
data class AudiusArtworkDto(
    @SerialName("150x150") val small: String? = null,
    @SerialName("480x480") val medium: String? = null,
    @SerialName("1000x1000") val large: String? = null,
)
