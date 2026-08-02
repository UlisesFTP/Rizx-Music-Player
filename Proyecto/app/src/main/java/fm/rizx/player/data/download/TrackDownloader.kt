package fm.rizx.player.data.download

import fm.rizx.player.core.error.AppError
import fm.rizx.player.domain.model.Stream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** What landed on disk. [container] is the real format, which may differ from what the stream claimed. */
data class DownloadedFile(val file: File, val sizeBytes: Long, val container: String, val mimeType: String?)

/**
 * Fetches a resolved [Stream] to a file, bit-for-bit — no transcoding (the owner's decision: the source
 * is already lossy, so re-encoding to MP3 would only lose quality, and FLAC from a lossy source is a
 * fiction). YouTube gives M4A/AAC, or WebM/Opus when no M4A track exists; Audius gives MP3.
 *
 * Reuses the injected [OkHttpClient] deliberately: `followRedirects` is already on (Audius's URL 302s to
 * an ephemeral CDN file) and `readTimeout` is per socket read rather than per body, so a multi-megabyte
 * download cannot trip it.
 */
class TrackDownloader(
    private val client: OkHttpClient,
    private val dir: File,
    /** Injectable so tests drive the copy deterministically instead of hopping to a real IO thread. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * Downloads [stream] for [identityKey] and returns the finished file.
     *
     * Writes to `<name>.part`, validates, then renames — and the caller only indexes it afterwards. That
     * order is load-bearing: a crash mid-write leaves an orphaned `.part` (harmless, swept at startup),
     * whereas indexing first would leave an entry pointing at a truncated file, which the resolver would
     * then happily hand to ExoPlayer forever.
     *
     * @param onProgress percent 0..100, only when the server declares a content length.
     */
    suspend fun download(
        identityKey: String,
        stream: Stream,
        expectedSha256: String? = null,
        onProgress: (Int) -> Unit = {},
    ): DownloadedFile = withContext(io) {
        dir.mkdirs()
        val request = Request.Builder().url(stream.url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw AppError.Network("download failed (HTTP ${response.code})")
            }
            val contentType = response.header("Content-Type")
            // A provider error page served with HTTP 200 would otherwise be saved as "the song".
            val bare = bareMime(contentType)
            if (bare != null && !bare.startsWith("audio/") && bare != "application/octet-stream") {
                throw AppError.Network("expected audio, got $bare")
            }
            val declared = response.header("Content-Length")?.toLongOrNull()
                ?: stream.contentLengthBytes?.takeIf { it > 0 }
            val extension = extensionFor(stream.container, stream.mimeType, contentType)
            val target = File(dir, downloadFileName(identityKey, extension))
            val part = File(dir, "${target.name}.part")

            val written = try {
                copy(response, part, declared, onProgress)
            } catch (e: Throwable) {
                part.delete()
                throw e
            }

            // A body truncated mid-flight can look like a clean EOF to OkHttp. Without this check the
            // file plays for 40 seconds and then dies — permanently, since the resolver prefers it.
            if (declared != null && written != declared) {
                part.delete()
                throw AppError.Network("incomplete download ($written of $declared bytes)")
            }
            if (written <= 0L) {
                part.delete()
                throw AppError.Network("empty download")
            }

            // A file claiming to be lossless has to still *be* one after the whole body has landed. The
            // header was checked before playback from 64 KiB; this re-checks it on what was actually
            // written, so a truncated-then-padded or swapped body can't be indexed as a FLAC and then
            // preferred offline forever. Only for FLAC: nothing else here makes a claim worth verifying.
            if (extension == "flac" && !startsWithFlacMagic(part)) {
                part.delete()
                throw AppError.Network("downloaded file is not a FLAC")
            }
            // Optional, and absent from every index measured so far — but when a row publishes a digest
            // it is the one check that covers the whole file rather than its first four bytes.
            expectedSha256?.let { expected ->
                val actual = sha256Of(part)
                if (!actual.equals(expected, ignoreCase = true)) {
                    part.delete()
                    throw AppError.Network("checksum mismatch")
                }
            }

            target.delete() // a re-download whose container changed must not leave the old file orphaned
            if (!part.renameTo(target)) {
                part.delete()
                throw AppError.Network("could not finalise ${target.name}")
            }
            DownloadedFile(
                file = target,
                sizeBytes = written,
                container = extension,
                mimeType = bareMime(stream.mimeType) ?: mimeForExtension(extension) ?: bare,
            )
        }
    }

    private suspend fun copy(
        response: okhttp3.Response,
        part: File,
        declared: Long?,
        onProgress: (Int) -> Unit,
    ): Long {
        val body = response.body ?: throw AppError.Network("empty response")
        var written = 0L
        var lastPercent = -1
        body.byteStream().use { input ->
            part.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    coroutineContext.ensureActive() // cancel() must stop the copy, not just orphan it
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    written += read
                    if (declared != null && declared > 0) {
                        val percent = ((written * 100) / declared).toInt().coerceIn(0, 100)
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(percent)
                        }
                    }
                }
                output.flush()
            }
        }
        return written
    }

    /** Whether the finished file still begins with `fLaC`. */
    private fun startsWithFlacMagic(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val head = ByteArray(4)
            input.read(head) == 4 && head.contentEquals(FLAC_MAGIC)
        }
    }.getOrDefault(false)

    /** Streamed rather than read whole: these files run tens of megabytes. */
    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Deletes any `.part` left by a process death mid-download. */
    fun sweepPartials() {
        runCatching { dir.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() } }
    }

    /** The file for [fileName], or null if it is gone. */
    fun fileFor(fileName: String): File? = File(dir, fileName).takeIf { it.isFile && it.length() > 0 }

    /** Deletes every file that no longer has an index entry (orphans from the crash window). */
    fun deleteOrphans(knownFileNames: Set<String>) {
        runCatching {
            dir.listFiles()?.forEach { f -> if (f.isFile && f.name !in knownFileNames) f.delete() }
        }
    }

    fun delete(fileName: String) {
        runCatching { File(dir, fileName).delete() }
    }

    private companion object {
        val FLAC_MAGIC = byteArrayOf(0x66, 0x4C, 0x61, 0x43) // "fLaC"
    }
}

/**
 * This stream must never be written to disk (see [isDownloadable]). A plain exception, deliberately not
 * a `CancellationException` — the coroutine machinery would swallow that as normal cancellation and the
 * user would never learn why nothing downloaded.
 */
class NotDownloadableException(message: String) : Exception(message)
