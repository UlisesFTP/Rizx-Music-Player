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

    /**
     * One taste artist's neighborhood ("Similar to <anchor>"): artists like them and records by those
     * artists, drawn as one mixed row — the shape every streaming feed uses for this. One section per
     * anchor, so a listener with two strong artists gets two distinct rows instead of one merged pool
     * (which is what the old `ArtistsForYou`/`AlbumsForYou` pair collapsed into).
     */
    @Serializable
    @SerialName("similar-to")
    data class SimilarTo(
        val anchorName: String,
        val artists: List<ArtistRef> = emptyList(),
        val albums: List<AlbumRef> = emptyList(),
    ) : ForYouSection {
        override val size: Int get() = artists.size + albums.size
    }

    /**
     * Editorial playlists that feature one taste artist ("Playlists with <anchor>"), interleaved across
     * Deezer, Apple Music and YouTube Music so the row reads as one shelf, not three provider dumps.
     * Spotify cannot contribute here: finding playlists by artist needs a search it gates (ADR 0018).
     */
    @Serializable
    @SerialName("playlists-with")
    data class PlaylistsWith(val anchorName: String, val playlists: List<PlaylistRef> = emptyList()) : ForYouSection {
        override val size: Int get() = playlists.size
    }

    /**
     * Playlists for one of the listener's dominant genres ("Your genre: <genre>"), from all four
     * platforms. The genre travels as the enum so the UI localizes the label.
     */
    @Serializable
    @SerialName("genre-playlists")
    data class GenrePlaylists(val genre: SoundGenre, val playlists: List<PlaylistRef> = emptyList()) : ForYouSection {
        override val size: Int get() = playlists.size
    }

    /**
     * What the listener's own artists released recently, newest first — the "release radar" shelf. One
     * row for every anchor together, because a release radar split per artist is a discography.
     */
    @Serializable
    @SerialName("new-from-artists")
    data class NewFromArtists(val albums: List<AlbumRef> = emptyList()) : ForYouSection {
        override val size: Int get() = albums.size
    }
}
