package fm.rizx.player.playback

import android.util.Log
import androidx.media3.common.util.UnstableApi
import fm.rizx.player.BuildConfig
import fm.rizx.player.data.genre.TrackGenreResolver
import fm.rizx.player.data.local.store.AutoEqStore
import fm.rizx.player.data.local.store.StoredAutoEq
import fm.rizx.player.domain.model.AutoEqDecision
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.repository.QueueRepository
import fm.rizx.player.domain.repository.SettingsRepository
import fm.rizx.player.domain.usecase.AutoEqCurves
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The automatic equalizer: **a curve per song**, from the song's genre and from the song itself.
 *
 * What happens when a track starts, in the order it happens:
 *
 *  1. **Cached?** A song played before applies its stored curve immediately — the first play paid for
 *     everything, and none of it changes for that recording.
 *  2. **Genre.** [TrackGenreResolver] answers from the cheapest source that can (a tag, the owning
 *     catalogue, the album, then a verified iTunes lookup). Its curve goes on as soon as it arrives,
 *     typically within a second.
 *  3. **Then it listens.** [TrackSpectrum] measures the first several seconds of the *actual* audio, and
 *     the curve is refined: a song that is already boomy gets less of its genre's bass, a dull one a
 *     little more air ([AutoEqCurves.adapt]). That result is written down, so it is instant next time.
 *
 * Nothing here can delay or interrupt playback. Every step is optional and every failure is silent: no
 * genre means a flat curve, no measurement means the genre curve stands, no equalizer effect on the device
 * means the whole component quietly does nothing.
 *
 * A track change cancels the previous song's work outright (`collectLatest`), so holding "next" does not
 * queue up a lookup per track — only the song you land on is shaped.
 */
@UnstableApi
@Singleton
class AutoEqualizer @Inject constructor(
    private val settings: SettingsRepository,
    private val queue: QueueRepository,
    private val effects: AudioEffects,
    private val spectrum: TrackSpectrum,
    private val genres: TrackGenreResolver,
    private val store: AutoEqStore,
) {

    private var scope: CoroutineScope? = null

    /** Starts watching the setting and the queue. Called by `PlaybackService` once the effect is attached. */
    fun attach() {
        release()
        val started = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = started
        started.launch {
            settings.autoEqualizer.collectLatest { on ->
                if (!on) {
                    effects.endAuto()
                    spectrum.stop()
                    return@collectLatest
                }
                effects.beginAuto()
                try {
                    queue.state
                        .map { it.current?.track }
                        .distinctUntilChanged { old, new -> old?.source?.identityKey == new?.source?.identityKey }
                        .collectLatest { track -> if (track != null) shape(track) }
                } finally {
                    // Reached when the switch goes off (this block is cancelled) or the service is going
                    // away: either way the user's own curve goes back and the listening stops.
                    effects.endAuto()
                    spectrum.stop()
                }
            }
        }
    }

    fun release() {
        scope?.cancel()
        scope = null
    }

    private suspend fun shape(track: Track) {
        val bands = effects.bandRanges()
        val range = effects.levelRangeMillibel()
        if (bands.isEmpty() || range == null) return
        val key = track.source.identityKey
        val cached = store.get(key, bands.size)

        // The measurement window opens *now*. Waiting for the genre lookup first would spend the opening
        // of the song, which is the part most likely to still be playing when the answer is needed.
        val needsMeasurement = cached == null || !cached.adapted
        if (needsMeasurement) spectrum.reset() else spectrum.stop()

        var genre = cached?.genre
        var label = cached?.label
        if (cached != null) {
            effects.applyAutoCurve(AutoEqCurves.toMillibels(cached.curveDb, range.first, range.last), label)
            log("cached ${track.title} · ${cached.genre} · ${cached.curveDb.dbLine()}${if (cached.adapted) " (adapted)" else ""}")
        }

        if (genre == null) {
            val resolved = genres.resolve(track)
            genre = resolved.genre
            label = resolved.label
            val decision = AutoEqCurves.decide(genre, label, null, bands)
            apply(decision, range)
            store.put(key, decision.stored(bands.size))
            log("genre ${track.title} · ${resolved.genre} (${resolved.label ?: "no label"}) · ${decision.curveDb.dbLine()}")
        }
        if (!needsMeasurement) return

        // Whatever the song turns out to sound like, this waits for it in the background; a skip cancels it.
        val measured = withTimeoutOrNull(MEASURE_TIMEOUT_MS) { spectrum.measurement.first { it != null } } ?: return
        val refined = AutoEqCurves.decide(genre, label, measured, bands)
        apply(refined, range)
        store.put(key, refined.stored(bands.size))
        log("refined ${track.title} · ${refined.genre} · ${refined.curveDb.dbLine()} (measured ${measured.dbLine()})")
    }

    private fun apply(decision: AutoEqDecision, range: IntRange) {
        effects.applyAutoCurve(AutoEqCurves.toMillibels(decision.curveDb, range.first, range.last), decision.label)
    }

    private fun AutoEqDecision.stored(bandCount: Int) = StoredAutoEq(
        genre = genre,
        label = label,
        curveDb = curveDb,
        adapted = adapted,
        bandCount = bandCount,
        // computedAtIso is left to the store, which owns the clock.
    )

    /** Debug-only, and the only window into a feature whose whole job is to be inaudible as a mechanism. */
    private fun log(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    private fun List<Float>.dbLine() = joinToString(" ") { String.format("%+.1f", it) }
    private fun FloatArray.dbLine() = joinToString(" ") { String.format("%+.1f", it) }

    private companion object {
        const val TAG = "RizxAutoEq"

        /**
         * How long to wait for the song's own spectrum before giving up on refining this play. Generous:
         * a paused or buffering track measures nothing, and the genre curve is already on.
         */
        const val MEASURE_TIMEOUT_MS = 45_000L
    }
}
