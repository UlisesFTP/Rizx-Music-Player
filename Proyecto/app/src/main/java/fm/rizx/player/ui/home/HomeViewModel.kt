package fm.rizx.player.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fm.rizx.player.core.error.AppError
import fm.rizx.player.core.error.toSafeMessage
import fm.rizx.player.data.local.store.HomeFeedStore
import fm.rizx.player.domain.model.AppMix
import fm.rizx.player.domain.model.DailyPick
import fm.rizx.player.domain.model.Daypart
import fm.rizx.player.domain.model.FeaturedPlaylist
import fm.rizx.player.domain.model.ForYouSection
import fm.rizx.player.domain.model.HomeFeed
import fm.rizx.player.domain.model.MoodStation
import fm.rizx.player.domain.model.PlayStat
import fm.rizx.player.domain.model.QueueContext
import fm.rizx.player.domain.model.QueueSourceKind
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.playback.PlaybackController
import fm.rizx.player.domain.repository.DashboardRepository
import fm.rizx.player.domain.repository.FavoritesRepository
import fm.rizx.player.domain.repository.ForYouRepository
import fm.rizx.player.domain.repository.PlaylistRepository
import fm.rizx.player.domain.repository.QueueRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import fm.rizx.player.domain.repository.SettingsRepository
import fm.rizx.player.domain.usecase.ContinueListening
import fm.rizx.player.domain.usecase.HomeFeedDeduper
import fm.rizx.player.domain.usecase.MixBuilder
import fm.rizx.player.domain.usecase.TasteProfile
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** UI state for the Home feed. */
sealed interface HomeUiState {
    data object Loading : HomeUiState
    data class Content(
        val feed: HomeFeed,
        /** Personalized rows (Mix / Because-you-like / Artists-for-you); empty on cold start. */
        val forYouSections: List<ForYouSection> = emptyList(),
        /**
         * Personalized rows already titled but not yet filled ([ForYouSection.size] == 0), drawn as
         * skeletons so the block occupies its real height from the start. Only ever set while there
         * is nothing personalized on screen yet — a refresh keeps the rows it already has.
         */
        val forYouPending: List<ForYouSection> = emptyList(),
        /** `null` = never asked → the For-you consent card shows. */
        val regionalConsent: Boolean? = null,
        val countryName: String? = null,
    ) : HomeUiState
    data object Offline : HomeUiState
    data class Error(val message: String) : HomeUiState
}

/**
 * The mosaic wall's data: the mixes Rizx built for itself, and the day's single pick.
 *
 * Kept out of [HomeUiState] because it is derived rather than loaded — [MixBuilder] computes it from the
 * history, the likes and whatever feed is already on screen, so it has no load state of its own and no
 * failure of its own. It also means the wall survives a feed error: a listener with a history still gets
 * their mixes offline.
 */
data class HomeMixes(
    val mixes: List<AppMix> = emptyList(),
    val pick: DailyPick? = null,
)

/**
 * Loads the blended multi-source [HomeFeed] (Deezer + Spotify + Apple charts through the dashboard
 * fan-out + blender) and the personalized For-you sections. A failing provider degrades gracefully
 * upstream; a total network failure with nothing to show surfaces as [HomeUiState.Offline] with retry.
 *
 * **Nothing waits for everything.** A cold Home costs ~70 network round-trips, the slowest of them the
 * YouTube-backed Mix rows, so this screen used to hold a full-page spinner for as long as the *slowest*
 * branch took. Now:
 *
 *  1. [HomeFeedStore] hands back the last Home the user saw and it renders **immediately** — the common
 *     case is no spinner at all (stale-while-revalidate).
 *  2. The charts and the For-you rows are fetched independently and each publishes the moment it lands,
 *     so the charts show while the personalized rows are still loading. Those rows announce themselves
 *     first — titled, empty, straight off local taste — so the Home can reserve their real height
 *     ([HomeUiState.Content.forYouPending]) instead of dropping a screen of them in later.
 *  3. A refresh never falls back to [HomeUiState.Loading]: it happens underneath the content on screen.
 *
 * Once the charts are visible they stay put — [HomeFeedDeduper.dedupe] is asked to prune the *arriving*
 * rows instead (`feedFirst`), so nothing the user is already looking at jumps.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val dashboard: DashboardRepository,
    private val queue: QueueRepository,
    private val playback: PlaybackController,
    private val forYou: ForYouRepository,
    private val cache: HomeFeedStore,
    private val settings: SettingsRepository,
    private val favorites: FavoritesRepository,
    private val playlists: PlaylistRepository,
    recents: fm.rizx.player.domain.repository.RecentlyPlayedRepository,
) : ViewModel() {

    /**
     * The listening log the statistics run on: every play with its counts, not just a list of titles.
     * Deeper than any row shows, because the weighting needs a distribution to weigh — recency decay,
     * "on repeat" and "rediscover" all say nothing across a handful of plays.
     */
    private val history: Flow<List<PlayStat>> = recents.stats(TASTE_ITEMS)

    /**
     * "Continue listening" — the way back into your own music: what you were playing, what you keep
     * coming back to, and something you have not heard in weeks ([ContinueListening]).
     *
     * Room-backed and live, so it needs no network and no load state: it is on screen before any
     * provider has answered, which is what turns the cold-start blank into something usable. Kept
     * out of [HomeUiState] for exactly that reason — it must not wait on the feed, and the feed's
     * failure must not take it away.
     */
    val continueListening: StateFlow<List<Track>> =
        combine(history, favorites.favoriteTracks()) { played, liked ->
            ContinueListening.build(profile(played, liked), today(), CONTINUE_ITEMS)
        }
            // Off the main thread: this re-runs on every play, and reading it means decoding a few
            // hundred stored tracks before the statistics even start.
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _state = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    /** Pure and stateless, like the blender it shares identity keys with — built, not injected. */
    private val deduper = HomeFeedDeduper()

    /** Same reasoning: pure statistics over data this ViewModel already holds. */
    private val mixBuilder = MixBuilder()

    /**
     * The mosaic wall. Recomputed whenever the history, the likes or the feed change — all three cheap,
     * all three already in memory — and never on a timer, so the wall is stable while you read it.
     *
     * [MixBuilder.build] is fed the feed but **not** the personalized rows, so the set of mixes is
     * settled on the first frame: a wall that gained a tile when the slow half landed would push the
     * whole feed down. [MixBuilder.pick] is the exception, and the Home reserves its card's height.
     */
    val mixes: StateFlow<HomeMixes> =
        combine(history, favorites.favoriteTracks(), state) { played, liked, ui ->
            val content = ui as? HomeUiState.Content
            val profile = profile(played, liked)
            val sections = content?.forYouSections.orEmpty()
            HomeMixes(
                mixes = mixBuilder.build(profile, content?.feed ?: HomeFeed(), sections, today()),
                pick = mixBuilder.pick(profile, sections),
            )
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeMixes())

    /**
     * The taste behind both of the above, read at the moment it is needed.
     *
     * The clock lives here rather than in the domain: `TasteProfile` is pure, and "now" is exactly the
     * kind of ambient state that makes a pure thing untestable if it reaches for it itself.
     */
    private fun profile(played: List<PlayStat>, liked: List<Track>) = TasteProfile(
        stats = played,
        liked = liked,
        nowMs = System.currentTimeMillis(),
        daypart = Daypart.ofHour(LocalTime.now().hour),
    )

    /** The day number — what makes a mix differ from yesterday's while staying put all day. */
    private fun today(): Long = LocalDate.now().toEpochDay()

    // Raw halves, kept apart so either can be replaced without waiting for the other.
    private var rawFeed: HomeFeed? = null
    private var rawSections: List<ForYouSection>? = null
    /** The announced-but-unfilled rows; see [HomeUiState.Content.forYouPending]. */
    private var pendingSections: List<ForYouSection> = emptyList()
    private var feedFirst = false
    private var regionalConsent: Boolean? = null
    private var countryName: String? = null

    private var loadJob: Job? = null

    init {
        load()
        // Turning regional recommendations on/off changes the feed, so it is refetched — but the rows
        // already on screen stay until the new ones arrive. `distinctUntilChanged` matters: this flow
        // sits on the DataStore file every setting shares, so without it changing the theme or any
        // toggle would relaunch the whole load.
        viewModelScope.launch {
            forYou.regionalConsent.drop(1).distinctUntilChanged().collect { refresh() }
        }
        // Same reasoning for the feed source: picking a platform must replace what's on screen, and
        // the cache is keyed by the selection so it can't serve the previous platform's charts.
        viewModelScope.launch {
            settings.feedProvider.drop(1).distinctUntilChanged().collect { refresh() }
        }
    }

    /** Cache-first load: shows the last Home instantly, then revalidates if it is stale. */
    fun load() = start(useCache = true)

    /** User-driven retry / consent change: always hits the network, never blanks what's on screen. */
    fun refresh() = start(useCache = false)

    private fun start(useCache: Boolean) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            var selection = ""
            val cached = withContext(Dispatchers.IO) {
                regionalConsent = forYou.regionalConsent.first()
                countryName = forYou.countryName()
                selection = settings.feedProvider.first()
                if (useCache) cache.read(selection) else null
            }

            if (cached != null && !(cached.feed.isEmpty && cached.sections.isEmpty())) {
                rawFeed = cached.feed
                rawSections = cached.sections
                // Already drawn, so it must survive whatever the refresh brings back.
                feedFirst = true
                publish()
                // Fresh enough: the ~70 round-trips are simply not made.
                if (!cache.isStale(cached)) return@launch
            }

            var feedFailure: Throwable? = null
            var sectionsArrived = false
            val feedJob = launch {
                attempt { dashboard.homeFeed() }
                    .onSuccess {
                        // First half in wins the dedup, so whatever is already drawn keeps its items.
                        if (!sectionsArrived) feedFirst = true
                        rawFeed = it
                        publish()
                    }
                    .onFailure { feedFailure = it }
            }
            // Personalized rows are additive — their failure must never sink the charts. Two emissions
            // arrive: the plan (titles, no items) reserves the block's height, then the real rows fill
            // it. A row is "pending" purely by being empty, so a partial fill would work the same way.
            val sectionsJob = launch {
                attempt {
                    forYou.sections().collect { rows ->
                        val ready = rows.filter { it.size > 0 }
                        val pending = rows.filter { it.size == 0 }
                        pendingSections = pending
                        // Nothing left announced ⇒ this is the finished set, empty or not.
                        if (pending.isEmpty() || ready.isNotEmpty()) {
                            sectionsArrived = true
                            rawSections = ready
                        }
                        publish()
                    }
                }.onFailure {
                    // Nothing personalized is coming: drop the reserved space rather than hold it open.
                    pendingSections = emptyList()
                    publish()
                }
            }
            joinAll(feedJob, sectionsJob)

            if (_state.value !is HomeUiState.Content) {
                _state.value = when (val e = feedFailure) {
                    null -> HomeUiState.Error("Nothing to show right now")
                    is AppError.Network -> HomeUiState.Offline
                    else -> HomeUiState.Error(e.toSafeMessage("Couldn't load Home"))
                }
                return@launch
            }
            (_state.value as? HomeUiState.Content)
                ?.takeIf { feedFailure == null }
                ?.let { cache.write(it.feed, it.forYouSections, selection) }
        }
    }

    /**
     * Renders whatever is available right now. Never overwrites real content with an empty state — a
     * half that failed simply leaves the other half on screen.
     */
    private fun publish() {
        val feed = rawFeed
        val sections = rawSections
        if (feed == null && sections == null) return
        val deduped = deduper.dedupe(feed ?: HomeFeed(), sections.orEmpty(), feedFirst = feedFirst)
        if (deduped.feed.isEmpty && deduped.sections.isEmpty()) return
        _state.value = HomeUiState.Content(
            feed = deduped.feed,
            forYouSections = deduped.sections,
            // Skeletons only ever stand in for rows that aren't there: once real ones exist they hold
            // their own place, and drawing the plan under them would be the jump this prevents.
            forYouPending = if (deduped.sections.isEmpty()) pendingSections else emptyList(),
            regionalConsent = regionalConsent,
            countryName = countryName,
        )
    }

    /** [runCatching] would swallow cancellation; a cancelled load must stay cancelled. */
    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    /**
     * A feed song starts a radio: play it now, then the service auto-fills next/prev — following
     * whichever recommendation engine the user picked in Settings (YT Music's autoplay by default,
     * or the metadata provider's artist radio). The choice lives in the controller so every "play one
     * song" entry point behaves the same.
     */
    fun playTrack(track: Track) = playback.playAutoRadio(track)

    /**
     * Play a whole mix **as a context**, so next/previous walk the mix instead of wandering off into a
     * radio the moment the first song ends — a mix the app assembled is a finished list, not a seed.
     *
     * [label] is the localized title the mosaic is already showing, passed in because the domain keeps no
     * resources: this way the queue names the mix exactly as the tile the user tapped did.
     */
    fun playMix(mix: AppMix, label: String) {
        if (mix.tracks.isEmpty()) return
        playback.playContext(mix.tracks, 0, QueueContext(kind = QueueSourceKind.PLAYLIST, label = label))
    }

    /**
     * The speed dial's dice: one random song from the whole listening log — deeper than the grid
     * shows, still only songs this listener actually played — started as an auto-radio so it keeps
     * going. Random per tap on purpose; the dice that always lands the same way isn't one.
     */
    fun playSurprise() {
        viewModelScope.launch {
            val pool = attempt { history.first() }.getOrDefault(emptyList())
            pool.randomOrNull()?.let { playback.playAutoRadio(it.track) }
        }
    }

    /**
     * A mood chip: resolve what the station is playing right now and queue it as a context, labeled
     * [queueLabel] ("Station · Chill Out") — next/previous walk the station's list, they don't wander
     * off into a radio. Resolution can fail quietly (offline chip = nothing happens); it never crashes.
     */
    fun playStation(providerId: String, station: MoodStation, queueLabel: String) {
        viewModelScope.launch {
            val tracks = attempt { dashboard.stationTracks(providerId, station.id, STATION_TRACKS) }
                .getOrDefault(emptyList())
            if (tracks.isEmpty()) return@launch
            playback.playContext(tracks, 0, QueueContext(kind = QueueSourceKind.PLAYLIST, label = queueLabel))
        }
    }

    /**
     * A featured card's PLAY: fetch the playlist's real tracklist and queue it whole. The card's
     * preview is the fallback — four songs beat a button that does nothing when the fetch fails.
     */
    fun playFeatured(featured: FeaturedPlaylist) {
        viewModelScope.launch {
            val full = attempt { playlists.previewPlaylist(featured.playlist.source) }.getOrDefault(emptyList())
            val tracks = full.ifEmpty { featured.preview }
            if (tracks.isEmpty()) return@launch
            playback.playContext(tracks, 0, QueueContext(kind = QueueSourceKind.PLAYLIST, label = featured.playlist.name))
        }
    }

    /** The consent card's buttons; the consent collector in [init] refreshes the feed afterwards. */
    fun setRegionalConsent(consented: Boolean) {
        viewModelScope.launch { forYou.setRegionalConsent(consented) }
    }

    private companion object {
        /**
         * Two full speed-dial pages: 17 songs + the dice = 18 cells = 2×9. The grid holds more than
         * the old carousel because a wall of thumb-sized covers *is* the denser presentation.
         */
        const val CONTINUE_ITEMS = 17

        /** One queue's worth of a mood station — enough to settle in, small enough to land fast. */
        const val STATION_TRACKS = 30

        /**
         * How deep the statistics read. The log keeps three hundred songs precisely so "you haven't
         * heard this since March" can be true; reading forty of them would throw that away on the way in.
         */
        const val TASTE_ITEMS = 200
    }
}
