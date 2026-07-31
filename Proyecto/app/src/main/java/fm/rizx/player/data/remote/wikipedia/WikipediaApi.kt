package fm.rizx.player.data.remote.wikipedia

import retrofit2.http.GET
import retrofit2.http.Url

/**
 * Wikipedia's public read APIs — no key, no account, nothing to sign (the same "keyless means public"
 * rule the catalogue providers follow).
 *
 * Every call takes an **absolute URL** because the host is the language: `es.wikipedia.org`,
 * `en.wikipedia.org`… The same shape as [fm.rizx.player.data.remote.audius.AudiusApi], whose host is
 * also chosen at runtime.
 */
interface WikipediaApi {

    /** `…/w/api.php?action=query&list=search&…` — candidate page titles for a name. */
    @GET
    suspend fun search(@Url url: String): WikipediaSearchDto

    /** `…/api/rest_v1/page/summary/<title>` — the lead paragraph, plus what kind of page it is. */
    @GET
    suspend fun summary(@Url url: String): WikipediaSummaryDto
}
