package fm.rizx.player.playback

import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer

/**
 * Hands every decoded PCM buffer to a set of listeners on its way into the audio sink, without touching
 * it.
 *
 * **Why this is not a `TeeAudioProcessor` any more.** It was, and for a FLAC it stopped working — silently,
 * and only for a FLAC. The chain is worth writing down because nothing about it is visible from our code:
 *
 * 1. "Prefer lossless" (and "best available") switch on ExoPlayer's float output path.
 * 2. `MediaCodecAudioRenderer` then asks the *decoder* for `ENCODING_PCM_FLOAT`, because the sink reports
 *    it can take float directly.
 * 3. The AOSP FLAC decoder honours that; the AAC and Opus decoders do not, so they keep emitting 16-bit.
 * 4. `DefaultAudioSink.configure` builds its processing pipeline from `toFloatPcmAvailableAudioProcessors`
 *    whenever the input encoding is high-resolution — and that list is a fixed one, so the processors
 *    handed to `setAudioProcessors` are **dropped entirely**.
 *
 * So the waveform froze at its idle baseline and the automatic equalizer quietly measured nothing, for
 * exactly the songs the user turned lossless on to hear. Tapping the sink's input instead of a processor
 * slot sidesteps the whole branch: this sees the buffer before any pipeline is chosen, in every mode.
 *
 * It also keeps the property the automatic equalizer depends on — the tap is upstream of the audio
 * session's `Equalizer` effect, so what is measured is the recording rather than the app's own output.
 *
 * The listeners keep Media3's [TeeAudioProcessor.AudioBufferSink] type. It is just "here is some PCM, and
 * here is what shape it's in", and both listeners already implement it; a parallel interface of our own
 * would have been the same two methods under a different name.
 */
@UnstableApi
class PcmTappingAudioSink(
    sink: AudioSink,
    private val taps: List<TeeAudioProcessor.AudioBufferSink>,
) : ForwardingAudioSink(sink) {

    /**
     * The timestamp of the last buffer handed to the taps.
     *
     * A sink that can't take a whole buffer is called again with the same one, so without this a busy
     * output would feed the same audio in two or three times over. Comparing timestamps is enough: each
     * buffer carries its own, and a repeat is by definition the same buffer.
     */
    private var lastTappedUs = Long.MIN_VALUE

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        // The encoding here is the decoder's, which is the whole point — 16-bit, float, or a raw file's
        // own depth. Told before `super`, so the first buffer of a track is already interpreted correctly.
        for (tap in taps) {
            tap.flush(inputFormat.sampleRate, inputFormat.channelCount, inputFormat.pcmEncoding)
        }
        lastTappedUs = Long.MIN_VALUE
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        if (presentationTimeUs != lastTappedUs) {
            lastTappedUs = presentationTimeUs
            // Read-only, so a listener cannot consume or corrupt audio on its way to the speaker. A
            // visualization must never be able to break playback.
            for (tap in taps) tap.handleBuffer(buffer.asReadOnlyBuffer())
        }
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }
}
