package fm.rizx.player.domain.usecase

import fm.rizx.player.domain.playback.PlaybackController
import fm.rizx.player.domain.recognition.RecognitionRepository
import javax.inject.Inject

/**
 * Starts an ambient recognition, silencing this app first.
 *
 * The pause is the whole reason this use case exists rather than the screen calling the repository
 * directly. A microphone a metre from the speaker Rizx is driving hears **Rizx**, so a recognition
 * started mid-playback reliably identifies the song already playing — a result that looks perfectly
 * successful and is completely useless. Pausing is also the honest behaviour: the user asked the app
 * to listen to the room, not to itself.
 *
 * Playback is not resumed afterwards. The user is about to act on a result — play the match, search
 * for it, try again — and resuming underneath that would fight whatever they choose next.
 *
 * Keeping this here also keeps [PlaybackController] out of the recognition repository, which has no
 * other reason to know that this app plays anything at all.
 */
class RecognizeAmbientSong @Inject constructor(
    private val recognition: RecognitionRepository,
    private val playback: PlaybackController,
) {
    operator fun invoke() {
        if (playback.state.value.isPlaying) playback.pause()
        recognition.start()
    }
}
