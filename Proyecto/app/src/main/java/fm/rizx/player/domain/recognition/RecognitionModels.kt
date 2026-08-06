package fm.rizx.player.domain.recognition

import fm.rizx.player.domain.model.Track

/**
 * A captured buffer on its way to being fingerprinted.
 *
 * Deliberately **not** a `data class`: structural equality over a megabyte of PCM is never what a
 * caller wants, and a generated `hashCode` over it is a silent performance trap.
 */
class RecognitionAudio(
    val pcm16LittleEndian: ByteArray,
    val sampleRateHz: Int,
    val channelCount: Int,
    val durationMs: Long,
)

/**
 * What the recognition service said it heard.
 *
 * Everything but [title] and [artist] is optional — the service omits fields freely and reorders its
 * sections, so a parser that requires them fails on ordinary responses.
 *
 * **Only two of the identifiers a response carries are exact**, which is measured rather than assumed:
 * against a live response, the service's Spotify, YouTube Music and Deezer links are all *search*
 * deeplinks built from the title and artist (`spotify:search:Get%20Lucky%20Daft%20Punk`), and the
 * video section is frequently absent altogether. Resolving through those would be a text search
 * wearing an identifier's clothes, so they are not modelled at all.
 *
 * What is left is genuinely exact:
 * - [isrc] — the recording's identity, and what the resolver tries first.
 * - [appleTrackId] — Apple's `adamid`, which this app can already look up through iTunes.
 */
data class RecognitionMatch(
    val provider: String,
    val providerTrackId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val isrc: String? = null,
    val artworkUrl: String? = null,
    val artworkHqUrl: String? = null,
    val genre: String? = null,
    val releaseDate: String? = null,
    val label: String? = null,
    /** The service's own page for this track — for sharing, never for resolving. */
    val externalUrl: String? = null,
    val appleTrackId: String? = null,
)

/** One past recognition. The history records *occasions*, so the same song may appear many times. */
data class RecognitionHistoryItem(
    val id: String,
    val match: RecognitionMatch,
    val resolvedTrack: Track?,
    val recognizedAtIso: String,
)

/** What a single [RecognitionProvider] call concluded. */
sealed interface RecognitionOutcome {
    data class Matched(val match: RecognitionMatch) : RecognitionOutcome

    /** The service answered, and does not know this audio. Not an error. */
    data object NoMatch : RecognitionOutcome

    data class Failed(val error: RecognitionError) : RecognitionOutcome
}

/**
 * Where a recognition attempt stands. One machine drives this; the UI renders it and nothing else.
 */
sealed interface RecognitionState {
    /**
     * Nothing in flight. Also where cancelling lands: a person who pressed cancel is not looking at an
     * error, they are looking at a button they can press again.
     *
     * There is deliberately no `RequestingPermission` member. The microphone permission is asked for
     * by the screen, through the platform's own dialog, before this machine is ever started — so a
     * state for it would only ever describe what the system UI is already showing.
     */
    data object Idle : RecognitionState

    /** @param amplitude 0..1, for the meter only — the flow never depends on it. */
    data class Listening(val elapsedMs: Long, val amplitude: Float) : RecognitionState

    data object Processing : RecognitionState

    /** [resolvedTrack] is null when nothing in the catalogue matched confidently enough to play. */
    data class Matched(val match: RecognitionMatch, val resolvedTrack: Track?) : RecognitionState

    data object NoMatch : RecognitionState

    /**
     * Carries a [category] rather than a message. A message here could neither be localized into the
     * four languages this app ships nor be trusted to stay free of provider internals, so the screen
     * maps the category to a string resource instead.
     */
    data class Failed(val category: RecognitionError, val retryable: Boolean) : RecognitionState
}

/**
 * Stable failure categories. Technical detail stays in the logs; this is what the rest of the app is
 * allowed to branch on and what the screen turns into a sentence.
 */
enum class RecognitionError {
    PERMISSION,
    MICROPHONE_UNAVAILABLE,
    RECORDING_FAILED,
    RESAMPLING_FAILED,
    SIGNATURE_FAILED,
    NETWORK,
    RATE_LIMITED,
    SERVICE_UNAVAILABLE,
    INVALID_RESPONSE,
    UNKNOWN,
    // No CANCELLED: cancelling returns to Idle. Reporting a person's own decision back to them as a
    // failure would be the app arguing with them.
}
