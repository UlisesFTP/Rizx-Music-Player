package fm.rizx.player.data.recognition

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import fm.rizx.player.domain.recognition.MicrophoneRecorder
import fm.rizx.player.domain.recognition.RecognitionAudio
import fm.rizx.player.domain.recognition.RecognitionError
import fm.rizx.player.domain.recognition.RecordingFailure
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

/**
 * Captures a few seconds of the room through the microphone.
 *
 * **It asks the platform for 16 kHz first**, which is what the fingerprint wants. Every Android device
 * supports that rate for voice capture — it is the one the speech pipeline is built on — so the usual
 * outcome is that the audio HAL resamples with its own filters and [Pcm16Resampler] is never needed:
 * a third of the memory, one less transformation over the peaks the fingerprint reads, and one less
 * thing that can be subtly wrong. The higher rates are a fallback for devices that refuse, not the
 * plan.
 *
 * Nothing is written to storage at any point. The audio exists as one buffer, is fingerprinted, and is
 * dropped.
 */
class AndroidMicrophoneRecorder(
    private val context: Context,
    private val io: CoroutineDispatcher,
) : MicrophoneRecorder {

    override fun isAvailable(): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)

    override suspend fun record(targetDurationMs: Long, onAmplitude: (Float) -> Unit): RecognitionAudio =
        withContext(io) {
            val duration = targetDurationMs.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
            val source = preferredSource()
            val (recorder, sampleRate, bufferBytes) = open(source)

            try {
                recorder.startRecording()
                if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    throw RecordingFailure(RecognitionError.MICROPHONE_UNAVAILABLE)
                }

                val wanted = (sampleRate * BYTES_PER_SAMPLE * duration / 1000L).toInt()
                val captured = ByteArrayOutputStream(wanted)
                val chunk = ByteArray(bufferBytes)

                while (captured.size() < wanted) {
                    // Cancellation lands between chunks — a fraction of a second — and `finally` below
                    // is what actually frees the hardware.
                    coroutineContext.ensureActive()

                    val read = recorder.read(chunk, 0, minOf(chunk.size, wanted - captured.size()))
                    when {
                        read > 0 -> {
                            captured.write(chunk, 0, read)
                            onAmplitude(peakOf(chunk, read))
                        }
                        // A device that vanished mid-capture (a headset unplugged, the mic stolen by
                        // another app) is not the same failure as a bad request, and the user can act
                        // on the difference.
                        read == AudioRecord.ERROR_DEAD_OBJECT ->
                            throw RecordingFailure(RecognitionError.MICROPHONE_UNAVAILABLE)
                        read == AudioRecord.ERROR_INVALID_OPERATION ||
                            read == AudioRecord.ERROR_BAD_VALUE ||
                            read == AudioRecord.ERROR ->
                            throw RecordingFailure(RecognitionError.RECORDING_FAILED)
                        // read == 0: nothing ready yet. Loop; the blocking read paces us.
                    }
                }

                val pcm = captured.toByteArray()
                if (pcm.isEmpty()) throw RecordingFailure(RecognitionError.RECORDING_FAILED)

                RecognitionAudio(
                    pcm16LittleEndian = pcm,
                    sampleRateHz = sampleRate,
                    channelCount = 1,
                    durationMs = pcm.size * 1000L / (sampleRate * BYTES_PER_SAMPLE),
                )
            } finally {
                // Order matters, and both can throw on a device that already went away: stopping a
                // recorder that never started raises IllegalStateException, and leaving it unreleased
                // holds the microphone against the whole system.
                runCatching { if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop() }
                runCatching { recorder.release() }
            }
        }

    /**
     * Opens the first rate the device accepts, preferring the fingerprint's own.
     *
     * @return the recorder, the rate it opened at, and a read size.
     */
    private fun open(source: Int): Triple<AudioRecord, Int, Int> {
        var lastError = RecognitionError.MICROPHONE_UNAVAILABLE

        for (rate in SAMPLE_RATES) {
            val minBuffer = AudioRecord.getMinBufferSize(rate, CHANNEL, ENCODING)
            if (minBuffer == AudioRecord.ERROR_BAD_VALUE || minBuffer == AudioRecord.ERROR) continue

            // Room for roughly a quarter second, so a scheduling hiccup cannot overrun the buffer and
            // punch a hole in the middle of the fingerprint.
            val bufferBytes = maxOf(minBuffer, rate * BYTES_PER_SAMPLE / 4)

            val recorder = try {
                AudioRecord(source, rate, CHANNEL, ENCODING, bufferBytes)
            } catch (e: IllegalArgumentException) {
                continue
            } catch (e: SecurityException) {
                // The permission was revoked between the check and here.
                throw RecordingFailure(RecognitionError.PERMISSION)
            }

            if (recorder.state == AudioRecord.STATE_INITIALIZED) return Triple(recorder, rate, bufferBytes)

            recorder.release()
            lastError = RecognitionError.MICROPHONE_UNAVAILABLE
        }
        throw RecordingFailure(lastError)
    }

    /**
     * The least-processed input the device admits to having.
     *
     * Noise suppression and automatic gain are tuned to make *speech* intelligible: they duck steady
     * music, chase its level, and generally rearrange exactly the spectral peaks a fingerprint is made
     * of. `UNPROCESSED` asks for none of it, but only some devices really offer it, and the property
     * below is how they say so.
     */
    private fun preferredSource(): Int {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val unprocessed = audio?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
        return when {
            unprocessed?.equals("true", ignoreCase = true) == true -> MediaRecorder.AudioSource.UNPROCESSED
            else -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        }
    }

    /** Loudest sample in the chunk, 0..1 — for the meter, nothing else depends on it. */
    private fun peakOf(chunk: ByteArray, length: Int): Float {
        var peak = 0
        var i = 0
        while (i + 1 < length) {
            val sample = (chunk[i].toInt() and 0xFF) or (chunk[i + 1].toInt() shl 8)
            val magnitude = abs(sample.toShort().toInt())
            if (magnitude > peak) peak = magnitude
            i += 2
        }
        return (peak / Short.MAX_VALUE.toFloat()).coerceIn(0f, 1f)
    }

    private companion object {
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val BYTES_PER_SAMPLE = 2

        /** 16 kHz first: it is what the fingerprint needs, so anything else costs a resampling pass. */
        val SAMPLE_RATES = intArrayOf(16_000, 48_000, 44_100)

        const val MIN_DURATION_MS = 6_000L
        const val MAX_DURATION_MS = 12_000L
    }
}
