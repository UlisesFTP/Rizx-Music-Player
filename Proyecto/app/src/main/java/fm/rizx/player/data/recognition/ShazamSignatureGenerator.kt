package fm.rizx.player.data.recognition

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin

/**
 * Builds the acoustic fingerprint the Shazam-compatible endpoint expects.
 *
 * **This file is a port, not a design.** Every constant, window shape and ring-buffer offset below is
 * dictated by the wire format the service accepts; there is no freedom here. The algorithm is the one
 * documented by the SongRec project (GPL-3.0, `marin-m/SongRec`), reached here through the Kotlin port
 * in Echo Music. A "simplified" or "cleaned up" version — a plain spectrogram, a different window, a
 * rounder magnitude curve — still produces a well-formed request that the service answers with an
 * empty match list, forever, silently. So changes here must be validated against the live endpoint
 * with real audio, never against intuition.
 *
 * **No Android imports, on purpose.** [Base64] is `java.util`, available since API 26 — which is this
 * app's `minSdk` — rather than `android.util.Base64`, whose JVM stub returns null under plain unit
 * tests. That one substitution is what lets the whole fingerprint be covered by ordinary JVM tests
 * instead of needing an instrumented device, and it is the only intentional deviation from the
 * reference implementation.
 *
 * The shape of the work: 10 seconds of 16 kHz mono audio slide through a 2048-sample window in
 * 128-sample steps; each step is Hann-windowed and transformed; the resulting spectra are "spread"
 * across neighbouring bins and recent frames so a peak has to beat its surroundings in both time and
 * frequency; surviving peaks are bucketed into four frequency bands and serialised with delta-encoded
 * frame numbers, then wrapped in a header carrying a CRC32 of everything after it.
 */
internal class ShazamSignatureGenerator : AudioSignatureGenerator {

    override fun generate(pcm16LittleEndian: ByteArray): String {
        require(pcm16LittleEndian.isNotEmpty()) { "signature input is empty" }
        require(pcm16LittleEndian.size % 2 == 0) {
            "signature input must hold whole 16-bit samples, got ${pcm16LittleEndian.size} bytes"
        }
        // The generator stops reading at MAX_TIME_SECONDS anyway; the cap is here so a bug upstream
        // cannot hand this a multi-megabyte buffer and have it quietly allocate a ShortArray of it.
        require(pcm16LittleEndian.size <= MAX_INPUT_BYTES) {
            "signature input of ${pcm16LittleEndian.size} bytes exceeds the ${MAX_INPUT_BYTES}-byte cap"
        }

        val pcm = ShortArray(pcm16LittleEndian.size / 2)
        ByteBuffer.wrap(pcm16LittleEndian).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm)
        return Session().run(pcm)
    }

    /**
     * One fingerprint being built. Holds several ring buffers, so it is single-use and never shared:
     * [generate] creates a fresh one per call, which is also what makes the class safe to inject as a
     * singleton.
     */
    private class Session {

        /** The most recent [FFT_SIZE] samples, oldest at [samplesPos]. */
        private val samples = IntArray(FFT_SIZE)
        private var samplesPos = 0

        private val fftOutputs = Array(RING_SIZE) { DoubleArray(FFT_BINS) }
        private var fftPos = 0

        private val spread = Array(RING_SIZE) { DoubleArray(FFT_BINS) }
        private var spreadPos = 0
        private var spreadWritten = 0

        private var processedSamples = 0

        private val bands = Array(BAND_COUNT) { mutableListOf<Peak>() }
        private var peakCount = 0

        fun run(pcm: ShortArray): String {
            var offset = 0
            while (offset + HOP <= pcm.size) {
                // Both conditions, as in the reference: a short recording is fingerprinted whole, and a
                // long one keeps going past 12 s only while it is still short of peaks to describe it.
                val elapsedSeconds = processedSamples.toDouble() / SIGNATURE_SAMPLE_RATE_HZ
                if (elapsedSeconds >= MAX_TIME_SECONDS && peakCount >= MAX_PEAKS) break

                processedSamples += HOP
                for (i in offset until offset + HOP) {
                    samples[samplesPos] = pcm[i].toInt()
                    samplesPos = (samplesPos + 1) % FFT_SIZE
                }
                transform()
                spreadPeaks()
                if (spreadWritten >= FRAMES_BEFORE_RECOGNITION) recognisePeaks()
                offset += HOP
            }
            return encode()
        }

        /** Hann-window the whole ring — oldest sample first — and store its magnitude spectrum. */
        private fun transform() {
            val windowed = DoubleArray(FFT_SIZE) { i ->
                samples[(samplesPos + i) % FFT_SIZE].toDouble() * HANN[i]
            }
            magnitudes(windowed).copyInto(fftOutputs[fftPos])
            fftPos = (fftPos + 1) % RING_SIZE
        }

        /**
         * Smear each magnitude forwards over two neighbouring bins and backwards over three earlier
         * frames, so [recognisePeaks] can ask "is this bin louder than everything around it" with a
         * single comparison instead of a search.
         */
        private fun spreadPeaks() {
            val latest = fftOutputs[(fftPos - 1 + RING_SIZE) % RING_SIZE].copyOf()

            for (bin in 0 until FFT_BINS - 2) {
                latest[bin] = maxOf(latest[bin], latest[bin + 1], latest[bin + 2])
            }

            for (bin in 0 until FFT_BINS) {
                var running = latest[bin]
                for (back in TIME_SPREAD_OFFSETS) {
                    val index = ((spreadPos + back) % RING_SIZE + RING_SIZE) % RING_SIZE
                    val previous = spread[index][bin]
                    if (previous > running) running = previous
                    spread[index][bin] = running
                }
            }

            latest.copyInto(spread[spreadPos])
            spreadPos = (spreadPos + 1) % RING_SIZE
            spreadWritten++
        }

        /** Collect the bins of one older frame that stand above their neighbourhood in time and frequency. */
        private fun recognisePeaks() {
            val frame = fftOutputs[(fftPos - 46 + RING_SIZE * 2) % RING_SIZE]
            val reference = spread[(spreadPos - 49 + RING_SIZE * 2) % RING_SIZE]

            for (bin in FIRST_BIN until FFT_BINS - LAST_BIN_MARGIN) {
                val magnitude = frame[bin]
                if (magnitude < MAGNITUDE_FLOOR || magnitude < reference[bin]) continue

                var rival = 0.0
                for (neighbour in BIN_NEIGHBOURS) {
                    val value = reference[bin + neighbour]
                    if (value > rival) rival = value
                }
                if (magnitude <= rival) continue

                for (back in FRAME_NEIGHBOURS) {
                    val index = ((spreadPos + back) % RING_SIZE + RING_SIZE) % RING_SIZE
                    val value = spread[index][bin - 1]
                    if (value > rival) rival = value
                }
                if (magnitude <= rival) continue

                val peak = logMagnitude(magnitude)
                val before = logMagnitude(frame[bin - 1])
                val after = logMagnitude(frame[bin + 1])

                // Interpolate the true peak between the two bins either side of it, so a tone landing
                // between bins is still reported at one frequency rather than jittering between two.
                val curvature = peak * 2 - before - after
                val correctedBin = bin * BIN_SCALE + (after - before) * 32 / curvature
                val hz = correctedBin * (SIGNATURE_SAMPLE_RATE_HZ / 2.0 / 1024.0 / BIN_SCALE)

                val band = bandOf(hz) ?: continue
                bands[band] += Peak(
                    frameNumber = spreadWritten - 46,
                    magnitude = peak.toInt(),
                    correctedBin = correctedBin.toInt(),
                )
                peakCount++
            }
        }

        /**
         * Serialise the peaks, then wrap them in the header. The CRC32 covers everything from byte 8
         * onwards and is written back into bytes 4..7 afterwards, which is why the buffer is built
         * first and patched second.
         */
        private fun encode(): String {
            val contents = ByteArrayOutputStream()

            for (band in 0 until BAND_COUNT) {
                val peaks = bands[band]
                if (peaks.isEmpty()) continue

                val encoded = ByteArrayOutputStream()
                var previousFrame = 0
                for (peak in peaks) {
                    // One byte holds the gap to the previous peak. A longer gap is escaped with 0xFF
                    // followed by the absolute frame number, after which the gap itself is zero.
                    if (peak.frameNumber - previousFrame >= FRAME_GAP_ESCAPE) {
                        encoded.write(FRAME_GAP_ESCAPE)
                        encoded.writeIntLe(peak.frameNumber)
                        previousFrame = peak.frameNumber
                    }
                    encoded.write(peak.frameNumber - previousFrame)
                    encoded.writeShortLe(peak.magnitude)
                    encoded.writeShortLe(peak.correctedBin)
                    previousFrame = peak.frameNumber
                }

                val bytes = encoded.toByteArray()
                contents.writeIntLe(BAND_MARKER + band)
                contents.writeIntLe(bytes.size)
                contents.write(bytes)
                repeat((4 - bytes.size % 4) % 4) { contents.write(0) }
            }

            val body = contents.toByteArray()
            val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(MAGIC_1)
                .putInt(0) // CRC32, patched in below
                .putInt(body.size + 8)
                .putInt(MAGIC_2)
                .putInt(0).putInt(0).putInt(0)
                .putInt(RATE_16_KHZ)
                .putInt(0).putInt(0)
                .putInt((processedSamples + SIGNATURE_SAMPLE_RATE_HZ * SAMPLE_OFFSET_SECONDS).toInt())
                .putInt(FIXED_TAIL)
                .array()

            val full = ByteArrayOutputStream(HEADER_BYTES + 8 + body.size).apply {
                write(header)
                writeIntLe(CONTENTS_MARKER)
                writeIntLe(body.size + 8)
                write(body)
            }.toByteArray()

            val crc = CRC32().apply { update(full, 8, full.size - 8) }.value.toInt()
            full[4] = crc.toByte()
            full[5] = (crc ushr 8).toByte()
            full[6] = (crc ushr 16).toByte()
            full[7] = (crc ushr 24).toByte()

            return SIGNATURE_URI_PREFIX + Base64.getEncoder().encodeToString(full)
        }

        private fun bandOf(hz: Double): Int? = when {
            hz < 250.0 -> null
            hz < 520.0 -> 0
            hz < 1450.0 -> 1
            hz < 3500.0 -> 2
            hz <= 5500.0 -> 3
            else -> null
        }

        private fun logMagnitude(value: Double): Double =
            ln(max(MAGNITUDE_FLOOR, value)) * LOG_SCALE + LOG_OFFSET

        private data class Peak(val frameNumber: Int, val magnitude: Int, val correctedBin: Int)
    }

    internal companion object {
        const val SIGNATURE_URI_PREFIX = "data:audio/vnd.shazam.sig;base64,"

        private const val FFT_SIZE = 2048
        private const val FFT_BINS = FFT_SIZE / 2 + 1
        private const val HOP = 128
        private const val RING_SIZE = 256
        private const val BAND_COUNT = 4
        private const val MAX_PEAKS = 255
        private const val MAX_TIME_SECONDS = 12.0
        private const val HEADER_BYTES = 48

        /** 16 s of 16 kHz mono PCM — comfortably above the longest capture, far below memory pressure. */
        private const val MAX_INPUT_BYTES = SIGNATURE_SAMPLE_RATE_HZ * 2 * 16

        /** Nothing is emitted until the ring holds enough frames for the backwards look-ups to be real. */
        private const val FRAMES_BEFORE_RECOGNITION = 47

        private const val FIRST_BIN = 10
        private const val LAST_BIN_MARGIN = 8
        private const val MAGNITUDE_FLOOR = 1.0 / 64.0
        private const val LOG_SCALE = 1477.3
        private const val LOG_OFFSET = 6144
        private const val BIN_SCALE = 64.0

        private val TIME_SPREAD_OFFSETS = intArrayOf(-1, -3, -6)
        private val BIN_NEIGHBOURS = intArrayOf(-10, -7, -4, -3, 1, 2, 5, 8)
        private val FRAME_NEIGHBOURS =
            intArrayOf(-53, -45, 165, 172, 179, 186, 193, 200, 214, 221, 228, 235, 242, 249)

        private const val FRAME_GAP_ESCAPE = 0xFF
        private const val BAND_MARKER = 0x60030040
        private const val CONTENTS_MARKER = 0x40000000
        private const val MAGIC_1 = 0xcafe2580.toInt()
        private const val MAGIC_2 = 0x94119c00.toInt()
        private const val RATE_16_KHZ = 3 shl 27
        private const val FIXED_TAIL = (15 shl 19) + 0x40000
        private const val SAMPLE_OFFSET_SECONDS = 0.24

        /** Hann window. The `+1`/2049 shift is the reference's, and the spectrum shifts without it. */
        private val HANN = DoubleArray(FFT_SIZE) { i ->
            0.5 * (1.0 - cos(2.0 * PI * (i + 1).toDouble() / (FFT_SIZE + 1).toDouble()))
        }

        /**
         * Magnitude spectrum of a real signal via an in-place radix-2 FFT. Returns [FFT_BINS] values
         * scaled the way the reference does — the absolute scale matters, because the peak threshold
         * and the logarithmic magnitude curve are both calibrated against it.
         */
        internal fun magnitudes(windowed: DoubleArray): DoubleArray {
            val n = windowed.size
            val re = windowed.copyOf()
            val im = DoubleArray(n)

            var j = 0
            for (i in 1 until n) {
                var bit = n ushr 1
                while (j and bit != 0) {
                    j = j xor bit
                    bit = bit ushr 1
                }
                j = j xor bit
                if (i < j) {
                    val tmpRe = re[i]; re[i] = re[j]; re[j] = tmpRe
                    val tmpIm = im[i]; im[i] = im[j]; im[j] = tmpIm
                }
            }

            var len = 2
            while (len <= n) {
                val half = len ushr 1
                val angle = -PI / half
                val stepRe = cos(angle)
                val stepIm = sin(angle)
                var start = 0
                while (start < n) {
                    var wRe = 1.0
                    var wIm = 0.0
                    for (k in 0 until half) {
                        val u = start + k
                        val v = u + half
                        val oddRe = re[v] * wRe - im[v] * wIm
                        val oddIm = re[v] * wIm + im[v] * wRe
                        val evenRe = re[u]
                        val evenIm = im[u]
                        re[u] = evenRe + oddRe
                        im[u] = evenIm + oddIm
                        re[v] = evenRe - oddRe
                        im[v] = evenIm - oddIm
                        val nextRe = wRe * stepRe - wIm * stepIm
                        wIm = wRe * stepIm + wIm * stepRe
                        wRe = nextRe
                    }
                    start += len
                }
                len = len shl 1
            }

            return DoubleArray(FFT_BINS) { bin ->
                val power = (re[bin] * re[bin] + im[bin] * im[bin]) / (1 shl 17)
                if (power < POWER_FLOOR) POWER_FLOOR else power
            }
        }

        private const val POWER_FLOOR = 1e-10
    }
}

private fun ByteArrayOutputStream.writeIntLe(value: Int) {
    write(value and 0xFF)
    write((value ushr 8) and 0xFF)
    write((value ushr 16) and 0xFF)
    write((value ushr 24) and 0xFF)
}

private fun ByteArrayOutputStream.writeShortLe(value: Int) {
    write(value and 0xFF)
    write((value ushr 8) and 0xFF)
}
