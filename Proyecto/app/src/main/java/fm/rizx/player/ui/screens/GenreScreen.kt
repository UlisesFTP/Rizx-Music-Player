package fm.rizx.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fm.rizx.player.R
import fm.rizx.player.core.formatDuration
import fm.rizx.player.domain.model.AlbumRef
import fm.rizx.player.domain.model.ArtistRef
import fm.rizx.player.domain.model.GenreFeed
import fm.rizx.player.domain.model.PlaylistRef
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.model.coverUrl
import fm.rizx.player.ui.components.CoverArt
import fm.rizx.player.ui.components.DotMatrixSpinner
import fm.rizx.player.ui.components.InkFrame
import fm.rizx.player.ui.components.RizxIconButton
import fm.rizx.player.ui.components.SectionHeader
import fm.rizx.player.ui.components.clickableScale
import fm.rizx.player.ui.components.tileUrl
import fm.rizx.player.ui.genre.GenreUiState
import fm.rizx.player.ui.genre.GenreViewModel
import fm.rizx.player.ui.icons.RizxIcons
import fm.rizx.player.ui.search.browseCategoryFor
import fm.rizx.player.ui.theme.LocalBottomInset
import fm.rizx.player.ui.theme.RizxTheme
import fm.rizx.player.ui.theme.code
import fm.rizx.player.ui.theme.mr
import fm.rizx.player.ui.theme.paperElevation
import fm.rizx.player.ui.theme.sg
import fm.rizx.player.ui.theme.staggeredReveal

/**
 * A genre hub: the songs, playlists, artists and albums that **belong to** a genre, as the catalogue
 * groups them — reached from Search's browse wall, which no longer runs a text search for the genre's
 * name.
 *
 * Playing a song makes the whole songs list the queue, so next/prev stay inside the genre.
 */
@Composable
fun GenreScreen(
    onBack: () -> Unit,
    onOpenAlbum: (ProviderRef) -> Unit,
    onOpenArtist: (ProviderRef) -> Unit,
    onOpenPlaylist: (PlaylistRef) -> Unit,
    genreId: String,
    vm: GenreViewModel = hiltViewModel(),
) {
    val c = RizxTheme.colors
    val state by vm.state.collectAsStateWithLifecycle()

    // The tile's artwork, looked up rather than carried through the route — the browse wall already
    // holds it, and a CDN URL in a nav argument is a URL that can go stale in the back stack.
    val artwork = browseCategoryFor(genreId)?.image

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RizxIconButton(RizxIcons.Back, stringResource(R.string.detail_back), onBack, background = c.elev, border = c.line, iconSize = 20.dp)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.genre_eyebrow), style = code(11, FontWeight.Bold), color = c.muted)
                Text(vm.genreName, style = sg(24, FontWeight.Bold, -0.02f), color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            CoverArt(
                tintFor(genreId), initial = null, Modifier.size(52.dp).paperElevation(),
                imageUrl = artwork, borderColor = c.hardLine, borderWidth = InkFrame,
            )
        }

        when (val s = state) {
            GenreUiState.Loading -> Centered { DotMatrixSpinner(color = c.accent, diameter = 34.dp) }
            GenreUiState.Offline -> Message(stringResource(R.string.detail_offline_message), vm::load)
            GenreUiState.Empty -> Message(stringResource(R.string.genre_empty), vm::load)
            is GenreUiState.Error -> Message(s.message, vm::load)
            is GenreUiState.Content -> GenreContent(s.feed, vm::play, onOpenAlbum, onOpenArtist, onOpenPlaylist)
        }
    }
}

@Composable
private fun GenreContent(
    feed: GenreFeed,
    onPlay: (Int) -> Unit,
    onOpenAlbum: (ProviderRef) -> Unit,
    onOpenArtist: (ProviderRef) -> Unit,
    onOpenPlaylist: (PlaylistRef) -> Unit,
) {
    // Resolved out here: the LazyColumn body is a LazyListScope lambda, not a composable one.
    val songsTitle = stringResource(R.string.search_tab_songs)
    val playlistsTitle = stringResource(R.string.search_tab_playlists)
    val artistsTitle = stringResource(R.string.search_tab_artists)
    val albumsTitle = stringResource(R.string.search_tab_albums)

    LazyColumn(Modifier.fillMaxSize()) {
        if (feed.tracks.isNotEmpty()) {
            item(key = "hdr-songs") { Heading(songsTitle) }
            itemsIndexed(feed.tracks, key = { i, t -> "t-${t.source.identityKey}-$i" }) { index, track ->
                Box(Modifier.padding(horizontal = 22.dp).staggeredReveal(index)) {
                    GenreTrackRow(index + 1, track) { onPlay(index) }
                }
            }
        }

        strip(playlistsTitle, feed.playlists.take(STRIP_ITEMS), { it.source.identityKey }) { playlist ->
            Cell(
                title = playlist.name,
                subtitle = playlist.trackCount?.let { stringResource(R.string.search_track_count, it) }
                    ?: stringResource(R.string.search_playlist_label),
                tintKey = playlist.source.id,
                imageUrl = playlist.artwork.tileUrl(),
                onClick = { onOpenPlaylist(playlist) },
            )
        }

        strip(artistsTitle, feed.artists.take(STRIP_ITEMS), { it.source.identityKey }) { artist ->
            Cell(
                title = artist.name,
                subtitle = null,
                tintKey = artist.source.id,
                imageUrl = artist.artwork.tileUrl(),
                circle = true,
                onClick = { onOpenArtist(artist.source) },
            )
        }

        strip(albumsTitle, feed.albums.take(STRIP_ITEMS), { it.source.identityKey }) { album ->
            Cell(
                title = album.title,
                subtitle = album.artists.joinToString { it.name }.ifEmpty { stringResource(R.string.unknown_artist) },
                tintKey = album.source.id,
                imageUrl = album.artwork.tileUrl(),
                onClick = { onOpenAlbum(album.source) },
            )
        }

        item { Spacer(Modifier.height(LocalBottomInset.current + 16.dp)) }
    }
}

/** A titled horizontal strip; an empty section draws nothing at all, header included. */
private fun <T> LazyListScope.strip(
    title: String,
    items: List<T>,
    key: (T) -> Any,
    cell: @Composable (T) -> Unit,
) {
    if (items.isEmpty()) return
    item(key = "hdr-$title") { Heading(title) }
    item(key = "strip-$title") {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(items, key = key) { item -> cell(item) }
        }
    }
}

@Composable
private fun Heading(title: String) = SectionHeader(
    title,
    Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 8.dp),
)

@Composable
private fun Cell(
    title: String,
    subtitle: String?,
    tintKey: String,
    imageUrl: String?,
    onClick: () -> Unit,
    circle: Boolean = false,
) {
    val c = RizxTheme.colors
    Column(
        Modifier.width(STRIP_ART).clickableScale(scale = 0.98f, onClick = onClick),
        horizontalAlignment = if (circle) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        CoverArt(
            tintFor(tintKey), initial = title.take(1),
            Modifier.size(STRIP_ART).paperElevation(if (circle) CircleShape else RectangleShape),
            initialSize = 40, circle = circle, imageUrl = imageUrl,
            borderColor = c.hardLine, borderWidth = InkFrame,
        )
        Text(
            title, style = mr(14, FontWeight.SemiBold), color = c.text, maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (circle) TextAlign.Center else null,
            modifier = Modifier.padding(top = 9.dp),
        )
        if (subtitle != null) {
            Text(subtitle, style = mr(12, FontWeight.Medium), color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun GenreTrackRow(position: Int, track: Track, onPlay: () -> Unit) {
    val c = RizxTheme.colors
    Row(
        Modifier.fillMaxWidth().clickableScale(scale = 0.99f, pressColor = c.rowHover, onClick = onPlay).padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
            Text("$position", style = mr(13, FontWeight.Medium), color = c.muted)
        }
        CoverArt(tintFor(track.source.id), initial = null, Modifier.size(46.dp), imageUrl = track.artwork.coverUrl())
        Column(Modifier.weight(1f)) {
            Text(track.title, style = mr(14, FontWeight.SemiBold), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                track.artists.joinToString { it.name }.ifEmpty { stringResource(R.string.unknown_artist) },
                style = mr(12, FontWeight.Medium), color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Text(formatDuration(track.durationMs), style = mr(12, FontWeight.Medium), color = c.muted)
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun Message(text: String, onRetry: () -> Unit) {
    val c = RizxTheme.colors
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, style = mr(14, FontWeight.Medium), color = c.muted, textAlign = TextAlign.Center)
            Text(
                stringResource(R.string.action_retry), style = sg(14, FontWeight.Bold), color = c.onFill,
                modifier = Modifier.padding(top = 16.dp).background(c.fill).clickableScale(scale = 0.94f, onClick = onRetry).padding(horizontal = 22.dp, vertical = 10.dp),
            )
        }
    }
}

private fun tintFor(key: String): Int = (key.hashCode() and Int.MAX_VALUE) % 7

/** How many cells a strip carries — the songs list is the screen, the strips are a way out of it. */
private const val STRIP_ITEMS = 20
private val STRIP_ART = 152.dp
