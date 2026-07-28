package fm.rizx.player.data.repository

import fm.rizx.player.data.local.store.LyricsStore
import fm.rizx.player.domain.model.Lyrics
import fm.rizx.player.domain.model.LyricsCandidate
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.model.TrackLyrics
import fm.rizx.player.domain.provider.LyricsProvider
import fm.rizx.player.domain.provider.ProviderKind
import fm.rizx.player.domain.provider.ProviderRegistry
import fm.rizx.player.domain.repository.LyricsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Resolves lyrics from most-specific to most-general:
 *
 * 1. **The user's pinned pick** for this track — it outranks anything matching would produce.
 * 2. **The disk cache**, which also makes downloaded songs readable offline.
 * 3. **Every provider at once**, active first for tie-breaks (see [fetch]).
 *
 * Step 3 used to walk the providers one at a time. That made the screen hostage to the slowest source in
 * front of the one that actually had the song: a provider that hung burned its full socket timeout before
 * the next was even tried, five of them in a row. Now they race, each under its own [providerTimeoutMs],
 * and the first word-timed hit ends the race immediately — so the good case is *faster* than one
 * sequential request, and the bad case is bounded instead of additive.
 *
 * The fallback still matters for the same reason as before: these sources are community-scale and each
 * misses different songs, so a track absent from one may exist word-timed on the next. A provider that
 * fails, times out, or simply doesn't have the song is skipped — the error surfaces **only if every
 * provider failed**, so one broken source can never turn a genuine "no lyrics" into a false error.
 *
 * Steps 1 and 2 are one lookup: a pin *is* a cache entry that is flagged and never evicted.
 */
class LyricsRepositoryImpl(
    private val registry: ProviderRegistry,
    /** Null in tests that don't exercise caching; then every lookup goes to the providers. */
    private val store: LyricsStore? = null,
    /**
     * How long any single provider gets. Well under OkHttp's 20 s read timeout on purpose: a lyrics
     * endpoint that hasn't answered in this long is not going to, and the others already have.
     */
    private val providerTimeoutMs: Long = PROVIDER_TIMEOUT_MS,
) : LyricsRepository {

    override suspend fun lyricsFor(track: Track): TrackLyrics? {
        val key = track.source.identityKey
        store?.get(key)?.let { return TrackLyrics(it.lyrics, it.offsetMs, it.pinned) }

        val result = fetch(track)
        val lyrics = result.lyrics ?: return null
        // Don't cache a result the preferred provider never got to weigh in on. A transient LRCLIB outage
        // otherwise pins lyrics.ovh's prose to this track forever, and the song would never gain the timed
        // lyrics it actually has. Observed happening on the very first device run.
        if (!result.degraded) store?.put(key, lyrics)
        return TrackLyrics(lyrics)
    }

    /**
     * Searches every provider at once and keeps the highest-priority non-empty answer. Concurrent rather
     * than sequential for the same reason as [fetch] — and bounded, so one dead source costs
     * [providerTimeoutMs] instead of a full socket timeout in front of everyone else.
     */
    override suspend fun search(query: String): List<LyricsCandidate> = coroutineScope {
        chain()
            .map { provider ->
                async {
                    try {
                        withTimeoutOrNull(providerTimeoutMs) { provider.searchLyrics(query) }.orEmpty()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        emptyList() // a broken provider must not abort the search
                    }
                }
            }
            .awaitAll()
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
    }

    override suspend fun pin(track: Track, candidate: LyricsCandidate) {
        store?.put(track.source.identityKey, candidate.lyrics, pinned = true)
    }

    override suspend fun setOffset(track: Track, offsetMs: Long) {
        store?.setOffset(track.source.identityKey, offsetMs)
    }

    override suspend fun clearOverride(track: Track) {
        store?.remove(track.source.identityKey)
    }

    // ---- Internals ----

    /**
     * What the chain produced, and whether it had to route around a failure to get there.
     *
     * [degraded] is what keeps a temporary outage from becoming permanent: the answer is usable now, but
     * not good enough to write down.
     */
    private data class Fetched(val lyrics: Lyrics?, val degraded: Boolean)

    /** One provider's answer. [TimedOut] and [Failed] both mean "skip it", but only the latter is an error. */
    private sealed interface Outcome {
        /** The provider answered. `lyrics == null` = it simply doesn't have this song. */
        class Ok(val lyrics: Lyrics?) : Outcome
        class Failed(val error: Exception) : Outcome
        data object TimedOut : Outcome
    }

    /**
     * Races the whole chain and returns the **best** result: word timings beat line timings, which beat
     * prose, with ties going to the provider earliest in the chain (the active one first).
     *
     * The first word-timed hit wins outright and cancels the rest — nothing can outrank it, so there is
     * no reason to keep paying for the others. Otherwise every provider is given [providerTimeoutMs] and
     * the best of what came back is used.
     *
     * The error is rethrown **only if every provider failed** — a timeout or a "doesn't have it" is not
     * a failure, it's a miss, and a miss must read as "no lyrics", not as an error banner.
     */
    private suspend fun fetch(track: Track): Fetched = coroutineScope {
        val providers = chain()
        if (providers.isEmpty()) return@coroutineScope Fetched(null, degraded = false)

        val outcomes = arrayOfNulls<Outcome>(providers.size)
        val karaoke = CompletableDeferred<Lyrics>()
        val attempts = providers.mapIndexed { index, provider ->
            async {
                val outcome = attempt(provider, track)
                outcomes[index] = outcome
                (outcome as? Outcome.Ok)?.lyrics?.takeIf { it.isWordSynced }?.let { karaoke.complete(it) }
                outcome
            }
        }
        val everyone = async { attempts.awaitAll() }

        // Whichever happens first: someone lands word timings, or everyone has had their turn.
        val early = select {
            karaoke.onAwait { it }
            everyone.onAwait { null }
        }
        attempts.forEach { it.cancel() }
        everyone.cancel()

        if (early != null) return@coroutineScope Fetched(early, degraded = false)

        val settled = outcomes.filterNotNull()
        val best = settled.filterIsInstance<Outcome.Ok>()
            .mapNotNull { it.lyrics }
            // `maxByOrNull` keeps the *first* maximum, and the chain is already in priority order.
            .maxByOrNull { it.rank() }
        val failure = settled.filterIsInstance<Outcome.Failed>().lastOrNull()?.error
        val everyoneFailed = settled.isNotEmpty() && settled.all { it is Outcome.Failed }
        if (best == null && everyoneFailed && failure != null) throw failure
        // A degraded answer is usable now but not worth writing down: the source that should have
        // answered didn't, and caching a worse result would make that outage permanent.
        Fetched(best, degraded = settled.any { it !is Outcome.Ok })
    }

    /** One bounded, isolated provider call. Neither a timeout nor a crash escapes. */
    private suspend fun attempt(provider: LyricsProvider, track: Track): Outcome = try {
        withTimeoutOrNull(providerTimeoutMs) { Boxed(provider.getLyrics(track)) }
            ?.let { boxed -> Outcome.Ok(boxed.value?.takeUnless { it.isEmpty }) }
            ?: Outcome.TimedOut
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Outcome.Failed(e)
    }

    /** `withTimeoutOrNull` returns null on timeout, which a nullable result would be indistinguishable from. */
    private class Boxed(val value: Lyrics?)

    /** How useful a result is: word-timed > line-timed > anything else worth showing. */
    private fun Lyrics.rank(): Int = when {
        isWordSynced -> 3
        isSynced -> 2
        else -> 1
    }

    /** The active provider first, then the rest in registration order. */
    private fun chain(): List<LyricsProvider> {
        val all = registry.list(ProviderKind.LYRICS).filterIsInstance<LyricsProvider>()
        val active = registry.activeDescriptor(ProviderKind.LYRICS) as? LyricsProvider ?: return all
        return listOf(active) + all.filterNot { it.id == active.id }
    }

    private companion object {
        const val PROVIDER_TIMEOUT_MS = 6_000L
    }
}
