package fm.rizx.player.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import dagger.hilt.android.lifecycle.HiltViewModel
import fm.rizx.player.core.network.DataSaverState
import fm.rizx.player.playback.AudioVisualizer
import fm.rizx.player.data.artwork.TrackArtworkEnricher
import fm.rizx.player.domain.model.AudioFormatUi
import fm.rizx.player.domain.model.QueueItem
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.model.coverUrl
import fm.rizx.player.domain.model.heroUrl
import fm.rizx.player.domain.playback.NowPlayingFormat
import fm.rizx.player.domain.playback.PlaybackController
import fm.rizx.player.domain.playback.PlaybackState
import fm.rizx.player.domain.repository.SettingsRepository
import fm.rizx.player.domain.repository.FavoritesRepository
import fm.rizx.player.domain.repository.QueueRepository
import fm.rizx.player.domain.usecase.LinkedArtist
import fm.rizx.player.domain.usecase.ResolveTrackArtistsUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI seam for real playback: re-exposes the [PlaybackController]'s engine truth ([state]) plus the
 * currently-playing [currentItem] (from the queue cursor the controller keeps in sync), and forwards
 * transport intents. The mini-player and full player observe this; neither touches ExoPlayer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
// Kotlin's @OptIn doesn't satisfy androidx's experimental checker for media3's Java @UnstableApi —
// lint requires the androidx variant.
@androidx.annotation.OptIn(UnstableApi::class)
@HiltViewModel
class PlaybackViewModel @Inject constructor(
    private val controller: PlaybackController,
    private val favorites: FavoritesRepository,
    private val artwork: TrackArtworkEnricher,
    private val resolveTrackArtists: ResolveTrackArtistsUseCase,
    visualizer: AudioVisualizer,
    queue: QueueRepository,
    nowPlayingFormat: NowPlayingFormat,
    settings: SettingsRepository,
    dataSaver: DataSaverState,
    private val spatial: fm.rizx.player.domain.playback.SpatialAudioController,
) : ViewModel() {

    /** Adaptive stereo spatialization, for the overflow menu's row. */
    val spatialState = spatial.state

    /** Applies to the song already playing — the engine fades between dry and wet rather than jumping. */
    fun toggleSpatialAudio() = spatial.setEnabled(!spatial.state.value.enabled)

    val state: StateFlow<PlaybackState> = controller.state

    /** Live audio spectrum (0..1 per bar) for the Now Playing waveform visualizer. */
    val levels: StateFlow<FloatArray> = visualizer.levels

    val currentItem: StateFlow<QueueItem?> = queue.state
        .map { it.current }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * The playing **track**, changing only when the song does.
     *
     * [currentItem] is a `QueueItem`, and its `status` walks IDLE → LOADING → SUCCESS while the stream
     * resolves — so collecting it re-emits several times per song. Anything that answers a *question
     * about the song* (its cover, its artist) must hang off this instead, or it restarts mid-flight and
     * fires the same lookup three or four times over.
     */
    private val currentTrack: StateFlow<Track?> = currentItem
        .map { it?.track }
        .distinctUntilChangedBy { it?.source?.identityKey }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * The codec/depth/rate line for the song on screen, or null when there is nothing honest to say.
     *
     * The id check is what keeps it truthful across a skip: the resolver publishes per queue item, and
     * for the second or two before the next track resolves the previous answer is still the newest one.
     * Showing it then would put "FLAC · 16-bit · 48 kHz" under a song that is about to play as Opus.
     */
    val audioFormat: StateFlow<AudioFormatUi?> =
        combine(currentItem, nowPlayingFormat.current, settings.showTechnicalFormat) { item, entry, show ->
            if (!show || item == null || entry == null || entry.queueItemId != item.id) null else entry.format
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Songs known to play losslessly, so a row anywhere in the app can mark one.
     *
     * Not gated on `showTechnicalFormat`: that setting is about the codec/depth/rate *readout* under the
     * player ("Show technical format"), and this is a quality mark rather than a spec line.
     */
    val losslessCodecs: StateFlow<Map<String, String>> = nowPlayingFormat.losslessCodecs

    /**
     * Whether grids and lists should fetch the cheap artwork rung.
     *
     * Lives here rather than in a screen's ViewModel because it is provided once at the root and read by
     * every tile in the app; this one is already the app-wide ViewModel that `RizxApp` holds.
     */
    val thriftyArtwork: StateFlow<Boolean> =
        dataSaver.saving.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Whether the currently-playing track is favorited (live, so the heart updates immediately). */
    val currentIsFavorite: StateFlow<Boolean> = currentItem
        .flatMapLatest { item -> item?.let { favorites.isFavoriteTrack(it.track.source) } ?: flowOf(false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * The cover to show for the current track, at the size a full-screen player needs.
     *
     * Emits twice on purpose: first whatever the track already carries, so the art appears instantly,
     * then a better one if there is a better one. That second emission is what fixes a YouTube-Mix
     * song, whose "cover" is a 16:9 video still that the player crops into a blurry band — the lookup
     * is memoized and request-collapsed, so in practice it is free after the first time.
     */
    val currentArtworkUrl: StateFlow<String?> = currentTrack
        .flatMapLatest { track ->
            track ?: return@flatMapLatest flowOf<String?>(null)
            flow {
                // heroUrl, not coverUrl: this one fills the screen, so it asks for the largest rung a
                // provider publishes rather than the tile-sized one every grid now takes.
                val own = track.artwork.heroUrl()
                emit(own)
                val best = runCatching { artwork.coverFor(track) }.getOrNull()
                if (best != null && best != own) emit(best)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * The song's artists, each with the page a tap should open — empty `source` meaning "nowhere safe
     * to go", which leaves that name untappable rather than navigating somewhere wrong.
     *
     * One entry per *artist*, not per credit: a YouTube-Mix song bills "Omar Courtz & De La Rose" as a
     * single string, and [ResolveTrackArtistsUseCase] is what turns that into two links — and what
     * keeps each link on the artist's real profile instead of the catalogue's duplicate row. Resolved
     * ahead of the tap so the tap itself is instant.
     */
    val currentArtists: StateFlow<List<LinkedArtist>> = currentTrack
        // Keyed on the billing, not the track: an album's worth of songs by the same artist resolves once.
        .distinctUntilChangedBy { track ->
            track?.artists.orEmpty().joinToString("|") { it.source?.identityKey ?: it.name }
        }
        .mapLatest { track -> runCatching { resolveTrackArtists(track) }.getOrDefault(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun toggleCurrentFavorite() {
        val track = currentItem.value?.track ?: return
        viewModelScope.launch { favorites.toggleTrack(track) }
    }

    fun playQueueItem(id: String) = controller.playQueueItem(id)

    /** Play [track] as a radio (feed): the service auto-fills similar tracks for next/prev. */
    fun playRadio(track: Track) = controller.playRadio(track)

    /** Play [track] as a YouTube-Mix radio (search): next/prev follow YT Music's own recommendations. */
    fun playYoutubeRadio(track: Track) = controller.playYoutubeRadio(track)

    /** Play [track] as a radio using the recommendation engine chosen in Settings. */
    fun playAutoRadio(track: Track) = controller.playAutoRadio(track)

    fun toggle() = controller.toggle()

    fun next() = controller.skipNext()

    fun previous() = controller.skipPrevious()

    fun stop() = controller.stop()

    /** Seek to a 0..1 fraction of the current item's duration. */
    fun seekToFraction(fraction: Float) {
        val durationMs = state.value.durationMs
        if (durationMs > 0L) controller.seekTo((fraction.coerceIn(0f, 1f) * durationMs).toLong())
    }
}
