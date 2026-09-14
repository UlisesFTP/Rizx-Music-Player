package fm.rizx.player.data.update

import fm.rizx.player.data.remote.github.GitHubAssetDto
import fm.rizx.player.data.remote.github.GitHubReleaseDto
import fm.rizx.player.data.remote.github.GitHubReleasesApi
import fm.rizx.player.domain.update.AppUpdate
import fm.rizx.player.domain.update.AppUpdateRepository
import fm.rizx.player.domain.update.SemanticVersion
import retrofit2.HttpException
import java.net.HttpURLConnection

/**
 * Published builds = the repository's GitHub Releases (ADR 0032). The contract with whoever cuts a
 * release: tag it `vX.Y.Z` (the app compares that as a semantic version) and attach the signed
 * release APK. Everything else — the title, the notes, the checksum, the size — is read from what
 * GitHub already knows about the release.
 *
 * Asset choice, when a release carries several files: only `.apk` files count; a name that says
 * `releaseTest`, `debug` or `test` is a smoke-test build signed with a debug key and would fail to
 * install over the real one, so it is never offered; among what is left the one that says `release`
 * wins, else the first.
 */
class GitHubAppUpdateRepository(
    private val api: GitHubReleasesApi,
    private val owner: String,
    private val repo: String,
) : AppUpdateRepository {

    override suspend fun latest(): AppUpdate? {
        val release = try {
            api.latestRelease(owner, repo)
        } catch (e: HttpException) {
            // No release published yet is a legitimate state of a repository, not a failure.
            if (e.code() == HttpURLConnection.HTTP_NOT_FOUND) return null
            throw e
        }
        return release.toUpdate()
    }

    private fun GitHubReleaseDto.toUpdate(): AppUpdate? {
        if (draft || prerelease) return null
        val version = SemanticVersion.parse(tagName) ?: return null
        val apk = pickApk(assets) ?: return null
        return AppUpdate(
            versionName = version.toString(),
            tag = tagName,
            title = name?.takeIf { it.isNotBlank() } ?: tagName,
            notes = body.orEmpty().trim(),
            publishedAtIso = publishedAt,
            pageUrl = htmlUrl,
            apkUrl = apk.browserDownloadUrl,
            apkName = apk.name,
            apkBytes = apk.size,
            sha256 = apk.digest?.let { parseSha256(it) },
        )
    }

    companion object {
        private val EXCLUDED = Regex("""releasetest|debug|(^|[^a-z])test""", RegexOption.IGNORE_CASE)

        internal fun pickApk(assets: List<GitHubAssetDto>): GitHubAssetDto? {
            val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) && it.browserDownloadUrl.isNotBlank() }
                .filterNot { EXCLUDED.containsMatchIn(it.name) }
            return apks.firstOrNull { it.name.contains("release", ignoreCase = true) } ?: apks.firstOrNull()
        }

        /** `sha256:<hex>` → lower-case hex; anything else (another algorithm, garbage) → null. */
        internal fun parseSha256(digest: String): String? {
            val (algo, hex) = digest.split(':', limit = 2).takeIf { it.size == 2 } ?: return null
            if (!algo.equals("sha256", ignoreCase = true)) return null
            val clean = hex.trim().lowercase()
            return clean.takeIf { it.length == 64 && it.all { ch -> ch in '0'..'9' || ch in 'a'..'f' } }
        }
    }
}
