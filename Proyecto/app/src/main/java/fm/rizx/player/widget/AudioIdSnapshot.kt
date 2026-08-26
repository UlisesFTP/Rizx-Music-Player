package fm.rizx.player.widget

import fm.rizx.player.domain.model.thumbnailUrl
import fm.rizx.player.domain.recognition.RecognitionHistoryItem
import fm.rizx.player.domain.recognition.RecognitionState

/** Where recognition stands, as far as a home screen widget can tell it. */
enum class AudioIdPhase { IDLE, LISTENING, IDENTIFYING, MATCHED, NO_MATCH, FAILED }

/**
 * The Audio ID widget's view: the session's phase while the app is listening, and otherwise the last
 * song it identified — title, artist, album, cover — with whether it can be played straight away
 * (only when the match was resolved to a playable track).
 */
data class AudioIdSnapshot(
    val phase: AudioIdPhase = AudioIdPhase.IDLE,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val artworkUrl: String? = null,
    val canPlay: Boolean = false,
) {
    val hasResult: Boolean get() = title != null

    companion object {
        val EMPTY = AudioIdSnapshot()

        fun of(state: RecognitionState, latest: RecognitionHistoryItem?): AudioIdSnapshot {
            val phase = when (state) {
                is RecognitionState.Listening -> AudioIdPhase.LISTENING
                is RecognitionState.Processing -> AudioIdPhase.IDENTIFYING
                is RecognitionState.Matched -> AudioIdPhase.MATCHED
                is RecognitionState.NoMatch -> AudioIdPhase.NO_MATCH
                is RecognitionState.Failed -> AudioIdPhase.FAILED
                else -> AudioIdPhase.IDLE
            }
            // A live match is the freshest thing there is; otherwise the newest entry of the history. Match
            // and track travel together: the history's track must never play under a live match's title.
            val live = state as? RecognitionState.Matched
            val match = live?.match ?: latest?.match
            val track = if (live != null) live.resolvedTrack else latest?.resolvedTrack
            if (match == null) return AudioIdSnapshot(phase = phase)
            return AudioIdSnapshot(
                phase = phase,
                title = match.title,
                artist = match.artist.ifBlank { null },
                album = match.album?.ifBlank { null },
                artworkUrl = match.artworkHqUrl ?: match.artworkUrl ?: track?.artwork.thumbnailUrl(),
                canPlay = track != null,
            )
        }
    }
}
