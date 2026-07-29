package fm.rizx.player.domain.model

/** Per-item resolution state in the queue. */
enum class QueueItemStatus { IDLE, LOADING, SUCCESS, ERROR }

/**
 * How the queue advances when a track finishes on its own ([QueueRepository.advanceOnTrackEnd]):
 * [OFF] stops at the last item, [ONE] replays the current item, [ALL] loops the whole queue.
 * Manual navigation ([QueueRepository.goToNext]/[goToPrevious]) is always bounded and ignores this.
 */
enum class RepeatMode { OFF, ONE, ALL }

/** Where the current queue was started from — drives contextual next/prev and the radio auto-refill. */
enum class QueueSourceKind { MANUAL, ALBUM, ARTIST, PLAYLIST, LIKED, RECENTS, DOWNLOADS, LOCAL, RADIO }

/**
 * Which engine refills a [QueueSourceKind.RADIO] queue.
 *
 * - [ARTIST] — the active metadata provider's artist radio (Deezer). Follows the *artist*.
 * - [YOUTUBE] — the YouTube Mix seeded from the current track (YT Music's own autoplay). Follows the
 *   *song*, and is the default because it is what a search-originated play already followed.
 * - [APPLEMUSIC] — the artist's own catalogue plus same-genre songs from Apple's public catalogue.
 * - [SOUNDCLOUD] — SoundCloud's own related-tracks for the current track.
 *
 * Every non-[ARTIST] engine falls back to the artist radio when it returns nothing, so "next" can
 * never dead-end. Persisted **by name**, so adding entries here is safe for stored preferences.
 */
enum class RadioMode { ARTIST, YOUTUBE, APPLEMUSIC, SOUNDCLOUD }

/**
 * The origin of the current queue. [label] is a human name for the source (album/playlist/artist name,
 * or "Radio · <artist>"); [radioSeed] is the artist (or track) [ProviderRef] used to fetch more similar
 * tracks while [kind] is [QueueSourceKind.RADIO]. Set by `QueueRepository.setQueue`; a plain
 * add-to-queue leaves it untouched, and `clearQueue` resets it to [QueueSourceKind.MANUAL].
 */
data class QueueContext(
    val kind: QueueSourceKind = QueueSourceKind.MANUAL,
    val label: String = "",
    val radioSeed: ProviderRef? = null,
    val radioMode: RadioMode = RadioMode.ARTIST,
)

/**
 * A single queue entry. [id] is a per-insertion uuid — **distinct from the track's identity**
 * ([Track.source]) — so the same track may appear in the queue multiple times. [addedAtIso] is an
 * ISO-8601 timestamp.
 */
data class QueueItem(
    val id: String,
    val track: Track,
    val status: QueueItemStatus = QueueItemStatus.IDLE,
    val error: String? = null,
    val addedAtIso: String,
)

/**
 * The playback queue: a flat list plus an integer cursor. [current] is `items[currentIndex]`, or
 * `null` when the queue is empty/uninitialized (`currentIndex == -1`). Upstream calls this `Queue`.
 * [repeatMode] governs auto-advance behavior; it lives here so the Queue screen observes a single
 * state object. Queue *operations* (add/remove/reorder/advance) are `QueueRepository` (Phase 5).
 */
data class PlaybackQueue(
    val items: List<QueueItem> = emptyList(),
    val currentIndex: Int = -1,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val context: QueueContext = QueueContext(),
    /**
     * Whether [items] is in shuffled order. A standing preference, not a property of this queue: it
     * survives clearing the queue and applies to whatever is played next.
     */
    val shuffleOn: Boolean = false,
    /**
     * [QueueItem.id]s in their pre-shuffle order, so turning shuffle off restores it. Null when shuffle
     * is off. Ids rather than a second copy of the items — an id is unique per insertion, so it names
     * exactly one entry even when the same track appears twice.
     */
    val unshuffledIds: List<String>? = null,
) {
    val current: QueueItem? get() = items.getOrNull(currentIndex)
}
