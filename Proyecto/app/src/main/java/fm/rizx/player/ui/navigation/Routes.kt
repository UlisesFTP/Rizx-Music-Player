package fm.rizx.player.ui.navigation

import android.net.Uri
import fm.rizx.player.domain.model.PlaylistRef
import fm.rizx.player.domain.model.ProviderRef

/** All navigation destinations in the Rizx shell. */
object Routes {
    const val HOME = "home"

    /**
     * Search, optionally arriving with a query already typed (`?q=…`) — same shape as [LIBRARY].
     * The bare `search` keeps working, so the bottom nav is unaffected.
     */
    const val SEARCH = "search"
    const val SEARCH_ROUTE = "search?q={q}"
    const val SEARCH_QUERY_ARG = "q"
    fun search(query: String): String = "$SEARCH?$SEARCH_QUERY_ARG=${Uri.encode(query)}"

    /**
     * Library, optionally deep-linked to one of its tabs (`?tab=Liked`). The tab is a query arg so the
     * bottom nav can keep navigating to the bare `library` and land on the default tab.
     */
    const val LIBRARY = "library"
    const val LIBRARY_ROUTE = "library?tab={tab}"
    const val LIBRARY_TAB_ARG = "tab"
    fun library(tab: String? = null): String = if (tab == null) LIBRARY else "$LIBRARY?$LIBRARY_TAB_ARG=$tab"

    /** Album/artist detail carry a `ProviderRef` (provider + id) as URL-encoded path args (Phase 18). */
    const val ALBUM_DETAIL = "album_detail"
    const val ALBUM_DETAIL_ROUTE = "album_detail/{provider}/{id}"
    fun albumDetail(ref: ProviderRef): String = "album_detail/${Uri.encode(ref.provider)}/${Uri.encode(ref.id)}"
    const val ARTIST_DETAIL = "artist_detail"
    const val ARTIST_DETAIL_ROUTE = "artist_detail/{provider}/{id}"
    fun artistDetail(ref: ProviderRef): String = "artist_detail/${Uri.encode(ref.provider)}/${Uri.encode(ref.id)}"

    /** Editorial (pre-made, remote) playlist detail — carries the `ProviderRef` + name so next/prev can
     *  traverse it. Name is a query arg so it round-trips without needing a second fetch. */
    const val EDITORIAL_PLAYLIST = "editorial_playlist"
    const val EDITORIAL_PLAYLIST_ROUTE = "editorial_playlist/{provider}/{id}?name={name}"
    fun editorialPlaylist(ref: PlaylistRef): String =
        "editorial_playlist/${Uri.encode(ref.source.provider)}/${Uri.encode(ref.source.id)}?name=${Uri.encode(ref.name)}"

    /** Identify what is playing in the room. Reached from the Search header; keeps its own history. */
    const val RECOGNITION = "recognition"

    const val NOW_PLAYING = "now_playing"
    const val QUEUE = "queue"
    const val SOURCES = "sources"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
    const val LICENSES = "licenses"
    const val EQUALIZER = "equalizer"
    const val LYRICS = "lyrics"

    /** Playlist detail takes an id argument: `playlist_detail/{playlistId}`. */
    const val PLAYLIST_DETAIL = "playlist_detail"
    fun playlistDetail(id: String): String = "$PLAYLIST_DETAIL/$id"

    /** The on-device music library (MediaStore scan) and its album/artist detail (raw MediaStore ids). */
    const val LOCAL = "local"
    const val LOCAL_ALBUM_ROUTE = "local_album/{albumId}"
    fun localAlbum(albumId: String): String = "local_album/${Uri.encode(albumId)}"
    const val LOCAL_ARTIST_ROUTE = "local_artist/{artistId}"
    fun localArtist(artistId: String): String = "local_artist/${Uri.encode(artistId)}"

    /** Screens that show the bottom navigation bar. NavHost reports the route *pattern*, hence the `_ROUTE`s. */
    val withBottomNav = setOf(HOME, SEARCH_ROUTE, LIBRARY_ROUTE, SOURCES, SETTINGS)

    /** Maps a route to its highlighted bottom-nav tab. */
    fun activeTab(route: String?): String = when (route) {
        LIBRARY_ROUTE -> LIBRARY
        SOURCES, SETTINGS -> SETTINGS
        SEARCH_ROUTE -> SEARCH
        else -> HOME
    }
}
