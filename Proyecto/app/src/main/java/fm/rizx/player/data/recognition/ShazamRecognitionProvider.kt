package fm.rizx.player.data.recognition

import fm.rizx.player.domain.recognition.RecognitionAudio
import fm.rizx.player.domain.recognition.RecognitionError
import fm.rizx.player.domain.recognition.RecognitionOutcome
import fm.rizx.player.domain.recognition.RecognitionProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * Recognition backed by the Shazam-compatible endpoint: fingerprints the audio locally, then asks the
 * service what it is.
 *
 * This is where the *policy* around that request lives — one call at a time, a floor on how often,
 * bounded retries, a short-lived memo — while [ShazamRecognitionClient] stays a bare transport.
 *
 * The politeness is deliberate and one-directional. Recognition is something a person taps, so a
 * single request in flight is always enough, and everything here errs towards asking the service
 * *less*: retries only for the failures that are actually transient, never for a refusal or a
 * malformed answer, which no amount of asking again will fix.
 */
internal class ShazamRecognitionProvider(
    private val client: ShazamRecognitionClient,
    private val signatures: AudioSignatureGenerator,
    private val resampler: Pcm16Resampler,
    private val io: CoroutineDispatcher,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val jitter: () -> Double = { Math.random() },
) : RecognitionProvider {

    override val id: String = ShazamRecognitionClient.PROVIDER_ID

    private val gate = Mutex()
    private var lastCallAt = 0L

    /** Fingerprint digest to (outcome, when). Never holds the fingerprint itself, and never persisted. */
    private val memo = LinkedHashMap<String, Pair<RecognitionOutcome, Long>>()

    override suspend fun recognize(audio: RecognitionAudio): RecognitionOutcome {
        val mono16k = withContext(io) {
            runCatching { resampler.toMono16k(audio.pcm16LittleEndian, audio.sampleRateHz, audio.channelCount) }
        }.getOrElse { return RecognitionOutcome.Failed(RecognitionError.RESAMPLING_FAILED) }

        val fingerprint = withContext(io) {
            runCatching { signatures.generate(mono16k) }
        }.getOrElse { return RecognitionOutcome.Failed(RecognitionError.SIGNATURE_FAILED) }

        val key = digest(fingerprint)
        cached(key)?.let { return it }

        val outcome = gate.withLock { request(fingerprint, audio.durationMs) }

        // Failures are transient by definition here — remembering one would keep answering "no network"
        // after the network came back.
        if (outcome !is RecognitionOutcome.Failed) remember(key, outcome)
        return outcome
    }

    private suspend fun request(signature: String, sampleMs: Long): RecognitionOutcome {
        var attempt = 1
        while (true) {
            throttle()
            val outcome = client.tag(signature, sampleMs)
            lastCallAt = now()

            if (outcome !is RecognitionOutcome.Failed || !outcome.error.isTransient()) return outcome
            if (attempt >= MAX_ATTEMPTS) return outcome

            // Exponential, with a little scatter so two devices that failed together don't come back
            // together.
            val backoff = BASE_BACKOFF_MS * (1L shl (attempt - 1)) + (jitter() * JITTER_MS).toLong()
            delay(backoff)
            attempt++
        }
    }

    /** Never two requests inside [MIN_INTERVAL_MS], however fast the button is pressed. */
    private suspend fun throttle() {
        val since = now() - lastCallAt
        if (lastCallAt != 0L && since in 0 until MIN_INTERVAL_MS) delay(MIN_INTERVAL_MS - since)
    }

    private fun cached(key: String): RecognitionOutcome? {
        val entry = memo[key] ?: return null
        if (now() - entry.second > MEMO_TTL_MS) {
            memo.remove(key)
            return null
        }
        return entry.first
    }

    private fun remember(key: String, outcome: RecognitionOutcome) {
        memo[key] = outcome to now()
        while (memo.size > MEMO_ENTRIES) memo.remove(memo.keys.first())
    }

    /**
     * SHA-256, not `hashCode()`. A 32-bit hash over a 6 KB fingerprint collides often enough to matter,
     * and a collision here means confidently naming the wrong song.
     */
    private fun digest(signature: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(signature.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /** Worth asking again; anything else is a settled answer. */
    private fun RecognitionError.isTransient(): Boolean = when (this) {
        RecognitionError.NETWORK,
        RecognitionError.RATE_LIMITED,
        RecognitionError.SERVICE_UNAVAILABLE,
        -> true
        else -> false
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val BASE_BACKOFF_MS = 1_000L
        const val JITTER_MS = 500
        const val MIN_INTERVAL_MS = 1_000L
        const val MEMO_TTL_MS = 5 * 60 * 1_000L
        const val MEMO_ENTRIES = 8
    }
}
