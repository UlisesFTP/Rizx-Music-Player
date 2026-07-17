package fm.rizx.player.domain.model

import kotlinx.serialization.Serializable

/** Transport of a resolved [Stream]. (Hi-fi 'mse' is intentionally out of scope for the MVP.) */
@Serializable
enum class StreamProtocol { FILE, HTTP, HTTPS, HLS }

/**
 * A concrete, playable stream. The [url] is **ephemeral** — it may expire and must never be
 * persisted as durable truth (AGENTS.md). [durationMs] is milliseconds.
 */
@Serializable
data class Stream(
    val url: String,
    val protocol: StreamProtocol,
    val mimeType: String? = null,
    val bitrateKbps: Int? = null,
    val codec: String? = null,
    val container: String? = null,
    val qualityLabel: String? = null,
    val durationMs: Long? = null,
    val contentLengthBytes: Long? = null,
    val source: ProviderRef,
)

/**
 * One provider's offering for a [Track] (phase 1 of two-phase resolution). The concrete [stream]
 * is populated just-in-time during phase 2; [failed] flags a dead candidate; [lastResolvedAtIso]
 * gates expiry / re-resolution. See NUCLEAR_UPSTREAM_STUDY.md §2 / §5.
 */
@Serializable
data class StreamCandidate(
    val id: String,
    val title: String,
    val durationMs: Long? = null,
    val thumbnail: String? = null,
    val stream: Stream? = null,
    val lastResolvedAtIso: String? = null,
    val failed: Boolean = false,
    val source: ProviderRef,
)
