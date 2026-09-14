package fm.rizx.player.data.remote.github

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Path

/**
 * The slice of GitHub's REST API the update check reads: the newest published release of a public
 * repository. Keyless — 60 requests an hour per address is far more than one phone asks for — and
 * the shared client's User-Agent satisfies GitHub's one requirement.
 *
 * `releases/latest` already excludes drafts and pre-releases, and answers 404 when the repository has
 * no release at all, which the repository treats as "nothing published", not as an error.
 */
interface GitHubReleasesApi {

    @Headers("Accept: application/vnd.github+json", "X-GitHub-Api-Version: 2022-11-28")
    @GET("repos/{owner}/{repo}/releases/latest")
    suspend fun latestRelease(@Path("owner") owner: String, @Path("repo") repo: String): GitHubReleaseDto
}

@Serializable
data class GitHubReleaseDto(
    @SerialName("tag_name") val tagName: String = "",
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("html_url") val htmlUrl: String = "",
    val assets: List<GitHubAssetDto> = emptyList(),
)

@Serializable
data class GitHubAssetDto(
    val name: String = "",
    val size: Long = 0,
    @SerialName("content_type") val contentType: String? = null,
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
    /** `sha256:<hex>`, computed by GitHub when the file was uploaded. Absent on very old assets. */
    val digest: String? = null,
)
