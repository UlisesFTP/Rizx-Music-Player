package fm.rizx.player.domain.playback

import fm.rizx.player.domain.model.AudioFormatUi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the stream resolver just handed to the player, so the screen can say what is playing.
 *
 * A separate holder rather than a field on `PlaybackState` because the two travel by different routes:
 * `PlaybackState` is rebuilt from the `MediaController`, which knows about positions and metadata and
 * has no idea what codec is behind the URI. The format is known only at the moment of resolution, deep
 * in the playback layer, and this is the narrowest way to carry it out — a singleton both sides already
 * have, with no Android types and no dependency either way.
 *
 * Keyed by queue item so a stale format can never be shown against a new song: the screen compares the
 * id against the one it is currently displaying and shows nothing when they disagree. That matters most
 * during a skip, when the next track's resolve has not finished and the previous answer is still here.
 */
@Singleton
class NowPlayingFormat @Inject constructor() {

    private val _current = MutableStateFlow<Entry?>(null)
    val current: StateFlow<Entry?> = _current.asStateFlow()

    private val _losslessCodecs = MutableStateFlow<Map<String, String>>(emptyMap())

    /**
     * Content identity (`ProviderRef.identityKey`) → codec, for the songs that have actually played
     * losslessly.
     *
     * What a row in a list is allowed to claim. Whether a song *could* be found in a lossless index is not
     * knowable without the lookup and the header read that follow it, so a badge drawn from a guess would
     * be wrong on every song that turns out to fall back — and the fallback is silent by design. This map
     * only ever grows from something that already happened, which makes the badge a record rather than a
     * promise. It also covers the cases an index knows nothing about: a downloaded FLAC, a local file.
     *
     * The codec travels with the key so a row can name what it is playing (`FLAC`) instead of asserting a
     * category — the same choice the readout under the player makes.
     */
    val losslessCodecs: StateFlow<Map<String, String>> = _losslessCodecs.asStateFlow()

    /**
     * [trackKey] is the track's content identity, and passing it is what lets the row in a search result
     * carry the badge too. Null when the caller doesn't know it (or doesn't care) — the readout under the
     * player works either way.
     */
    fun publish(queueItemId: String, format: AudioFormatUi, trackKey: String? = null) {
        _current.value = Entry(queueItemId, format)
        if (trackKey != null) record(trackKey, format)
    }

    fun clear() {
        _current.value = null
    }

    /** Remembers (or withdraws) the verdict for one track. Withdrawal matters: a FLAC that fails to play
     *  falls back to the ordinary stream, and the badge has to fall back with it. */
    private fun record(trackKey: String, format: AudioFormatUi) {
        val codec = format.codec?.uppercase()?.takeIf { format.isLosslessCodec && it.isNotBlank() }
        _losslessCodecs.update { known ->
            when {
                codec == null -> if (trackKey in known) known - trackKey else known
                known[trackKey] == codec -> known
                // Bounded: a long session touches thousands of tracks and this is only here to draw a tag.
                // A LinkedHashMap keeps insertion order, so the oldest verdict is the one dropped.
                known.size >= MAX_KEYS -> known.entries.drop(known.size - MAX_KEYS + 1)
                    .associate { it.key to it.value } + (trackKey to codec)
                else -> known + (trackKey to codec)
            }
        }
    }

    data class Entry(val queueItemId: String, val format: AudioFormatUi)

    private companion object {
        const val MAX_KEYS = 512
    }
}
