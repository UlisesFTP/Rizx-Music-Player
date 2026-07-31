package fm.rizx.player.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fm.rizx.player.core.error.AppError
import fm.rizx.player.core.error.toSafeMessage
import fm.rizx.player.data.artist.ArtistBioSource
import fm.rizx.player.domain.model.Artist
import fm.rizx.player.domain.model.ArtistBio
import fm.rizx.player.domain.model.ArtistRef
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.QueueContext
import fm.rizx.player.domain.model.QueueSourceKind
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.playback.PlaybackController
import fm.rizx.player.domain.repository.MetadataRepository
import fm.rizx.player.domain.repository.QueueRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/** UI state for the artist detail screen. */
sealed interface ArtistUiState {
    data object Loading : ArtistUiState
    data class Content(
        val artist: Artist,
        /** Artists the catalogue considers similar; empty when it publishes none. */
        val similar: List<ArtistRef> = emptyList(),
        /** Wikipedia's lead paragraph, when one could be trusted. */
        val bio: ArtistBio? = null,
    ) : ArtistUiState
    data object Offline : ArtistUiState
    data class Error(val message: String) : ArtistUiState
}

/**
 * Loads everything the artist page shows, by the nav-arg `ProviderRef`.
 *
 * The three sources run **together and are published once**: the catalogue's own artist detail (which
 * the page cannot exist without), the similar artists, and the biography. Emitting them separately
 * would drop two blocks into a page the user has already started reading; waiting for them in
 * sequence would make the page as slow as the slowest. Both extras are bounded by [EXTRAS_TIMEOUT_MS]
 * and both are optional — a failure means that section is simply absent.
 */
@HiltViewModel
class ArtistDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val metadata: MetadataRepository,
    private val queue: QueueRepository,
    private val playback: PlaybackController,
    private val bios: ArtistBioSource,
) : ViewModel() {

    private val source = ProviderRef(
        provider = checkNotNull(savedStateHandle["provider"]),
        id = checkNotNull(savedStateHandle["id"]),
    )

    private val _state = MutableStateFlow<ArtistUiState>(ArtistUiState.Loading)
    val state: StateFlow<ArtistUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = ArtistUiState.Loading
            _state.value = try {
                val artist = metadata.artistDetail(source)
                if (artist == null) {
                    ArtistUiState.Error("Artist not found")
                } else {
                    supervisorScope {
                        // Started before either is awaited, so they overlap rather than queue up.
                        val similar = async { extra { metadata.relatedArtists(source) }.orEmpty() }
                        val bio = async { extra { bios.bioFor(source.identityKey, artist.name) } }
                        ArtistUiState.Content(artist, similar.await(), bio.await())
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppError.Network) {
                ArtistUiState.Offline
            } catch (e: Exception) {
                ArtistUiState.Error(e.toSafeMessage("Couldn't load artist"))
            }
        }
    }

    /**
     * Play [tracks] from [index] — **the list the user is looking at**, so a filtered song list plays
     * what it shows and next/previous walk it. Passing the artist's full top-track list here instead
     * would play a different song from the one that was tapped.
     */
    fun play(index: Int, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        playback.playContext(tracks, index, context())
    }

    /**
     * Shuffle the artist's songs.
     *
     * Order matters: `setShuffle` on an empty queue only records the preference, and whatever is
     * played next starts shuffled. Doing it the other way round would shuffle around a first track
     * that stays pinned — which is not what pressing shuffle means.
     */
    fun shuffle(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        queue.setShuffle(true)
        playback.playContext(tracks, 0, context())
    }

    /** Start a radio from this artist, following whichever engine Settings has chosen. */
    fun radio(tracks: List<Track>) {
        tracks.firstOrNull()?.let(playback::playAutoRadio)
    }

    private fun context(): QueueContext {
        val name = (_state.value as? ArtistUiState.Content)?.artist?.name.orEmpty()
        return QueueContext(kind = QueueSourceKind.ARTIST, label = name)
    }

    /** An optional section: bounded, and silent about anything that goes wrong. */
    private suspend fun <T> extra(block: suspend () -> T): T? = try {
        withTimeoutOrNull(EXTRAS_TIMEOUT_MS) { block() }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private companion object {
        /** Long enough for two round-trips, short enough that a slow extra can't hold the page. */
        const val EXTRAS_TIMEOUT_MS = 2_500L
    }
}
