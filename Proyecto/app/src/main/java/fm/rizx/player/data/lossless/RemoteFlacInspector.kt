package fm.rizx.player.data.lossless

import fm.rizx.player.domain.lossless.FlacInspector
import fm.rizx.player.domain.lossless.FlacStreamInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Reads a remote file's FLAC header over a single ranged request.
 *
 * **64 KiB decides a 27 MB question.** The files in the reference index run 25-27 MB each; downloading
 * one to find out it is the wrong song, or not a FLAC at all, would cost more than the compressed stream
 * it was meant to replace. The header is at the front, so one `Range` request answers three things at
 * once: is this really a FLAC, what are its actual PCM parameters, and — the one the matcher needs —
 * how long is it.
 *
 * A `200` is accepted as well as a `206`, because a server that ignores `Range` is common and not a
 * fault; the read is capped either way, and the connection is dropped as soon as the head is buffered.
 *
 * Redirects are followed **by hand**. OkHttp's automatic ones would skip [LosslessUrlGuard], and a public
 * host is perfectly able to answer a `302` pointing at `127.0.0.1`.
 */
class RemoteFlacInspector(
    client: OkHttpClient,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Which URLs may be fetched. Only tests pass anything but [LosslessUrlGuard.Strict]. */
    private val guard: LosslessUrlGuard = LosslessUrlGuard.Strict,
) : FlacInspector {

    /**
     * A private client derived from the shared one — same connection pool and cache, three differences
     * that must not leak into the rest of the app: redirects are ours to check, the timeouts are short
     * because this sits in front of playback, and the DNS hook rejects a name that resolves somewhere
     * private (the hole a string check structurally cannot close).
     */
    private val http: OkHttpClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .dns(GuardedDns(client.dns, guard))
        .build()

    override suspend fun inspect(url: String): FlacStreamInfo? = withContext(io) {
        try {
            fetchHead(url)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A dead link, a TLS failure, a timeout: all of them mean "no verified FLAC", which is a
            // normal answer here. Decoration for the ear, not an error the user is shown.
            null
        }
    }

    private fun fetchHead(startUrl: String): FlacStreamInfo? {
        var current = startUrl
        repeat(MAX_REDIRECTS + 1) {
            if (!guard.isAllowed(current)) return null
            val request = Request.Builder()
                .url(current)
                .header("Range", "bytes=0-${MAX_HEADER_BYTES - 1}")
                .header("Accept", "audio/flac, application/octet-stream")
                .build()

            http.newCall(request).execute().use { response ->
                val redirect = response.redirectTarget()
                if (redirect != null) {
                    current = redirect
                    return@use
                }
                if (response.code != 200 && response.code != 206) return null
                return readInfo(response)
            }
        }
        return null // ran out of redirect budget
    }

    private fun readInfo(response: Response): FlacStreamInfo? {
        val source = response.body?.source() ?: return null
        // Buffer at most the cap. A server that ignores Range and starts streaming 27 MB stops here.
        source.request(MAX_HEADER_BYTES.toLong())
        val available = minOf(source.buffer.size, MAX_HEADER_BYTES.toLong())
        val head = source.buffer.readByteArray(available)

        val info = FlacStreamInfoParser.parse(head) ?: return null
        val length = response.totalLength()
        return info.copy(
            contentLength = length,
            effectiveBitrateKbps = effectiveBitrateKbps(length, info.durationMs),
        )
    }

    /** Where this response says to go next, if it is a redirect we are willing to consider. */
    private fun Response.redirectTarget(): String? {
        if (code !in REDIRECT_CODES) return null
        val location = header("Location")?.takeIf { it.isNotBlank() } ?: return null
        // Resolved against the current URL so a relative Location works, then re-checked by the caller.
        return request.url.resolve(location)?.toString()
    }

    /**
     * The **whole file's** size.
     *
     * On a `206` this has to come from `Content-Range`, whose trailing `/27110494` is the total —
     * `Content-Length` there is the size of the slice we asked for (65536) and using it would report
     * every song as a couple of hundred kbps.
     */
    private fun Response.totalLength(): Long? {
        header("Content-Range")
            ?.substringAfter('/', "")
            ?.takeIf { it.isNotBlank() && it != "*" }
            ?.toLongOrNull()
            ?.let { return it }
        if (code == 200) return header("Content-Length")?.toLongOrNull()
        return null
    }

    /**
     * Bytes over seconds, never a constant.
     *
     * The 1411 kbps everyone quotes for "CD quality" is uncompressed 16/44.1 PCM; a FLAC of the same
     * audio is typically 55-75 % of that, and printing 1411 next to a real file would be inventing a
     * number in the one place this feature promises not to.
     */
    private fun effectiveBitrateKbps(contentLength: Long?, durationMs: Long): Int? {
        if (contentLength == null || contentLength <= 0L || durationMs <= 0L) return null
        return (contentLength * 8.0 / (durationMs / 1000.0) / 1000.0).roundToInt().takeIf { it > 0 }
    }

    /** Resolves names normally, then refuses the ones that landed somewhere private. */
    private class GuardedDns(private val delegate: Dns, private val guard: LosslessUrlGuard) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val resolved = delegate.lookup(hostname)
            if (resolved.any { guard.isPrivateAddress(it) }) {
                throw UnknownHostException("blocked private address for $hostname")
            }
            return resolved
        }
    }

    private companion object {
        /**
         * How much of the file to read. STREAMINFO is 34 bytes at the front, but a file may carry a
         * large picture or padding block ahead of… well, it may not — STREAMINFO is required to be
         * first. 64 KiB is the document's figure and leaves room for a tolerant walk.
         */
        const val MAX_HEADER_BYTES = 64 * 1024

        const val MAX_REDIRECTS = 3
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        // Short on purpose: this runs before the first note plays, and a slow index must cost a
        // fallback to the normal stream rather than a silent player.
        const val CONNECT_TIMEOUT_SECONDS = 4L
        const val READ_TIMEOUT_SECONDS = 4L
        const val CALL_TIMEOUT_SECONDS = 6L
    }
}
