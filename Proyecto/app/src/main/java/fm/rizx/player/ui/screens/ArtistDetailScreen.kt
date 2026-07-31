package fm.rizx.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import fm.rizx.player.domain.model.Artist
import fm.rizx.player.domain.model.ArtistBio
import fm.rizx.player.domain.model.ArtistRef
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.model.albumsOnly
import fm.rizx.player.domain.model.coverUrl
import fm.rizx.player.domain.model.singlesAndEps
import fm.rizx.player.ui.components.CoverArt
import fm.rizx.player.ui.components.DotMatrixSpinner
import fm.rizx.player.ui.components.FilterEmpty
import fm.rizx.player.ui.components.RizxActionButton
import fm.rizx.player.ui.components.RizxFilterField
import fm.rizx.player.ui.components.RizxIconButton
import fm.rizx.player.ui.components.SectionHeader
import fm.rizx.player.ui.components.clickableScale
import fm.rizx.player.ui.components.tintFor
import fm.rizx.player.ui.detail.ArtistDetailViewModel
import fm.rizx.player.ui.detail.ArtistUiState
import fm.rizx.player.ui.icons.RizxIcons
import fm.rizx.player.ui.theme.LocalBottomInset
import fm.rizx.player.ui.theme.RizxTheme
import fm.rizx.player.ui.theme.code
import fm.rizx.player.ui.theme.cornerBrackets
import fm.rizx.player.ui.theme.hatch
import fm.rizx.player.ui.theme.heroBrush
import fm.rizx.player.ui.theme.mr
import fm.rizx.player.ui.theme.sg
import fm.rizx.player.ui.theme.staggeredReveal
import fm.rizx.player.ui.util.ListFilter

/**
 * The artist page: who they are, everything they released, and who to listen to next.
 *
 * It is a `LazyColumn` rather than a scrolling `Column` because it now carries fifty songs and a whole
 * discography — composing all of that up front to show the first screenful is the kind of cost that
 * only shows up on the artists people actually care about.
 *
 * Every long section is **previewed and expandable in place**: five songs, six records, and a "see all"
 * that opens that section where it stands. Nothing here navigates away to find more of the same thing.
 */
@Composable
fun ArtistDetailScreen(
    onBack: () -> Unit,
    onOpenAlbum: (ProviderRef) -> Unit,
    onOpenArtist: (ProviderRef) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onAddNext: (Track) -> Unit,
    vm: ArtistDetailViewModel = hiltViewModel(),
) {
    val c = RizxTheme.colors
    val state by vm.state.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
        when (val s = state) {
            ArtistUiState.Loading -> ArtistSpinner()
            ArtistUiState.Offline -> ArtistMessage(stringResource(R.string.detail_offline_message), vm::load)
            is ArtistUiState.Error -> ArtistMessage(s.message, vm::load)
            is ArtistUiState.Content -> ArtistContent(s, vm, onOpenAlbum, onOpenArtist, onAddToQueue, onAddNext)
        }
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            RizxIconButton(
                RizxIcons.Back, stringResource(R.string.detail_back), onBack,
                background = if (c.isDark) Color(0xFF0A0A0B).copy(alpha = 0.5f) else Color(0xFFF3F0E9).copy(alpha = 0.58f),
                border = if (c.isDark) Color.White.copy(alpha = 0.14f) else Color(0xFF221F1A).copy(alpha = 0.18f),
                tint = c.text,
            )
        }
    }
}

@Composable
private fun ArtistContent(
    content: ArtistUiState.Content,
    vm: ArtistDetailViewModel,
    onOpenAlbum: (ProviderRef) -> Unit,
    onOpenArtist: (ProviderRef) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onAddNext: (Track) -> Unit,
) {
    val artist = content.artist
    val albums = remember(artist) { artist.albumsOnly }
    val singles = remember(artist) { artist.singlesAndEps }

    var query by rememberSaveable(artist.source.identityKey) { mutableStateOf("") }
    var songsOpen by rememberSaveable(artist.source.identityKey) { mutableStateOf(false) }
    var albumsOpen by rememberSaveable(artist.source.identityKey) { mutableStateOf(false) }
    var singlesOpen by rememberSaveable(artist.source.identityKey) { mutableStateOf(false) }

    // The queue a tapped song starts: everything matching the filter, not just the rows on screen —
    // a preview is a shortened view of one list, and playing from it should carry on down that list.
    val matching = remember(artist.topTracks, query) {
        artist.topTracks.filter { ListFilter.matchesTrack(query, it) }
    }
    val filtering = query.isNotBlank()
    val visibleSongs = if (songsOpen || filtering) matching else matching.take(SONG_PREVIEW)

    // Hoisted: the shelves are plain LazyListScope builders, and stringResource needs composable scope.
    val seeAll = stringResource(R.string.detail_see_all)
    val seeLess = stringResource(R.string.detail_see_less)
    val albumsTitle = stringResource(R.string.detail_albums_heading)
    val singlesTitle = stringResource(R.string.detail_singles_heading)

    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "hero") { ArtistHero(artist, albums.size, singles.size) }

        item(key = "actions") {
            Row(
                Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                RizxActionButton(
                    RizxIcons.Play, stringResource(R.string.detail_play),
                    onClick = { vm.play(0, artist.topTracks) },
                    prominent = true,
                )
                RizxActionButton(
                    RizxIcons.Shuffle, stringResource(R.string.detail_shuffle),
                    onClick = { vm.shuffle(artist.topTracks) },
                )
                RizxActionButton(
                    RizxIcons.Radio, stringResource(R.string.detail_radio),
                    onClick = { vm.radio(artist.topTracks) },
                )
            }
        }

        content.bio?.let { bio -> item(key = "about") { AboutBlock(bio) } }

        if (artist.topTracks.isNotEmpty()) {
            item(key = "songs-header") {
                Column(Modifier.padding(start = 22.dp, end = 22.dp, top = 26.dp)) {
                    SectionHeader(
                        stringResource(R.string.detail_top_tracks_heading),
                        Modifier.fillMaxWidth(),
                        // Only offered when there is more to see; with a filter typed, everything
                        // matching is already on screen.
                        action = if (filtering || matching.size <= SONG_PREVIEW) {
                            null
                        } else if (songsOpen) {
                            seeLess
                        } else {
                            seeAll
                        },
                        onAction = { songsOpen = !songsOpen },
                    )
                    RizxFilterField(query, { query = it }, Modifier.padding(top = 12.dp))
                }
            }
            itemsIndexed(visibleSongs, key = { _, t -> "song-${t.source.identityKey}" }) { index, track ->
                ArtistTrackRow(
                    position = index + 1,
                    track = track,
                    onPlay = { vm.play(index, matching) },
                    onAddToQueue = { onAddToQueue(track) },
                    onAddNext = { onAddNext(track) },
                    onOpenAlbum = { track.album?.source?.let(onOpenAlbum) },
                )
            }
            if (visibleSongs.isEmpty()) item(key = "songs-empty") { FilterEmpty(query) }
        }

        recordShelf(
            key = "albums",
            title = albumsTitle,
            records = albums,
            expanded = albumsOpen,
            seeAll = seeAll,
            seeLess = seeLess,
            onToggle = { albumsOpen = !albumsOpen },
            onOpen = onOpenAlbum,
        )
        recordShelf(
            key = "singles",
            title = singlesTitle,
            records = singles,
            expanded = singlesOpen,
            seeAll = seeAll,
            seeLess = seeLess,
            onToggle = { singlesOpen = !singlesOpen },
            onOpen = onOpenAlbum,
        )

        if (content.similar.isNotEmpty()) {
            item(key = "similar-header") {
                SectionHeader(
                    stringResource(R.string.detail_similar_heading),
                    Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 26.dp, bottom = 12.dp),
                )
            }
            item(key = "similar") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 22.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    itemsIndexed(content.similar, key = { _, a -> a.source.identityKey }) { index, similar ->
                        Box(Modifier.staggeredReveal(index)) { SimilarArtistCard(similar, onOpenArtist) }
                    }
                }
            }
        }

        item(key = "bottom") { Spacer(Modifier.height(LocalBottomInset.current + 16.dp)) }
    }
}

// ---- Hero + about ------------------------------------------------------------------------------

@Composable
private fun ArtistHero(artist: Artist, albums: Int, singles: Int) {
    val c = RizxTheme.colors
    Box(Modifier.fillMaxWidth().height(300.dp).clipToBounds().background(heroBrush(c)).hatch(c.hatch)) {
        val cover = artist.artwork.coverUrl()
        if (cover != null) {
            coil.compose.AsyncImage(
                model = cover, contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            Text(
                artist.name.take(1).uppercase(), style = sg(150, FontWeight.Bold),
                color = c.heroLetter, modifier = Modifier.align(Alignment.Center),
            )
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.0f to Color.Transparent, 0.6f to c.bg.copy(alpha = 0.55f), 1.0f to c.bg)))
        // HUD corner-bracket frame over the hero (industrial spec-sheet chrome).
        Box(Modifier.matchParentSize().cornerBrackets(c.hardLine, len = 12.dp))
        Column(Modifier.align(Alignment.BottomStart).padding(start = 22.dp, end = 22.dp, bottom = 16.dp)) {
            Text(stringResource(R.string.detail_artist_eyebrow), style = mr(11, FontWeight.Bold, 0.2f), color = c.accent)
            Text(
                artist.name, style = sg(32, FontWeight.Bold, -0.02f), color = c.heroText,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp),
            )
            artist.followers?.let {
                Text(
                    stringResource(R.string.detail_followers_count, formatFollowers(it)),
                    style = mr(13, FontWeight.Medium), color = c.heroSub, modifier = Modifier.padding(top = 6.dp),
                )
            }
            // The size of the discography, stated: it is what makes "see all" worth pressing.
            if (albums + singles > 0) {
                Text(
                    stringResource(R.string.detail_artist_stats, albums, singles),
                    style = mr(12, FontWeight.Medium), color = c.heroSub, modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/**
 * The biography, collapsed to a few lines until asked.
 *
 * The "Wikipedia" line is not a credit we chose to give: the text is CC BY-SA, and attribution is the
 * condition it comes with.
 */
@Composable
private fun AboutBlock(bio: ArtistBio) {
    val c = RizxTheme.colors
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 22.dp)) {
        Text(stringResource(R.string.detail_about).uppercase(), style = code(11, FontWeight.Bold), color = c.muted)
        Text(
            bio.text,
            style = mr(13, FontWeight.Medium),
            color = c.text2,
            maxLines = if (expanded) Int.MAX_VALUE else ABOUT_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                stringResource(if (expanded) R.string.detail_show_less else R.string.detail_show_more).uppercase(),
                style = code(11, FontWeight.Bold),
                color = c.text2,
                modifier = Modifier.clickableScale(scale = 0.94f) { expanded = !expanded },
            )
            Text(
                stringResource(R.string.detail_wikipedia),
                style = code(11, FontWeight.Medium),
                color = c.muted,
            )
        }
    }
}

// ---- Songs -------------------------------------------------------------------------------------

@Composable
private fun ArtistTrackRow(
    position: Int,
    track: Track,
    onPlay: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddNext: () -> Unit,
    onOpenAlbum: () -> Unit,
) {
    val c = RizxTheme.colors
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickableScale(scale = 0.99f, pressColor = c.rowHover, onClick = onPlay)
            .padding(start = 18.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
            Text("$position", style = mr(13, FontWeight.Medium), color = c.muted)
        }
        CoverArt(tintFor(track.source.id), initial = null, Modifier.size(46.dp), imageUrl = track.artwork.coverUrl())
        Column(Modifier.weight(1f)) {
            Text(track.title, style = mr(14, FontWeight.SemiBold), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            track.album?.title?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = mr(11, FontWeight.Medium), color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(formatDuration(track.durationMs), style = mr(12, FontWeight.Medium), color = c.muted)
        Box {
            RizxIconButton(
                RizxIcons.MoreVert, stringResource(R.string.detail_more_options), { menuOpen = true },
                background = Color.Transparent, border = Color.Transparent, tint = c.muted, iconSize = 18.dp,
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.search_play_next), style = mr(13, FontWeight.Medium), color = c.text) },
                    onClick = { menuOpen = false; onAddNext() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.search_add_to_queue), style = mr(13, FontWeight.Medium), color = c.text) },
                    onClick = { menuOpen = false; onAddToQueue() },
                )
                if (track.album != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.detail_open_album), style = mr(13, FontWeight.Medium), color = c.text) },
                        onClick = { menuOpen = false; onOpenAlbum() },
                    )
                }
            }
        }
    }
}

// ---- Records -----------------------------------------------------------------------------------

/**
 * One shelf of releases: a carousel while it is a preview, a two-column grid once opened.
 *
 * The grid is emitted as rows of two rather than a nested grid — a `LazyVerticalGrid` inside a
 * `LazyColumn` has no bounded height and throws.
 */
private fun LazyListScope.recordShelf(
    key: String,
    title: String,
    records: List<AlbumRef>,
    expanded: Boolean,
    seeAll: String,
    seeLess: String,
    onToggle: () -> Unit,
    onOpen: (ProviderRef) -> Unit,
) {
    if (records.isEmpty()) return
    item(key = "$key-header") {
        SectionHeader(
            title,
            Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 26.dp, bottom = 12.dp),
            action = if (records.size <= RECORD_PREVIEW) null else if (expanded) seeLess else seeAll,
            onAction = onToggle,
        )
    }
    if (expanded) {
        val rows = records.chunked(2)
        itemsIndexed(rows, key = { _, row -> "$key-row-${row.first().source.identityKey}" }) { index, row ->
            Row(
                Modifier.fillMaxWidth().staggeredReveal(index).padding(horizontal = 22.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                row.forEach { album -> Box(Modifier.weight(1f)) { AlbumCard(album, onOpen, fill = true) } }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    } else {
        item(key = "$key-row") {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 22.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                itemsIndexed(records.take(RECORD_PREVIEW), key = { _, a -> a.source.identityKey }) { index, album ->
                    Box(Modifier.staggeredReveal(index)) { AlbumCard(album, onOpen, fill = false) }
                }
            }
        }
    }
}

@Composable
private fun AlbumCard(album: AlbumRef, onOpenAlbum: (ProviderRef) -> Unit, fill: Boolean) {
    val c = RizxTheme.colors
    Column(
        (if (fill) Modifier.fillMaxWidth() else Modifier.width(140.dp))
            .clickableScale(scale = 0.97f) { onOpenAlbum(album.source) },
    ) {
        CoverArt(
            tintFor(album.source.id),
            initial = album.title.take(1),
            if (fill) Modifier.fillMaxWidth().aspectRatio(1f) else Modifier.size(140.dp),
            initialSize = 34,
            imageUrl = album.artwork.coverUrl(),
        )
        Text(
            album.title, style = mr(13, FontWeight.SemiBold), color = c.text,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
        )
        album.year?.let {
            Text("$it", style = mr(11, FontWeight.Medium), color = c.muted, maxLines = 1)
        }
    }
}

// ---- Similar artists ---------------------------------------------------------------------------

@Composable
private fun SimilarArtistCard(artist: ArtistRef, onOpenArtist: (ProviderRef) -> Unit) {
    val c = RizxTheme.colors
    Column(
        Modifier.width(110.dp).clickableScale(scale = 0.97f) { onOpenArtist(artist.source) },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CoverArt(
            tintFor(artist.source.id), initial = artist.name.take(1), Modifier.size(110.dp),
            initialSize = 34, circle = true, imageUrl = artist.artwork.coverUrl(),
        )
        Text(
            artist.name, style = mr(12, FontWeight.SemiBold), color = c.text, maxLines = 2,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

// ---- Bits --------------------------------------------------------------------------------------

private const val SONG_PREVIEW = 5
private const val RECORD_PREVIEW = 6
private const val ABOUT_LINES = 3

private fun formatFollowers(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fK".format(n / 1_000.0)
    else -> n.toString()
}

@Composable
private fun ArtistSpinner() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        DotMatrixSpinner(color = RizxTheme.colors.accent, diameter = 34.dp)
    }
}

@Composable
private fun ArtistMessage(text: String, onRetry: () -> Unit) {
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
