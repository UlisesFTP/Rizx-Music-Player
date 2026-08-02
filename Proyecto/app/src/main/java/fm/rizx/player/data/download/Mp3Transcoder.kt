package fm.rizx.player.data.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import fm.rizx.player.core.error.AppError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Re-encodes a downloaded song (M4A/AAC or WebM/Opus) to MP3, for the download format that exists so a
 * file plays in a car stereo from 2009.
 *
 * The split of labour is the testable seam: **decoding** is the Android framework (`MediaExtractor` +
 * `MediaCodec` — the platform already ships every decoder we could need, and gets them right), while
 * **encoding** is the injected [Mp3Encoder], pure JVM, exercised directly by unit tests. This class is
 * the thin untestable middle and deliberately contains nothing clever.
 *
 * Runs on [cpu] ([Dispatchers.Default]): it is compute, not I/O, and a couple of minutes of decoded
 * audio per second of work — slow only in the sense that a background job is allowed to be.
 */
open class Mp3Transcoder(
    private val newEncoder: (sampleRateHz: Int, channels: Int) -> Mp3Encoder = { rate, ch -> Jump3rMp3Encoder(rate, ch) },
    private val cpu: CoroutineDispatcher = Dispatchers.Default,
) {

    /**
     * Decodes [source]'s first audio track and writes an MP3 to [target]. Throws on anything unplayable.
     *
     * `open` because the decode half is Android's MediaCodec, which no JVM test can run — the pipeline
     * tests substitute this whole method and the encoder half is tested against the real engine.
     */
    open suspend fun transcode(source: File, target: File): Unit = withContext(cpu) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(source.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw AppError.ProviderFailure("Mp3Transcoder", "no audio track in ${source.name}")
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))

            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(format, null, null, 0)
                start()
            }
            target.outputStream().buffered().use { out ->
                drain(extractor, codec, out)
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /**
     * The classic synchronous MediaCodec pump. The encoder is created lazily on the **first output
     * buffer**, not from the container's declared format — Opus decodes at 48 kHz regardless of what
     * the WebM header claims, and the output format is the decoder's own word for what it produced.
     */
    private suspend fun drain(extractor: MediaExtractor, codec: MediaCodec, out: java.io.OutputStream) {
        val info = MediaCodec.BufferInfo()
        var encoder: Mp3Encoder? = null
        var pcm = ShortArray(0)
        var inputDone = false
        while (true) {
            coroutineContext.ensureActive()
            if (!inputDone) {
                val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    val buffer = codec.getInputBuffer(inIndex) ?: continue
                    val read = extractor.readSampleData(buffer, 0)
                    if (read < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, read, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            if (outIndex >= 0) {
                val buffer = codec.getOutputBuffer(outIndex)
                if (buffer != null && info.size > 0) {
                    val format = codec.outputFormat
                    val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    val active = encoder ?: newEncoder(
                        format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                        channels,
                    ).also { encoder = it }
                    val shorts = buffer.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
                    val count = shorts.remaining()
                    if (pcm.size < count) pcm = ShortArray(count)
                    shorts.get(pcm, 0, count)
                    active.encode(pcm, count / channels, out)
                }
                codec.releaseOutputBuffer(outIndex, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                // Keep pumping until EOS propagates through the decoder.
                continue
            }
        }
        // A file whose decoder never produced a buffer is not a song; an empty MP3 must not survive.
        (encoder ?: throw AppError.ProviderFailure("Mp3Transcoder", "decoder produced no audio")).finish(out)
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
    }
}
