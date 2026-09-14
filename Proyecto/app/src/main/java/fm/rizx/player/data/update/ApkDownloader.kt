package fm.rizx.player.data.update

import fm.rizx.player.domain.update.AppUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** The downloaded file is not the file GitHub published: wrong checksum or wrong size. */
class ApkVerificationException(message: String) : IOException(message)

/**
 * Fetches a release APK into the app's private `updates/` directory and proves it is the published
 * file before anyone is offered to install it.
 *
 * Streams to a `.part` file with a running SHA-256, so the hash costs no second pass and a torn
 * download never sits under the final name. Verification is the digest GitHub computed on upload;
 * when a release predates digests, the size is the fallback check. Android's installer separately
 * refuses any APK not signed with the app's key, so a swapped file would fail there too — this check
 * exists so a corrupt or truncated download is reported as such instead of as a mystery.
 *
 * One update at a time lives here: older files are removed before a new one is written.
 */
class ApkDownloader(
    private val client: OkHttpClient,
    private val dir: File,
) {

    /** The verified file if this update was already fetched (and is still whole), else null. */
    fun existing(update: AppUpdate): File? {
        val file = File(dir, fileNameFor(update))
        if (!file.isFile) return null
        if (update.apkBytes > 0 && file.length() != update.apkBytes) return null
        return file
    }

    /**
     * Downloads [update]'s APK, reporting `(downloaded, total)` as bytes land (`total` 0 while unknown).
     * Throws [ApkVerificationException] on a mismatch and [IOException] when the network or the disk fails.
     */
    suspend fun download(update: AppUpdate, onProgress: (Long, Long) -> Unit): File = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val target = File(dir, fileNameFor(update))
        dir.listFiles()?.forEach { if (it != target) it.delete() }
        val part = File(dir, target.name + PART_SUFFIX)
        part.delete()

        val request = Request.Builder().url(update.apkUrl).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} fetching the update")
            val body = response.body ?: throw IOException("Empty body fetching the update")
            val total = body.contentLength().takeIf { it > 0 } ?: update.apkBytes
            val digest = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L
            body.byteStream().use { input ->
                part.outputStream().buffered().use { out ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                }
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            val expected = update.sha256
            if (expected != null && !hex.equals(expected, ignoreCase = true)) {
                part.delete()
                throw ApkVerificationException("SHA-256 mismatch")
            }
            if (expected == null && update.apkBytes > 0 && part.length() != update.apkBytes) {
                part.delete()
                throw ApkVerificationException("Size mismatch")
            }
        }
        if (target.exists() && !target.delete()) throw IOException("Could not replace the previous update file")
        if (!part.renameTo(target)) throw IOException("Could not finish writing the update file")
        target
    }

    /** The attached name, kept when it is a plain file name; a fallback built from the version otherwise. */
    private fun fileNameFor(update: AppUpdate): String {
        val name = update.apkName.substringAfterLast('/').substringAfterLast('\\')
        val safe = name.takeIf { it.isNotBlank() && it.endsWith(".apk", ignoreCase = true) && !it.contains("..") }
        return safe ?: "Rizx-${update.versionName}.apk"
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
        const val PART_SUFFIX = ".part"
    }
}
