package fm.rizx.player.data.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import fm.rizx.player.core.error.AppError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext

/**
 * Moves a downloaded song's Opus packets from WebM into **Ogg**, the container Opus is normally shipped
 * in — without re-encoding anything.
 *
 * The audio is copied packet for packet, so this is not a conversion and costs no quality: it is the
 * same bitstream in a different wrapper. Two things are gained. The file gets the `.opus` extension the
 * rest of the world recognises, and — the reason this exists — it lands in a container whose comment
 * header can actually be written, so [OggOpusTagger] can give it the cover, artist and album that every
 * other download format already carried. WebM tagging was never an option: jaudiotagger cannot write it,
 * and Matroska's tag element would have meant rewriting the segment in place.
 *
 * `open` and injected for the same reason as [Mp3Transcoder]: `MediaExtractor`/`MediaMuxer` are Android
 * framework classes that no JVM unit test can run, so the untestable half is kept thin and dull while
 * the byte-level work next door is pure Kotlin and fully covered.
 */
open class OpusRemuxer(private val io: CoroutineDispatcher = Dispatchers.IO) {

    /**
     * Writes [source]'s Opus track into [target] as an Ogg Opus file. Throws when the source carries no
     * Opus track or the framework refuses the remux — the caller keeps the original file in that case.
     *
     * `WrongConstant` is suppressed for the packet loop: feeding `MediaExtractor.sampleFlags` into
     * `BufferInfo`/`writeSampleData` is the canonical remux pattern (the sync-frame bit is the same),
     * and it is exactly what `MediaMuxer`'s own documentation shows.
     */
    @android.annotation.SuppressLint("WrongConstant")
    open suspend fun remux(source: File, target: File): Unit = withContext(io) {
        if (android.os.Build.VERSION.SDK_INT < 29) {
            // MUXER_OUTPUT_OGG exists only on API 29+. The pickers never offer Opus there
            // (availableDownloadFormats), so this can only fire for a preference restored from a newer
            // device's backup — and the caller's catch keeps the original WebM, losing nothing.
            throw AppError.ProviderFailure("OpusRemuxer", "Ogg muxing needs Android 10 (API 29)")
        }
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            extractor.setDataSource(source.absolutePath)
            val index = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_OPUS
            } ?: throw AppError.ProviderFailure("OpusRemuxer", "no Opus track in ${source.name}")
            extractor.selectTrack(index)

            muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)
            val track = muxer.addTrack(extractor.getTrackFormat(index))
            muxer.start()
            started = true

            val buffer = ByteBuffer.allocate(MAX_PACKET_BYTES)
            val info = MediaCodec.BufferInfo()
            var packets = 0
            while (true) {
                coroutineContext.ensureActive()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.set(0, size, extractor.sampleTime, extractor.sampleFlags)
                muxer.writeSampleData(track, buffer, info)
                extractor.advance()
                packets++
            }
            if (packets == 0) throw AppError.ProviderFailure("OpusRemuxer", "no audio in ${source.name}")
            muxer.stop()
            started = false
        } finally {
            // stop() throws if the muxer never started or was already stopped; either way it must be released.
            if (started) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }

    private companion object {
        /** One Opus packet is at most a few kilobytes; 256 KiB is headroom, not a budget. */
        const val MAX_PACKET_BYTES = 256 * 1024
    }
}
