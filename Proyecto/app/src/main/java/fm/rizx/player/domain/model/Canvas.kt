package fm.rizx.player.domain.model

/**
 * A short looping video to play behind the artwork — a "canvas".
 *
 * **Never persisted.** [mediaUrl] is a resolved, expiring stream URL, and this project's rule is that
 * those live in memory or not at all. [expiresAtMs] is the moment the URL itself stops working (parsed
 * from the provider's own expiry when it publishes one), which is a different thing from how long the
 * *answer* is worth caching — see `CanvasResolutionCache`.
 */
data class CanvasCandidate(
    /** Which [fm.rizx.player.domain.provider.CanvasProvider] produced this. */
    val providerId: String,
    val mediaUrl: String,
    /** A second URL to try once if [mediaUrl] fails to play. Null when the provider offers only one. */
    val fallbackUrl: String? = null,
    val mimeType: String? = null,
    val aspect: CanvasAspect = CanvasAspect.LANDSCAPE,
    val title: String? = null,
    val artist: String? = null,
    val durationMs: Long? = null,
    /** How well this matched the track, 0-100. See `CanvasTrackMatcher`. */
    val score: Int = 0,
    /** Epoch millis after which [mediaUrl] is dead, when the provider says. Null = unknown. */
    val expiresAtMs: Long? = null,
    /**
     * The frame size the provider advertised, when it knows one. A progressive file has exactly one; an
     * HLS master holds a whole ladder and reports nothing here — the *played* size is what
     * [CanvasDiagnostics] carries, and only Media3 can say what that turned out to be.
     */
    val width: Int? = null,
    val height: Int? = null,
)

/**
 * The shape of the video.
 *
 * A music video is [LANDSCAPE]; Apple's motion artwork ships a [SQUARE] and a [PORTRAIT] cut. The
 * distinction earns its keep twice: it picks the cut that fits the screen, and a [SQUARE] frame from
 * **YouTube** is the tell of an auto-generated cover-art upload that will never move
 * (`CanvasStaticFilter`).
 */
enum class CanvasAspect { SQUARE, PORTRAIT, LANDSCAPE }

/** Which connections a canvas may be fetched over. */
enum class CanvasNetworkPolicy {
    /** Wi-Fi and other unmetered links only. The default: a canvas is optional bytes. */
    UNMETERED_ONLY,

    /** Any connection, subject to data saver and signal strength. */
    ANY,
}

/**
 * How big a video to ask for.
 *
 * This was once derived rather than chosen, on the grounds that YouTube serves one muxed stream for most
 * videos and a picker would change nothing. Apple's motion artwork changed that: it is a single HLS URL
 * holding a ladder from 360² to **2160²**, so the cap set here is what actually decides how the cover
 * looks. The gate still clamps it — see `CanvasGate.quality` — because mobile data and a low-RAM device
 * overrule a preference.
 */
enum class CanvasQuality(val maxHeight: Int) {
    DATA_SAVER(360),
    AUTO(720),
    HIGH(1080),
}

/**
 * Everything that decides whether a canvas may be resolved and shown, assembled from DataStore.
 *
 * Defaults are the cautious ones on purpose: off, Wi-Fi only, not in battery saver, Now Playing only.
 * Turning it on is a choice about data and battery, so it has to be made rather than inherited.
 */
data class CanvasPreferences(
    val enabled: Boolean = false,
    val network: CanvasNetworkPolicy = CanvasNetworkPolicy.UNMETERED_ONLY,
    val allowOnBatterySaver: Boolean = false,
    val quality: CanvasQuality = CanvasQuality.AUTO,
    /** Apple's purpose-made motion album artwork. On by default: it is the one that actually loops. */
    val appleEnabled: Boolean = true,
    /**
     * The music-video fallback. On by default, and separately switchable because it is the source that
     * can be *wrong* — Apple either has this album's loop or it doesn't, whereas YouTube is a search.
     */
    val youtubeEnabled: Boolean = true,
    val showOnNowPlaying: Boolean = true,
    val showOnAlbumScreen: Boolean = false,
)

/**
 * Why there is no canvas right now.
 *
 * Every one of these is a *normal* outcome ending in the static cover, never an error the user is shown.
 * They exist so the diagnostics can say which one it was instead of leaving a blank screen unexplained.
 */
enum class CanvasBlockReason {
    /** The preference is off, or this screen isn't one the user enabled it for. */
    DISABLED,

    /** Data saving is on — Rizx's switch or Android's. Beats [METERED]: it applies on any connection. */
    DATA_SAVER,

    /** The connection costs money and the policy says no. */
    METERED,

    /** The device is in power-save mode and [CanvasPreferences.allowOnBatterySaver] is off. */
    BATTERY_SAVER,

    /** The link is too weak to carry a second stream on top of the audio. */
    WEAK_SIGNAL,

    /** No provider had a video for this track. */
    NO_CANDIDATE,

    /** A provider had one, but it wasn't this recording. */
    REJECTED_BY_MATCHER,

    /** The providers were asked and something went wrong. Decoration failing is not an error. */
    PROVIDER_ERROR,
}

/**
 * What happened on the last resolution, for the diagnostics block in Settings.
 *
 * Deliberately holds **no URL**: a googlevideo link carries a signed token, and a diagnostics panel is
 * the kind of thing people screenshot. [error] is already run through `toSafeMessage()` by the caller.
 *
 * [width]/[height]/[frameRate] are filled in by the *player*, not the provider — they are what Media3
 * actually selected and decoded. That is the only honest place to read them from: an HLS master
 * advertises nine variants and the one that plays depends on the cap, the decoder and the link.
 */
data class CanvasDiagnostics(
    val providerId: String? = null,
    val score: Int? = null,
    val aspect: CanvasAspect? = null,
    val cacheHit: Boolean = false,
    val resolveMs: Long = 0L,
    val network: CanvasNetworkPolicy? = null,
    val blockedBy: CanvasBlockReason? = null,
    val error: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    /** From `Format.frameRate`. Null until a frame has been decoded, and never asserted otherwise. */
    val frameRate: Float? = null,
    /** Milliseconds from `prepare()` to `onRenderedFirstFrame()`. */
    val firstFrameMs: Long? = null,
)

/**
 * A resolution attempt: the candidates if there are any, and always the story of how it went.
 *
 * **A list, best first.** One URL was not enough: a stream that fails to play has to cost one retry
 * rather than the whole feature, and the player is the only part that finds out. Apple contributes its
 * square and portrait cuts, YouTube its best hit and the runner-up.
 *
 * [quality] travels with it because an HLS candidate carries a whole variant ladder inside one URL — the
 * player, not the provider, is what spends the network budget on those, so it has to be told the budget.
 */
data class CanvasResolution(
    val candidates: List<CanvasCandidate> = emptyList(),
    val diagnostics: CanvasDiagnostics = CanvasDiagnostics(),
    val quality: CanvasQuality = CanvasQuality.DATA_SAVER,
) {
    /** The one to try first. */
    val candidate: CanvasCandidate? get() = candidates.firstOrNull()
}
