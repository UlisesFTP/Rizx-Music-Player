package fm.rizx.player.ui

import fm.rizx.player.ui.player.shareTrack
import fm.rizx.player.ui.player.openAudioOutputSwitcher
import fm.rizx.player.ui.player.NowPlayingMenu
import fm.rizx.player.ui.player.CanvasViewModel
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.model.coverUrl
import fm.rizx.player.ui.components.MiniPlayer
import fm.rizx.player.ui.components.LocalLosslessCodecs
import fm.rizx.player.ui.components.LocalThriftyArtwork
import fm.rizx.player.ui.components.RizxBottomNav
import fm.rizx.player.ui.components.SaveDownloadsToPhoneDialog
import fm.rizx.player.ui.library.AddToPlaylistDialog
import fm.rizx.player.ui.library.LibraryViewModel
import fm.rizx.player.ui.navigation.Routes
import fm.rizx.player.ui.player.PlaybackViewModel
import fm.rizx.player.ui.player.PlayerViewModel
import fm.rizx.player.ui.queue.QueueViewModel
import fm.rizx.player.ui.util.rememberSaveToPhonePermission
import fm.rizx.player.ui.screens.AboutScreen
import fm.rizx.player.ui.screens.EqualizerScreen
import fm.rizx.player.ui.screens.LicensesScreen
import fm.rizx.player.ui.screens.LyricsScreen
import fm.rizx.player.ui.screens.AlbumDetailScreen
import fm.rizx.player.ui.screens.ArtistDetailScreen
import fm.rizx.player.ui.screens.HomeScreen
import fm.rizx.player.ui.screens.EditorialPlaylistScreen
import fm.rizx.player.ui.screens.LibraryScreen
import fm.rizx.player.ui.screens.LibraryTab
import fm.rizx.player.ui.screens.LocalAlbumScreen
import fm.rizx.player.ui.screens.LocalArtistScreen
import fm.rizx.player.ui.screens.LocalLibraryScreen
import fm.rizx.player.ui.screens.NowPlayingScreen
import fm.rizx.player.ui.screens.PlaylistDetailScreen
import fm.rizx.player.ui.screens.PreferencesScreen
import fm.rizx.player.ui.screens.QueueScreen
import fm.rizx.player.ui.screens.SearchScreen
import fm.rizx.player.ui.screens.PluginsScreen
import fm.rizx.player.ui.theme.ClampedFontScale
import fm.rizx.player.ui.theme.LocalBottomInset
import fm.rizx.player.ui.theme.RizxTheme
import fm.rizx.player.ui.theme.blueprintCircles
import fm.rizx.player.ui.theme.blueprintGrid

@Composable
fun RizxApp(playerViewModel: PlayerViewModel) {
    val c = RizxTheme.colors
    val nav = rememberNavController()
    val themeMode by playerViewModel.themeMode.collectAsStateWithLifecycle()
    // Activity-scoped queue shared by the search actions and the queue screen (repo is a singleton).
    val queueViewModel: QueueViewModel = hiltViewModel()
    val queue by queueViewModel.queue.collectAsStateWithLifecycle()
    // Real playback (single ExoPlayer behind the controller); drives the mini-player + full player.
    val playbackViewModel: PlaybackViewModel = hiltViewModel()
    val playbackState by playbackViewModel.state.collectAsStateWithLifecycle()
    val currentItem by playbackViewModel.currentItem.collectAsStateWithLifecycle()
    val isFavorite by playbackViewModel.currentIsFavorite.collectAsStateWithLifecycle()
    // The player's cover and its artist link both need a resolution step for tracks that came from
    // YouTube, so they're state rather than a straight read off the current track.
    // Data saving pulls every grid tile down to the thumbnail rung — roughly a tenth of the bytes.
    val thriftyArtwork by playbackViewModel.thriftyArtwork.collectAsStateWithLifecycle()
    val npArtworkUrl by playbackViewModel.currentArtworkUrl.collectAsStateWithLifecycle()
    val npArtists by playbackViewModel.currentArtists.collectAsStateWithLifecycle()
    // Held as a State (not read here) so the ~25fps spectrum only invalidates the waveform's draw.
    val levelsState = playbackViewModel.levels.collectAsStateWithLifecycle()
    val losslessCodecs by playbackViewModel.losslessCodecs.collectAsStateWithLifecycle()
    // Library (favorites + playlists) shared for the app-wide "add to playlist" picker.
    val libraryViewModel: LibraryViewModel = hiltViewModel()
    val playlists by libraryViewModel.playlistSummaries.collectAsStateWithLifecycle()
    var addToPlaylistTrack: Track? by remember { mutableStateOf(null) }
    val backStackEntry by nav.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route

    addToPlaylistTrack?.let { track ->
        AddToPlaylistDialog(
            playlists = playlists,
            onPick = { libraryViewModel.addTrackToPlaylist(it, track) },
            onCreateNew = { libraryViewModel.createPlaylistWithTrack(it, track) },
            onDismiss = { addToPlaylistTrack = null },
        )
    }

    // Asked once, on the first download, and from here rather than from a screen: downloads start in the
    // Library, in an album, in a playlist and in the player's ⋮ menu, which are four different ViewModels
    // and four different places this question would otherwise have to live.
    val askSaveToPhone by libraryViewModel.askSaveToPhone.collectAsStateWithLifecycle()
    var saveToPhoneOpen by remember { mutableStateOf(false) }
    LaunchedEffect(askSaveToPhone) { if (askSaveToPhone) saveToPhoneOpen = true }
    // Android 8–9 (API < 29) additionally need WRITE_EXTERNAL_STORAGE to publish into Music/. A refusal
    // stores nothing — the next download simply asks again, the same semantics as dismissing the dialog.
    val ensureSavePermission = rememberSaveToPhonePermission()
    if (saveToPhoneOpen) {
        SaveDownloadsToPhoneDialog(
            onChoose = { alsoPhone ->
                saveToPhoneOpen = false
                if (alsoPhone) ensureSavePermission { libraryViewModel.setSaveToPhone(true) }
                else libraryViewModel.setSaveToPhone(false)
            },
            // Dismissing decides nothing, so nothing is stored: the next download asks again rather than
            // quietly settling on an answer the user never gave.
            onDismiss = { saveToPhoneOpen = false },
        )
    }

    // Blueprint background — BOTH themes, made prominent: technical grid + registration crosshairs +
    // large construction circles/arcs + square markers (refs #2/#4 "spec-sheet" poster).
    val gridColor = if (c.isDark) Color.White.copy(alpha = 0.35f) else Color(0xFF221F1A).copy(alpha = 0.24f)
    val circleColor = if (c.isDark) Color.White.copy(alpha = 0.17f) else Color(0xFF221F1A).copy(alpha = 0.17f)
    // How tall the floating chrome (mini-player + bottom nav) actually is, measured rather than guessed.
    // Screens end their scrollable content above this, which is what stops the mini-player covering the
    // last rows of a list. It changes with the system navigation mode, the font scale, and whether the
    // mini-player is showing, so no constant could have been right.
    var chromeHeight by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current

    Box(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .blueprintGrid(gridColor, cell = 28.dp)
            .blueprintCircles(circleColor),
    ) {
      CompositionLocalProvider(
          LocalBottomInset provides chromeHeight.coerceAtLeast(24.dp),
          // One policy, read by every grid tile in the app — see `tileUrl()`.
          LocalThriftyArtwork provides thriftyArtwork,
          // Which songs have played losslessly, for the rows that mark them — see `LosslessTag`.
          LocalLosslessCodecs provides losslessCodecs,
      ) {
        NavHost(
            navController = nav,
            startDestination = Routes.HOME,
            modifier = Modifier.fillMaxSize(),
            // Nothing-OS mechanical transitions: a quick fade with a small directional slide.
            enterTransition = { fadeIn(tween(220)) + slideInHorizontally(tween(240, easing = FastOutSlowInEasing)) { it / 14 } },
            exitTransition = { fadeOut(tween(160)) + slideOutHorizontally(tween(200)) { -it / 22 } },
            popEnterTransition = { fadeIn(tween(220)) + slideInHorizontally(tween(240, easing = FastOutSlowInEasing)) { -it / 14 } },
            popExitTransition = { fadeOut(tween(160)) + slideOutHorizontally(tween(200)) { it / 22 } },
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    onOpenSearch = { nav.navigateTab(Routes.SEARCH) },
                    // Liked songs live in the Library now — go straight to that tab, as a tab switch.
                    onOpenLikes = { nav.navigateTabAt(Routes.library(LibraryTab.Liked.name)) },
                    onOpenAlbum = { nav.navigate(Routes.albumDetail(it)) },
                    onOpenArtist = { nav.navigate(Routes.artistDetail(it)) },
                    onOpenEditorialPlaylist = { nav.navigate(Routes.editorialPlaylist(it)) },
                )
            }
            composable(Routes.SEARCH) {
                SearchScreen(
                    onOpenQueue = { nav.navigate(Routes.QUEUE) },
                    // Which engine keeps "next" going is the user's choice (Settings → Recommendations);
                    // the controller reads it, so search and the feed always agree.
                    onPlay = { playbackViewModel.playAutoRadio(it) },
                    onAddToQueue = queueViewModel::addToQueue,
                    onAddNext = queueViewModel::addNext,
                    onOpenAlbum = { nav.navigate(Routes.albumDetail(it)) },
                    onOpenArtist = { nav.navigate(Routes.artistDetail(it)) },
                    onOpenPlaylist = { nav.navigate(Routes.editorialPlaylist(it)) },
                    queueCount = queue.items.size,
                )
            }
            composable(
                Routes.LIBRARY_ROUTE,
                arguments = listOf(
                    navArgument(Routes.LIBRARY_TAB_ARG) {
                        type = NavType.StringType
                        defaultValue = LibraryTab.All.name
                    },
                ),
            ) { entry ->
                val requested = entry.arguments?.getString(Routes.LIBRARY_TAB_ARG)
                LibraryScreen(
                    onOpenPlaylist = { nav.navigate(Routes.playlistDetail(it)) },
                    onOpenLocal = { nav.navigate(Routes.LOCAL) },
                    initialTab = LibraryTab.entries.firstOrNull { it.name == requested } ?: LibraryTab.All,
                )
            }
            composable(
                "${Routes.PLAYLIST_DETAIL}/{playlistId}",
                arguments = listOf(navArgument("playlistId") { type = NavType.StringType }),
            ) {
                PlaylistDetailScreen(onBack = { nav.popBackStack() })
            }
            composable(
                Routes.ALBUM_DETAIL_ROUTE,
                arguments = listOf(
                    navArgument("provider") { type = NavType.StringType },
                    navArgument("id") { type = NavType.StringType },
                ),
            ) {
                AlbumDetailScreen(
                    onBack = { nav.popBackStack() },
                    onOpenArtist = { nav.navigate(Routes.artistDetail(it)) },
                )
            }
            composable(
                Routes.ARTIST_DETAIL_ROUTE,
                arguments = listOf(
                    navArgument("provider") { type = NavType.StringType },
                    navArgument("id") { type = NavType.StringType },
                ),
            ) {
                ArtistDetailScreen(
                    onBack = { nav.popBackStack() },
                    onOpenAlbum = { nav.navigate(Routes.albumDetail(it)) },
                    // A similar artist opens their own page, from where the next one is reachable again.
                    onOpenArtist = { nav.navigate(Routes.artistDetail(it)) },
                    onAddToQueue = queueViewModel::addToQueue,
                    onAddNext = queueViewModel::addNext,
                )
            }
            composable(
                Routes.EDITORIAL_PLAYLIST_ROUTE,
                arguments = listOf(
                    navArgument("provider") { type = NavType.StringType },
                    navArgument("id") { type = NavType.StringType },
                    navArgument("name") { type = NavType.StringType; defaultValue = "" },
                ),
            ) {
                EditorialPlaylistScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.LOCAL) {
                LocalLibraryScreen(
                    onBack = { nav.popBackStack() },
                    onOpenAlbum = { nav.navigate(Routes.localAlbum(it)) },
                    onOpenArtist = { nav.navigate(Routes.localArtist(it)) },
                    onOpenPlaylist = { nav.navigate(Routes.playlistDetail(it)) },
                    onAddToPlaylist = { addToPlaylistTrack = it },
                    onAddToQueue = queueViewModel::addToQueue,
                    onAddNext = queueViewModel::addNext,
                )
            }
            composable(
                Routes.LOCAL_ALBUM_ROUTE,
                arguments = listOf(navArgument("albumId") { type = NavType.StringType }),
            ) { entry ->
                LocalAlbumScreen(
                    albumId = entry.arguments?.getString("albumId").orEmpty(),
                    onBack = { nav.popBackStack() },
                    onAddToPlaylist = { addToPlaylistTrack = it },
                    onAddToQueue = queueViewModel::addToQueue,
                    onAddNext = queueViewModel::addNext,
                )
            }
            composable(
                Routes.LOCAL_ARTIST_ROUTE,
                arguments = listOf(navArgument("artistId") { type = NavType.StringType }),
            ) { entry ->
                LocalArtistScreen(
                    artistId = entry.arguments?.getString("artistId").orEmpty(),
                    onBack = { nav.popBackStack() },
                    onOpenAlbum = { nav.navigate(Routes.localAlbum(it)) },
                    onAddToPlaylist = { addToPlaylistTrack = it },
                    onAddToQueue = queueViewModel::addToQueue,
                    onAddNext = queueViewModel::addNext,
                )
            }
            composable(
                Routes.NOW_PLAYING,
                enterTransition = { slideInVertically(tween(320, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(240)) },
                popExitTransition = { slideOutVertically(tween(300, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(220)) },
            ) {
                val np = currentItem
                // Scoped to this back-stack entry, so the canvas player is released the moment you
                // leave Now Playing rather than decoding video behind another screen.
                val canvasViewModel: CanvasViewModel = hiltViewModel()
                val canvasState by canvasViewModel.state.collectAsStateWithLifecycle()
                val canvasOn by canvasViewModel.enabled.collectAsStateWithLifecycle()
                val downloadStates by libraryViewModel.downloadStates.collectAsStateWithLifecycle()
                val context = LocalContext.current
                LaunchedEffect(np?.track?.source, canvasOn) { canvasViewModel.show(np?.track) }
                // The canvas only decodes while Now Playing is genuinely in front. This covers pressing
                // Home, the screen switching off, and navigating anywhere else — all of which used to
                // leave a second ExoPlayer buffering video behind the user's back, because the ViewModel's
                // pause()/resume() pair was written and then never called from anywhere.
                LifecycleResumeEffect(canvasViewModel) {
                    canvasViewModel.setVisible(true)
                    onPauseOrDispose { canvasViewModel.setVisible(false) }
                }
                // Fall back to the track's metadata duration until the engine reports its own, so a
                // restored (or still-buffering) track shows its real elapsed second immediately instead
                // of 0:00 while the stream resolves.
                val npAudioFormat by playbackViewModel.audioFormat.collectAsStateWithLifecycle()
                val npDurationMs = playbackState.durationMs.takeIf { it > 0L } ?: (np?.track?.durationMs ?: 0L)
                val npProgress = if (npDurationMs > 0L) {
                    (playbackState.positionMs.toFloat() / npDurationMs).coerceIn(0f, 1f)
                } else {
                    0f
                }
                NowPlayingScreen(
                    title = np?.track?.title ?: stringResource(fm.rizx.player.R.string.now_playing_empty),
                    artist = np?.track?.artists?.joinToString { it.name }?.ifEmpty { "—" } ?: "—",
                    audioFormat = npAudioFormat,
                    artworkUrl = npArtworkUrl,
                    isPlaying = playbackState.isPlaying,
                    progress = npProgress,
                    durationSec = (npDurationMs / 1000L).toInt(),
                    liked = isFavorite,
                    onBack = { nav.popBackStack() },
                    onTogglePlay = playbackViewModel::toggle,
                    onToggleLike = playbackViewModel::toggleCurrentFavorite,
                    onSeek = playbackViewModel::seekToFraction,
                    onNext = playbackViewModel::next,
                    onPrevious = playbackViewModel::previous,
                    onAddToPlaylist = { addToPlaylistTrack = np?.track },
                    onOpenLyrics = { nav.navigate(Routes.LYRICS) },
                    // Nearby devices: hand off to Android's own output switcher (no cast SDK). Radio:
                    // reuse the existing artist-radio the controller already knows how to fill.
                    onOpenDevices = { context.openAudioOutputSwitcher() },
                    onStartRadio = { np?.track?.let { playbackViewModel.playAutoRadio(it) } },
                    // One entry per artist, resolved ahead of the tap: a collaboration's names are
                    // separately tappable, and each opens that artist's real profile rather than the
                    // duplicate row a feature credit was filed under. No page found → not tappable.
                    artistLinks = npArtists,
                    onOpenArtist = { ref -> nav.navigate(Routes.artistDetail(ref)) },
                    album = np?.track?.album?.title ?: "",
                    trackIndex = queue.currentIndex,
                    trackCount = queue.items.size,
                    repeatMode = queue.repeatMode,
                    shuffleOn = queue.shuffleOn,
                    onToggleRepeat = queueViewModel::cycleRepeatMode,
                    onToggleShuffle = queueViewModel::toggleShuffle,
                    // Null until there is something to attach it to: the screen creates the surface on
                    // this, not on the fade, so the first frame has somewhere to land — but a song with
                    // no canvas must still not pay for a TextureView.
                    canvasVideo = if (canvasState.hasCandidate) canvasViewModel::attach else null,
                    canvasPlaying = canvasState.playing,
                    queue = queue,
                    // Parity with the full Queue screen: tapping a drawer row must actually start that song
                    // (seek+play), not just move the cursor (goToId). The drawer used the cursor-only path.
                    onPlayQueueItem = playbackViewModel::playQueueItem,
                    onRemoveQueueItem = queueViewModel::removeItem,
                    onMoveQueueItem = queueViewModel::move,
                    menu = { expanded, onDismiss ->
                        val track = np?.track
                        NowPlayingMenu(
                            expanded = expanded,
                            onDismiss = onDismiss,
                            download = track?.let { downloadStates[it.source.identityKey] },
                            canvasOn = canvasOn,
                            canvasAvailable = canvasState.playing || canvasState.resolving,
                            onDownload = { track?.let(libraryViewModel::downloadTrack) },
                            onDownloadAs = { format -> track?.let { libraryViewModel.downloadTrackAs(it, format) } },
                            onDeleteDownload = { track?.let { libraryViewModel.deleteDownload(it.source.identityKey) } },
                            onToggleCanvas = canvasViewModel::toggle,
                            onShare = { track?.let { context.shareTrack(it) } },
                        )
                    },
                    loading = playbackState.isLoading,
                    levels = { levelsState.value },
                )
            }
            composable(
                Routes.QUEUE,
                enterTransition = { slideInVertically(tween(320, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(240)) },
                popExitTransition = { slideOutVertically(tween(300, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(220)) },
            ) {
                QueueScreen(
                    queue = queue,
                    onBack = { nav.popBackStack() },
                    onPlayItem = playbackViewModel::playQueueItem,
                    onRemoveItem = queueViewModel::removeItem,
                    onClear = queueViewModel::clear,
                    onSaveAsPlaylist = queueViewModel::saveAsPlaylist,
                )
            }
            composable(Routes.SOURCES) {
                PluginsScreen()
            }
            composable(Routes.SETTINGS) {
                PreferencesScreen(
                    themeMode = themeMode,
                    onSetThemeMode = playerViewModel::setThemeMode,
                    onOpenSources = { nav.navigate(Routes.SOURCES) },
                    onOpenEqualizer = { nav.navigate(Routes.EQUALIZER) },
                    onOpenAbout = { nav.navigate(Routes.ABOUT) },
                )
            }
            composable(Routes.ABOUT) {
                AboutScreen(
                    onBack = { nav.popBackStack() },
                    onOpenLicenses = { nav.navigate(Routes.LICENSES) },
                )
            }
            composable(Routes.LICENSES) {
                LicensesScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.EQUALIZER) {
                EqualizerScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.LYRICS) {
                // Position is passed as a lambda, not a value: the 4 Hz ticker would otherwise recompose
                // the whole lyric list instead of just the line that changed.
                LyricsScreen(
                    onBack = { nav.popBackStack() },
                    positionMs = { playbackState.positionMs },
                    durationMs = playbackState.durationMs.takeIf { it > 0L }
                        ?: (currentItem?.track?.durationMs ?: 0L),
                    isPlaying = playbackState.isPlaying,
                    onTogglePlay = playbackViewModel::toggle,
                    onNext = playbackViewModel::next,
                    onPrevious = playbackViewModel::previous,
                    onSeekMs = { ms ->
                        val total = playbackState.durationMs.takeIf { it > 0L }
                            ?: currentItem?.track?.durationMs
                        if (total != null && total > 0L) {
                            playbackViewModel.seekToFraction(ms.toFloat() / total)
                        }
                    },
                )
            }
        }

      }

        // Floating chrome: mini-player + bottom nav, per-route.
        val showNav = route in Routes.withBottomNav
        // The mini-player follows real playback: it shows wherever something is loaded, except on the
        // full player and the queue list themselves.
        // Lyrics is excluded alongside them: it carries its own transport, and two stacked sets of play
        // controls is worse than none.
        val showMini = currentItem != null &&
            route != Routes.NOW_PLAYING &&
            route != Routes.QUEUE &&
            route != Routes.LYRICS
        if (showNav || showMini) {
          // The chrome is fixed-height by design; a 1.3x system font would otherwise push its controls
          // off the edge. Body text everywhere else still scales fully.
          ClampedFontScale {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .onSizeChanged { chromeHeight = with(density) { it.height.toDp() } }
                    .navigationBarsPadding()
                    .padding(horizontal = 14.dp)
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AnimatedVisibility(
                    visible = showMini,
                    enter = slideInVertically(tween(260, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(200)),
                    exit = slideOutVertically(tween(220)) { it } + fadeOut(tween(160)),
                ) {
                    val mini = currentItem
                    val miniDurationMs = playbackState.durationMs.takeIf { it > 0L } ?: (mini?.track?.durationMs ?: 0L)
                    val miniProgress = if (miniDurationMs > 0L) {
                        (playbackState.positionMs.toFloat() / miniDurationMs).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    MiniPlayer(
                        title = mini?.track?.title ?: "",
                        artist = mini?.track?.artists?.joinToString { it.name }?.ifEmpty { "—" } ?: "—",
                        isPlaying = playbackState.isPlaying,
                        onClick = { nav.navigate(Routes.NOW_PLAYING) },
                        onPlayPause = playbackViewModel::toggle,
                        onLike = playbackViewModel::toggleCurrentFavorite,
                        // Same resolved cover as the full player, so opening it doesn't swap the image.
                        artworkUrl = npArtworkUrl ?: mini?.track?.artwork?.coverUrl(),
                        progress = miniProgress,
                        loading = playbackState.isLoading,
                        liked = isFavorite,
                        positionMs = playbackState.positionMs,
                        durationMs = miniDurationMs,
                        onSeek = playbackViewModel::seekToFraction,
                        // Read from the same map the rows use, not from the format readout, so the bar and
                        // a list agree — and so the tag survives the setting that hides the spec line.
                        losslessLabel = mini?.track?.source?.identityKey?.let { losslessCodecs[it] },
                    )
                }
                if (showNav) {
                    RizxBottomNav(currentRoute = route, onSelect = { nav.navigateTab(it) })
                }
            }
          }
        }
    }
}

/** Bottom-nav tab switch: single-top, saving/restoring each tab's back stack. */
private fun NavController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * Open a **tab** at a specific place inside it (the Home heart → Library's "Liked").
 *
 * It has to pop like a tab switch rather than push like a detail screen. A plain `navigate` leaves a
 * tab destination sitting on top of Home without the saved state a tab switch records, and from there
 * every bottom-nav tap is a no-op: the bar looks alive and does nothing. That was a real bug — the
 * heart on the Home screen stranded the user in the Library.
 *
 * `restoreState` is deliberately **not** set: this call carries an argument (which tab), and restoring
 * a previously-saved Library entry would quietly land on the tab the user was on last instead.
 */
private fun NavController.navigateTabAt(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
    }
}
