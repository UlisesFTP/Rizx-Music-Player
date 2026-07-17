package fm.rizx.player.domain.model

import kotlinx.serialization.Serializable
import kotlin.math.abs

/** What an [Artwork] is best used for; drives the target aspect ratio in [pick]. */
@Serializable
enum class ArtworkPurpose { AVATAR, COVER, BACKGROUND, THUMBNAIL }

/** A single image variant. [width]/[height] are pixels when known. */
@Serializable
data class Artwork(
    val url: String,
    val width: Int? = null,
    val height: Int? = null,
    val purpose: ArtworkPurpose? = null,
    val source: ProviderRef? = null,
)

/** Ordered set of [Artwork] variants for one entity. */
@Serializable
data class ArtworkSet(val items: List<Artwork> = emptyList())

/** Recommended target pixel sizes for [pick], by usage. */
object ArtworkTargetPx {
    const val THUMBNAIL = 128
    const val AVATAR = 256
    const val COVER = 512
    const val BACKGROUND = 1080
}

private fun targetAspect(purpose: ArtworkPurpose): Float = when (purpose) {
    ArtworkPurpose.BACKGROUND -> 16f / 9f
    ArtworkPurpose.AVATAR, ArtworkPurpose.COVER, ArtworkPurpose.THUMBNAIL -> 1f
}

/**
 * Selects the best [Artwork] for a [purpose] at a desired [targetPx], porting upstream
 * `pickArtwork` (NUCLEAR_UPSTREAM_STUDY.md §2). Returns `null` only for an empty/missing set.
 *
 * Candidate pool = items with **no** `purpose` (always eligible) **or** items whose `purpose`
 * matches *and* have a non-empty `url`. If the pool is empty, falls back to `items.first()`
 * (even if its url is empty — the url filter applies to scoring, not fallback). Otherwise scores
 * each candidate and returns the highest:
 *
 * ```
 * score = (upscaleFactor > 1.5 ? -1000 : 0) + (-aspectDiff * 50) + (-sizeDiff * 0.1)
 * ```
 */
/** Best cover URL for a card/hero, or null when unavailable (drives real image loading in the UI). */
fun ArtworkSet?.coverUrl(): String? =
    pick(ArtworkPurpose.COVER, ArtworkTargetPx.COVER)?.url?.takeIf { it.isNotBlank() }

fun ArtworkSet?.pick(purpose: ArtworkPurpose, targetPx: Int): Artwork? {
    val all = this?.items ?: return null
    if (all.isEmpty()) return null

    val candidates = all.filter { it.purpose == null || (it.purpose == purpose && it.url.isNotEmpty()) }
    if (candidates.isEmpty()) return all.first()

    val target = targetAspect(purpose)
    return candidates.maxByOrNull { art ->
        val w = art.width ?: 0
        val h = art.height ?: 0
        val size = minOf(w, h)
        val aspect = if (art.width != null && art.height != null && h != 0) w.toFloat() / h else 1f
        val aspectDiff = abs(aspect - target)
        val sizeDiff = abs(size - targetPx).toFloat()
        val upscaleFactor = if (size < targetPx) {
            if (size > 0) targetPx.toFloat() / size else Float.POSITIVE_INFINITY
        } else {
            1f
        }
        (if (upscaleFactor > 1.5f) -1000f else 0f) + (-aspectDiff * 50f) + (-sizeDiff * 0.1f)
    }
}
