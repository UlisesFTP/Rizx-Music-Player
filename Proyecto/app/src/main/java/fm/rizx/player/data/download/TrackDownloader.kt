package fm.rizx.player.data.download

import fm.rizx.player.core.error.AppError
import fm.rizx.player.domain.model.Stream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/** What landed on disk. [container] is the real format, which may differ from what the stream claimed. */
data class DownloadedFile(val file: File, val sizeBytes: Long, val container: String, val mimeType: String?)

/**
 * Fetches a resolved [Stream] to a file, bit-for-bit — no transcoding here (conversion, when a format
 * asks for one, is a separate step over the finished file). YouTube gives M4A/AAC or WebM/Opus; Audius
 * gives MP3.
 *
 * **Segmented when the server allows it.** The very first request asks for `Range: bytes=0-<chunk>`:
 * a 206 answer means ranges work *and* already carries the first chunk, so probing costs no extra round
 * trip; a 200 means the server ignored the header and is sending the whole body, which is then consumed
 * as the classic single stream. With ranges, the remainder is fetched as [CHUNK_BYTES] pieces by up to
 * [maxWorkers] concurrent workers writing into one pre-sized file through positioned [FileChannel]
 * writes (documented thread-safe). Two reasons this is faster, both measured elsewhere in this repo:
 * several connections beat one, and googlevideo specifically throttles long single reads — the same
 * behaviour yt-dlp's `--http-chunk-size` exists to dodge, and the reason the canvas had to avoid
 * video-only itags. Each chunk retries its *remaining* range a couple of times before the download as a
 * whole is failed.
 *
 * The client here is the download-dedicated one (no HTTP cache — a song's body would evict the whole
 * catalogue cache — and no offline-fallback interceptor, whose synthetic 504 would mask the real error).
 * `followRedirects` stays on (Audius 302s to an ephemeral CDN file) and `readTimeout` is per socket
 * read, so a multi-megabyte body cannot trip it.
 */
class TrackDownloader(
    private val client: OkHttpClient,
    private val dir: File,
    /** Injectable so tests drive the copy deterministically instead of hopping to a real IO thread. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /**
     * Concurrent range workers for one file. A lambda so the caller can shrink it live — on a bad
     * signal, three parallel readers would only fight the stream the user is listening to.
     */
    private val maxWorkers: () -> Int = { DEFAULT_WORKERS },
) {

    /**
     * Downloads [stream] for [identityKey] and returns the finished file.
     *
     * Writes to `<name>.part`, validates, then renames — and the caller only indexes it afterwards. That
     * order is load-bearing: a crash mid-write leaves an orphaned `.part` (harmless, swept at startup),
     * whereas indexing first would leave an entry pointing at a truncated file, which the resolver would
     * then happily hand to ExoPlayer forever.
     *
     * @param onProgress percent 0..100, only when the total size is known.
     */
    suspend fun download(
        identityKey: String,
        stream: Stream,
        expectedSha256: String? = null,
        onProgress: (Int) -> Unit = {},
    ): DownloadedFile = withContext(io) {
        dir.mkdirs()
        val probe = Request.Builder()
            .url(stream.url)
            .header("Range", "bytes=0-${CHUNK_BYTES - 1}")
            .build()
        client.newCall(probe).execute().use { response ->
            if (!response.isSuccessful) {
                throw AppError.Network("download failed (HTTP ${response.code})")
            }
            val contentType = response.header("Content-Type")
            // A provider error page served with HTTP 200 would otherwise be saved as "the song".
            val bare = bareMime(contentType)
            if (bare != null && !bare.startsWith("audio/") && bare != "application/octet-stream") {
                throw AppError.Network("expected audio, got $bare")
            }
            val extension = extensionFor(stream.container, stream.mimeType, contentType)
            val target = File(dir, downloadFileName(identityKey, extension))
            val part = File(dir, "${target.name}.part")

            val totalFromRange = if (response.code == 206) totalFromContentRange(response) else null
            var declared = totalFromRange
                ?: response.header("Content-Length")?.toLongOrNull()
                ?: stream.contentLengthBytes?.takeIf { it > 0 }
            val written = try {
                when {
                    totalFromRange != null && totalFromRange > CHUNK_BYTES ->
                        segmented(stream.url, response, part, totalFromRange, onProgress)

                    // A 206 whose total is unstated (`bytes 0-x/*`): the body in flight is only the
                    // chunk we asked for, and its Content-Length *equals the chunk size* — so saving it
                    // would pass the length check below while truncating the song at 2 MiB. The only
                    // honest move is a fresh, un-ranged request consumed classically.
                    response.code == 206 && totalFromRange == null ->
                        client.newCall(Request.Builder().url(stream.url).build()).execute().use { full ->
                            if (!full.isSuccessful) throw AppError.Network("download failed (HTTP ${full.code})")
                            declared = full.header("Content-Length")?.toLongOrNull()
                                ?: stream.contentLengthBytes?.takeIf { it > 0 }
                            classic(full, part, declared, onProgress)
                        }

                    // A 200 (ranges ignored, whole body already in flight) or a 206 that fits in one
                    // chunk (this response IS the file).
                    else -> classic(response, part, declared, onProgress)
                }
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

            finalize(part, target, written, stream, bare, extension)
        }
    }

    /** Validate-and-rename shared by the network path and [adopt]. */
    private fun finalize(
        part: File,
        target: File,
        written: Long,
        stream: Stream,
        bareResponseMime: String?,
        extension: String,
    ): DownloadedFile {
        target.delete() // a re-download whose container changed must not leave the old file orphaned
        if (!part.renameTo(target)) {
            part.delete()
            throw AppError.Network("could not finalise ${target.name}")
        }
        return DownloadedFile(
            file = target,
            sizeBytes = written,
            container = extension,
            mimeType = bareMime(stream.mimeType) ?: mimeForExtension(extension) ?: bareResponseMime,
        )
    }

    /**
     * Adopts bytes that already exist locally (the streaming byte-cache, a transcoder's output) as a
     * finished download: same naming, same "never index a partial file" discipline, no network at all.
     * [write] fills the `.part`; adoption fails cleanly if it throws or produces an empty file.
     */
    suspend fun adopt(
        identityKey: String,
        container: String,
        mimeType: String?,
        write: suspend (File) -> Unit,
    ): DownloadedFile = withContext(io) {
        dir.mkdirs()
        val target = File(dir, downloadFileName(identityKey, container))
        val part = File(dir, "${target.name}.part")
        try {
            write(part)
        } catch (e: Throwable) {
            part.delete()
            throw e
        }
        val written = part.length()
        if (written <= 0L) {
            part.delete()
            throw AppError.Network("empty file")
        }
        target.delete()
        if (!part.renameTo(target)) {
            part.delete()
            throw AppError.Network("could not finalise ${target.name}")
        }
        DownloadedFile(target, written, container, mimeType ?: mimeForExtension(container))
    }

    // ---- segmented path ----

    /**
     * The probe's 206 body is chunk 0; every later chunk is fetched by up to [maxWorkers] workers, each
     * writing at its own offset. One failing chunk (after its retries) cancels the rest via structured
     * concurrency — the caller deletes the `.part`.
     */
    private suspend fun segmented(
        url: String,
        probeResponse: Response,
        part: File,
        total: Long,
        onProgress: (Int) -> Unit,
    ): Long {
        val written = AtomicLong(0)
        val lastPercent = AtomicInteger(-1)
        val report = {
            val percent = ((written.get() * 100) / total).toInt().coerceIn(0, 100)
            val previous = lastPercent.getAndSet(percent)
            if (percent != previous) onProgress(percent)
        }
        RandomAccessFile(part, "rw").use { raf ->
            raf.setLength(total) // pre-size, so positioned writes land wherever their chunk starts
            val channel = raf.channel
            coroutineScope {
                // Chunk 0 arrives on the probe response this scope already has open — drain it first,
                // so its connection frees up for a worker as soon as possible.
                val firstChunkEnd = minOf(CHUNK_BYTES, total)
                copyIntoChannel(probeResponse, channel, startAt = 0, expect = firstChunkEnd) { n ->
                    written.addAndGet(n.toLong())
                    report()
                }

                val workers = Semaphore(maxWorkers().coerceIn(1, MAX_WORKERS))
                var start = firstChunkEnd
                val jobs = buildList {
                    while (start < total) {
                        val chunkStart = start
                        val chunkEnd = minOf(chunkStart + CHUNK_BYTES, total)
                        add(
                            async {
                                workers.withPermit {
                                    fetchChunk(url, channel, chunkStart, chunkEnd, written, report)
                                }
                            },
                        )
                        start = chunkEnd
                    }
                }
                jobs.forEach { it.await() }
            }
        }
        return written.get()
    }

    /** One chunk, retried from wherever it got to — a flaky link repays the bytes it kept, not the chunk. */
    private suspend fun fetchChunk(
        url: String,
        channel: FileChannel,
        start: Long,
        endExclusive: Long,
        written: AtomicLong,
        report: () -> Unit,
    ) {
        var position = start
        var attempt = 0
        while (position < endExclusive) {
            coroutineContext.ensureActive()
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("Range", "bytes=$position-${endExclusive - 1}")
                    .build()
                client.newCall(request).execute().use { response ->
                    // A server that stops honouring ranges mid-file would silently corrupt the offsets;
                    // better to fail the chunk and let the whole download report honestly.
                    if (response.code != 206) throw IOException("expected 206 for chunk, got ${response.code}")
                    // Position advances per buffer, *inside* the copy. On a connection cut mid-body the
                    // bytes already landed keep their offsets and their count, and the retry resumes at
                    // the true high-water mark — advancing only on a clean return would re-fetch and
                    // double-count them, which the length check would then report as a corrupt download.
                    copyIntoChannel(response, channel, startAt = position, expect = endExclusive - position) { n ->
                        position += n
                        written.addAndGet(n.toLong())
                        report()
                    }
                }
            } catch (e: IOException) {
                if (++attempt > CHUNK_RETRIES) throw AppError.Network("chunk failed after $attempt attempts", e)
            }
        }
    }

    /**
     * Streams [response]'s body into [channel] from [startAt], up to [expect] bytes, calling [onCopied]
     * after each buffer lands. Progress must be observed through the callback, not the return value —
     * an exception mid-body abandons the return, and the whole point of per-buffer accounting is that
     * the bytes copied before the failure stay counted.
     */
    private suspend fun copyIntoChannel(
        response: Response,
        channel: FileChannel,
        startAt: Long,
        expect: Long,
        onCopied: (Int) -> Unit,
    ): Long {
        val body = response.body ?: throw AppError.Network("empty response")
        var at = startAt
        var copied = 0L
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        body.byteStream().use { input ->
            while (copied < expect) {
                coroutineContext.ensureActive() // cancel() must stop the copy, not just orphan it
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), expect - copied).toInt())
                if (read == -1) break
                val byteBuffer = ByteBuffer.wrap(buffer, 0, read)
                while (byteBuffer.hasRemaining()) at += channel.write(byteBuffer, at)
                copied += read
                onCopied(read)
            }
        }
        return copied
    }

    // ---- classic path (servers without ranges; bodies that fit in one chunk) ----

    private suspend fun classic(
        response: Response,
        part: File,
        declared: Long?,
        onProgress: (Int) -> Unit,
    ): Long {
        val body = response.body ?: throw AppError.Network("empty response")
        var written = 0L
        var lastPercent = -1
        body.byteStream().use { input ->
            part.outputStream().use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    coroutineContext.ensureActive()
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

    /** The full length out of `Content-Range: bytes 0-x/total`, or null when the server won't say. */
    private fun totalFromContentRange(response: Response): Long? =
        response.header("Content-Range")
            ?.substringAfterLast('/')
            ?.toLongOrNull()
            ?.takeIf { it > 0 }

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
            val buffer = ByteArray(COPY_BUFFER_BYTES)
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

        /**
         * 2 MiB: small enough that googlevideo's large-request throttle never engages (yt-dlp's own
         * workaround caps at 10 MiB), large enough that a typical 4 MB song is only a couple of requests.
         */
        const val CHUNK_BYTES = 2L * 1024 * 1024

        /** Concurrent range readers per file. With two songs downloading at once, ≤6 sockets total. */
        const val DEFAULT_WORKERS = 3
        const val MAX_WORKERS = 4

        /** Per-chunk retries. Each resumes from the bytes already written, not from the chunk's start. */
        const val CHUNK_RETRIES = 2

        /** 64 KiB. The old 8 KiB meant eight syscall round-trips per 64 KiB of a 25 MB FLAC. */
        const val COPY_BUFFER_BYTES = 64 * 1024
    }
}

/**
 * This stream must never be written to disk (see [isDownloadable]). A plain exception, deliberately not
 * a `CancellationException` — the coroutine machinery would swallow that as normal cancellation and the
 * user would never learn why nothing downloaded.
 */
class NotDownloadableException(message: String) : Exception(message)
