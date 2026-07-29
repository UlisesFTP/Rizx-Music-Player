package fm.rizx.player.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One personalized Home row ("For you"). Sections carry data only — the UI supplies the localized
 * title — so the domain stays resource-free. Built from the user's own likes/recents; with no taste
 * data yet there are simply no sections (cold start: the Home lives on the charts).
 *
 * Serializable (sealed → polymorphic, with stable `@SerialName`s so renaming a class never orphans an
 * already-written cache) because the Home caches these rows to disk to render instantly on launch.
 */
@Serializable
sealed interface ForYouSection {

    /**
     * How many cards the row carries.
     *
     * **Zero marks a planned row**: its title is already known from local taste but its items are
     * still being fetched. The Home draws those as skeletons of the real row's height, so the
     * personalized half fills in place instead of appearing above whatever the user is reading.
     * Computed, never serialized — a cached row always has its items.
     */
    val size: Int

    /** YT Music's own autoplay recommendations seeded by one of the user's tracks ("Mix · <seed>"). */
    @Serializable
    @SerialName("mix")
    data class Mix(val seedTitle: String, val items: List<Track>) : ForYouSection {
        override val size: Int get() = items.size
    }

    /** The metadata provider's artist radio for a most-listened artist ("Because you like <artist>"). */
    @Serializable
    @SerialName("because-you-like")
    data class BecauseYouLike(val artistName: String, val items: List<Track>) : ForYouSection {
        override val size: Int get() = items.size
    }

    /** Artists similar to the user's favorites ("Artists for you"). */
    @Serializable
    @SerialName("artists-for-you")
    data class ArtistsForYou(val items: List<ArtistRef>) : ForYouSection {
        override val size: Int get() = items.size
    }

    /** Records by those similar artists — the album-shaped half of the same discovery ("Albums for you"). */
    @Serializable
    @SerialName("albums-for-you")
    data class AlbumsForYou(val items: List<AlbumRef>) : ForYouSection {
        override val size: Int get() = items.size
    }
}
