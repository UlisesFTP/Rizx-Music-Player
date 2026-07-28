package fm.rizx.player.domain.repository

import fm.rizx.player.domain.model.ForYouSection
import kotlinx.coroutines.flow.Flow

/**
 * Personalized Home sections plus the regional-recommendations consent surface, as one seam so the
 * Home ViewModel has a single new dependency. Sections come from the user's likes/recents; every
 * row is isolated — a failing source drops its row, never the feed.
 */
interface ForYouRepository {

    /** The personalized rows, freshly composed. Empty on cold start (no likes/recents yet). */
    suspend fun sections(): List<ForYouSection>

    /** `null` = never asked (the For-you consent card shows); `true`/`false` = standing choice. */
    val regionalConsent: Flow<Boolean?>
    suspend fun setRegionalConsent(consented: Boolean)

    /** The detected country's display name ("México") for the consent card/Settings — null if unknown. */
    fun countryName(): String?
}
