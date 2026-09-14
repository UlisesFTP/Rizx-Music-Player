package fm.rizx.player.domain.update

import kotlinx.serialization.Serializable

/**
 * A published build of the app that is newer than the one running — what the update flow shows,
 * downloads and hands to the system installer. Built from one GitHub Release: the tag gives the
 * version, the release body the notes, and one attached `.apk` the file (spec 024).
 *
 * Nothing here is a stream URL or a secret: the download URL is a public, stable release link.
 * Serializable so the last lookup can be remembered between launches.
 */
@Serializable
data class AppUpdate(
    /** The version the release announces, as the app compares it (`1.1.0`; the tag's leading `v` is stripped). */
    val versionName: String,
    /** The release tag as published (`v1.1.0`). */
    val tag: String,
    /** The release's title, or the tag when it has none. */
    val title: String,
    /** The release body: what changed, as the owner wrote it (Markdown, shown as text). Empty when absent. */
    val notes: String,
    /** When the release was published, ISO 8601, when GitHub sent it. */
    val publishedAtIso: String?,
    /** The release page — where a reader can see the whole thing in a browser. */
    val pageUrl: String,
    /** The attached APK's public download link. */
    val apkUrl: String,
    /** The APK's file name as attached (`Rizx-1.1.0-release.apk`). */
    val apkName: String,
    /** The APK's size in bytes, as GitHub reports it (0 when unknown). */
    val apkBytes: Long,
    /** The APK's SHA-256 as GitHub computed it on upload (lower-case hex), or null when not offered. */
    val sha256: String?,
)

/**
 * Why an update step failed — a reason the UI turns into a sentence, never the raw exception text
 * (see `Throwable.toSafeMessage`).
 */
enum class AppUpdateFailure {
    /** The release lookup or the download could not reach GitHub. */
    NETWORK,
    /** The downloaded file did not match the published checksum or size. */
    VERIFICATION,
    /** Anything else. */
    UNKNOWN,
}

/** Where the update flow stands. One value for the whole app, published by the coordinator. */
sealed class AppUpdateState {
    /** Nothing checked yet in this process. */
    data object Unknown : AppUpdateState()

    /** A lookup is in flight. */
    data object Checking : AppUpdateState()

    /** The installed build is the newest published one, or the newest one was skipped by the user. */
    data class UpToDate(val installedVersion: String, val skippedVersion: String? = null) : AppUpdateState()

    /** A newer build is published and not skipped. */
    data class Available(val update: AppUpdate) : AppUpdateState()

    /** The APK is being fetched; [downloadedBytes] of [totalBytes] (0 when the size is unknown). */
    data class Downloading(val update: AppUpdate, val downloadedBytes: Long, val totalBytes: Long) : AppUpdateState() {
        /** 0..1, or null while the total is unknown. */
        val fraction: Float? get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null
    }

    /** The APK is on disk and verified; [filePath] is what the installer is handed. */
    data class Ready(val update: AppUpdate, val filePath: String) : AppUpdateState()

    /** A step failed. [update] is kept when the failure happened after one was found, so "retry" has something to retry. */
    data class Failed(val update: AppUpdate?, val reason: AppUpdateFailure) : AppUpdateState()

    /** The update this state is about, if any. */
    val current: AppUpdate?
        get() = when (this) {
            is Available -> update
            is Downloading -> update
            is Ready -> update
            is Failed -> update
            else -> null
        }
}
