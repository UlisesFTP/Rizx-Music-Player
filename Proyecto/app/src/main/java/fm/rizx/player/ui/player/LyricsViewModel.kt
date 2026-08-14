package fm.rizx.player.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fm.rizx.player.core.error.AppError
import fm.rizx.player.core.error.toSafeMessage
import fm.rizx.player.domain.lyrics.LyricsScript
import fm.rizx.player.domain.lyrics.availableModes
import fm.rizx.player.domain.model.Lyrics
import fm.rizx.player.domain.model.LyricsCandidate
import fm.rizx.player.domain.model.LyricsDisplayMode
import fm.rizx.player.domain.model.LyricsVisualQuality
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.model.coverUrl
import fm.rizx.player.domain.playback.PlaybackController
import fm.rizx.player.domain.playback.PlaybackState
import fm.rizx.player.domain.repository.LyricsRepository
import fm.rizx.player.domain.repository.QueueRepository
import fm.rizx.player.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/** What the lyrics pane is showing right now. */
sealed interface LyricsContent {
    data object NoTrack : LyricsContent
    data object Loading : LyricsContent
    data class Ready(val lyrics: Lyrics, val offsetMs: Long = 0L, val pinned: Boolean = false) : LyricsContent
    data class Empty(val title: String) : LyricsContent
    data object Offline : LyricsContent
    data class Error(val message: String) : LyricsContent
}

/** The manual picker, which only exists because automatic matching sometimes picks the wrong recording. */
sealed interface LyricsSearchState {
    data object Closed : LyricsSearchState
    data object Idle : LyricsSearchState
    data object Loading : LyricsSearchState
    data class Results(val items: List<LyricsCandidate>) : LyricsSearchState
    data object NoResults : LyricsSearchState
    data class Error(val message: String) : LyricsSearchState
}

data class LyricsUiState(
    val title: String = "",
    val artist: String = "",
    val artworkUrl: String? = null,
    val content: LyricsContent = LyricsContent.NoTrack,
    /** Karaoke view vs plain prose. Persisted, so it carries across songs. */
    val syncedMode: Boolean = true,
    /** How much the karaoke renderer is allowed to spend. Persisted; set from Settings. */
    val visualQuality: LyricsVisualQuality = LyricsVisualQuality.AUTOMATIC,
    /** Original script, pronunciation or translation. Persisted: it describes the reader, not the song. */
    val displayMode: LyricsDisplayMode = LyricsDisplayMode.ORIGINAL,
    /** The lyric is written in an alphabet the reader may not have — computed once, when it loads. */
    val foreignScript: Boolean = false,
    /** The reading being fetched right now. Null when nothing is in flight. */
    val pendingMode: LyricsDisplayMode? = null,
    /** A readings lookup has already run for this song, so what it has is all it will ever have. */
    val readingsTried: Boolean = false,
    val search: LyricsSearchState = LyricsSearchState.Closed,
) {
    /** The timed view is only possible when the lyric *has* timings and the user wants it. */
    val showSynced: Boolean
        get() = syncedMode && (content as? LyricsContent.Ready)?.lyrics?.isSynced == true

    /** The readings this song can be shown in right now. */
    val readingModes: List<LyricsDisplayMode>
        get() = (content as? LyricsContent.Ready)?.lyrics?.availableModes().orEmpty()

    /**
     * The readings to offer, which is not the same as the readings already in hand.
     *
     * A foreign-script lyric offers all three before anything has been fetched, because **that first tap
     * is what pays for them** — an endpoint that rate-limits after roughly ten calls can't be probed for
     * every song just to find out whether a button belongs on screen. Once the lookup has run, the offer
     * shrinks to what actually came back, so a reading that doesn't exist stops being advertised.
     *
     * A Latin-script song offers nothing: it would be a control that can only disappoint.
     */
    val offeredModes: List<LyricsDisplayMode>
        get() = when {
            content !is LyricsContent.Ready -> emptyList()
            readingsTried || !foreignScript -> readingModes.takeIf { it.size > 1 }.orEmpty()
            else -> LyricsDisplayMode.entries
        }

    /** The reading actually on screen — the chosen one when this song has it, else the original. */
    val effectiveMode: LyricsDisplayMode
        get() = if (displayMode in readingModes) displayMode else LyricsDisplayMode.ORIGINAL
}

/**
 * Drives the lyrics screen: resolves lyrics for whatever is playing and owns the corrections the user can
 * make to them (pick another version, shift the timing, switch to prose).
 *
 * Unlike a pure `flatMapLatest` chain, the state has to be *writable* — the offset and the pinned version
 * are user edits that must land without waiting for the track to change — so the track subscription
 * updates a [MutableStateFlow] rather than being the state itself.
 *
 * Every failure mode is a state, never a crash: no provider or no match → [LyricsContent.Empty], no
 * network → [LyricsContent.Offline].
 */
@HiltViewModel
class LyricsViewModel @Inject constructor(
    queue: QueueRepository,
    playback: PlaybackController,
    private val lyrics: LyricsRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(LyricsUiState())
    val state: StateFlow<LyricsUiState> = _state.asStateFlow()

    /**
     * The engine's position, handed to the karaoke view **as a flow**.
     *
     * Deliberately not folded into [state]: the position changes four times a second, and a screen that
     * recomposed at 4 Hz would undo the whole point of the sweep's draw-only update path. The lyrics list
     * subscribes to this directly and never reads it during composition.
     */
    val playbackState: StateFlow<PlaybackState> = playback.state

    private var track: Track? = null
    private var searchJob: Job? = null
    private var offsetJob: Job? = null
    private var readingsJob: Job? = null

    init {
        viewModelScope.launch {
            settings.syncedLyricsMode.collect { on -> _state.update { it.copy(syncedMode = on) } }
        }
        viewModelScope.launch {
            settings.lyricsVisualQuality.collect { q -> _state.update { it.copy(visualQuality = q) } }
        }
        viewModelScope.launch {
            settings.lyricsDisplayMode.collect { m -> _state.update { it.copy(displayMode = m) } }
        }
        viewModelScope.launch {
            queue.state
                .map { it.current?.track }
                .distinctUntilChanged { a, b -> a?.source == b?.source }
                // collectLatest: switching songs cancels the in-flight lookup for the previous one.
                .collectLatest { load(it) }
        }
    }

    // ---- User actions ----

    fun toggleSyncedMode() {
        viewModelScope.launch { settings.setSyncedLyricsMode(!_state.value.syncedMode) }
    }

    /**
     * Shows this song in [mode], fetching the readings first if this is the first time they're asked for.
     *
     * The fetch happens here rather than when the song loads because a translation costs a request to an
     * endpoint that rate-limits after roughly ten calls: it is spent when someone asks to read the song,
     * never when they merely open it. If [mode] turns out not to exist, the state keeps it — the lyric
     * simply reads as the original, and the chip for it disappears once the lookup has settled.
     */
    fun selectDisplayMode(mode: LyricsDisplayMode) {
        val song = track ?: return
        val ready = _state.value.content as? LyricsContent.Ready ?: return
        if (_state.value.pendingMode != null) return

        // A translation fetched before the app changed language is the wrong translation, so it counts
        // as missing rather than as something to show.
        val stale = mode == LyricsDisplayMode.TRANSLATION && ready.lyrics.translationLang != language()
        val have = mode == LyricsDisplayMode.ORIGINAL || mode in ready.lyrics.availableModes()

        if (have && !stale) {
            persist(mode)
        } else if (!_state.value.readingsTried) {
            loadReadings(song, ready.lyrics, mode)
        }
    }

    /** The language a translation should arrive in — whatever the app is currently being read in. */
    private fun language(): String = Locale.getDefault().language.ifBlank { FALLBACK_LANGUAGE }

    private fun persist(mode: LyricsDisplayMode) {
        viewModelScope.launch { settings.setLyricsDisplayMode(mode) }
    }

    private fun loadReadings(song: Track, current: Lyrics, wanted: LyricsDisplayMode) {
        readingsJob?.cancel()
        readingsJob = viewModelScope.launch {
            _state.update { it.copy(pendingMode = wanted) }
            val enriched = try {
                lyrics.readings(song, current, language())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                current // no reading is a normal answer here, not an error worth a red screen
            }
            // The song may have moved on while this was in flight; its readings are not this song's.
            if (track?.source != song.source) return@launch
            _state.update { s ->
                val ready = s.content as? LyricsContent.Ready
                s.copy(
                    content = ready?.copy(lyrics = enriched) ?: s.content,
                    pendingMode = null,
                    readingsTried = true,
                )
            }
            if (wanted in enriched.availableModes()) persist(wanted)
        }
    }

    /**
     * Shifts the words against the audio. Applied to the visible state immediately and persisted after,
     * so holding the button feels continuous rather than waiting on a disk write per tap.
     */
    fun nudgeOffset(deltaMs: Long) {
        val current = _state.value.content as? LyricsContent.Ready ?: return
        setOffset(current.offsetMs + deltaMs)
    }

    fun resetOffset() = setOffset(0L)

    private fun setOffset(offsetMs: Long) {
        val song = track ?: return
        val clamped = offsetMs.coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS)
        _state.update { s ->
            val ready = s.content as? LyricsContent.Ready ?: return@update s
            s.copy(content = ready.copy(offsetMs = clamped))
        }
        // Coalesced, and the previous write is cancelled rather than left to race. One launch per tap
        // would put two concurrent writes in flight with no ordering between them, so the *earlier*
        // value could land last and silently roll the correction back. It also means holding the button
        // costs one disk write instead of one per tap.
        offsetJob?.cancel()
        offsetJob = viewModelScope.launch {
            delay(OFFSET_WRITE_DEBOUNCE_MS)
            lyrics.setOffset(song, clamped)
        }
    }

    fun openSearch() {
        _state.update { it.copy(search = LyricsSearchState.Idle) }
    }

    fun closeSearch() {
        searchJob?.cancel()
        _state.update { it.copy(search = LyricsSearchState.Closed) }
    }

    /** The query the picker opens with — the song we're actually playing, which is usually right. */
    fun defaultQuery(): String = listOf(_state.value.artist, _state.value.title)
        .filter { it.isNotBlank() && it != "—" }
        .joinToString(" ")

    fun search(query: String) {
        val q = query.trim()
        if (q.isBlank()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _state.update { it.copy(search = LyricsSearchState.Loading) }
            val next = try {
                val results = lyrics.search(q)
                if (results.isEmpty()) LyricsSearchState.NoResults else LyricsSearchState.Results(results)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppError.Network) {
                LyricsSearchState.Error("You're offline. Connect and try again.")
            } catch (e: Exception) {
                LyricsSearchState.Error(e.toSafeMessage("Search failed"))
            }
            _state.update { it.copy(search = next) }
        }
    }

    /** Locks [candidate] in for this song. The offset resets: it described the previous transcription. */
    fun applyCandidate(candidate: LyricsCandidate) {
        val song = track ?: return
        _state.update {
            it.copy(
                content = LyricsContent.Ready(candidate.lyrics, offsetMs = 0L, pinned = true),
                search = LyricsSearchState.Closed,
            )
        }
        viewModelScope.launch { lyrics.pin(song, candidate) }
    }

    /** Drops the manual pick and the cached copy, then resolves again from the providers. */
    fun resetToAutomatic() {
        val song = track ?: return
        viewModelScope.launch {
            lyrics.clearOverride(song)
            load(song)
        }
    }

    fun retry() {
        viewModelScope.launch { load(track) }
    }

    // ---- Loading ----

    private suspend fun load(song: Track?) {
        track = song
        readingsJob?.cancel()
        if (song == null) {
            _state.update {
                it.copy(title = "", artist = "", artworkUrl = null, content = LyricsContent.NoTrack)
            }
            return
        }
        _state.update {
            it.copy(
                title = song.title,
                artist = song.artists.joinToString { a -> a.name }.ifEmpty { "—" },
                artworkUrl = song.artwork.coverUrl(),
                content = LyricsContent.Loading,
                search = LyricsSearchState.Closed,
            )
        }
        val content = try {
            val result = lyrics.lyricsFor(song)
            if (result == null || result.lyrics.isEmpty) {
                LyricsContent.Empty(song.title)
            } else {
                LyricsContent.Ready(result.lyrics, result.offsetMs, result.pinned)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AppError.Network) {
            LyricsContent.Offline
        } catch (e: Exception) {
            LyricsContent.Error(e.toSafeMessage("Couldn't load lyrics"))
        }
        // Which alphabet this is in decides whether the reading switch appears, and it can't change
        // until the words do — so it is answered once here rather than on every recomposition.
        val foreign = (content as? LyricsContent.Ready)?.lyrics?.let(LyricsScript::isForeign) == true
        _state.update {
            it.copy(content = content, foreignScript = foreign, pendingMode = null, readingsTried = false)
        }
    }

    private companion object {
        /** ±30 s covers any plausible intro difference; beyond that the match itself is wrong. */
        const val MAX_OFFSET_MS = 30_000L

        /** Musixmatch keys translations by language code; English is the one every song is likeliest to have. */
        const val FALLBACK_LANGUAGE = "en"

        /** Long enough to swallow a burst of taps, short enough to survive leaving the screen. */
        const val OFFSET_WRITE_DEBOUNCE_MS = 250L
    }
}
