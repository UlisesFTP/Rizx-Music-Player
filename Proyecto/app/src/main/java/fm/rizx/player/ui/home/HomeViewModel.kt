package fm.rizx.player.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fm.rizx.player.core.error.AppError
import fm.rizx.player.core.error.toSafeMessage
import fm.rizx.player.data.local.store.HomeFeedStore
import fm.rizx.player.domain.model.ForYouSection
import fm.rizx.player.domain.model.HomeFeed
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.playback.PlaybackController
import fm.rizx.player.domain.repository.DashboardRepository
import fm.rizx.player.domain.repository.ForYouRepository
import fm.rizx.player.domain.repository.QueueRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import fm.rizx.player.domain.repository.SettingsRepository
import fm.rizx.player.domain.usecase.HomeFeedDeduper
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
    recents: fm.rizx.player.domain.repository.RecentlyPlayedRepository,
) : ViewModel() {

    /**
     * "Continue listening" — the songs you played last, newest first.
     *
     * Room-backed and live, so it needs no network and no load state: it is on screen before any
     * provider has answered, which is what turns the cold-start blank into something usable. Kept
     * out of [HomeUiState] for exactly that reason — it must not wait on the feed, and the feed's
     * failure must not take it away.
     */
    val continueListening: StateFlow<List<Track>> =
        recents.recent(CONTINUE_ITEMS)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _state = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    /** Pure and stateless, like the blender it shares identity keys with — built, not injected. */
    private val deduper = HomeFeedDeduper()

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

    /** The consent card's buttons; the consent collector in [init] refreshes the feed afterwards. */
    fun setRegionalConsent(consented: Boolean) {
        viewModelScope.launch { forYou.setRegionalConsent(consented) }
    }

    private companion object {
        /** One carousel's worth of history — the row is a way back in, not the whole log. */
        const val CONTINUE_ITEMS = 12
    }
}
