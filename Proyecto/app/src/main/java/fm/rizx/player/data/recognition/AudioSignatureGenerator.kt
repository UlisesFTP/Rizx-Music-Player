package fm.rizx.player.data.recognition

/**
 * Turns raw captured audio into the acoustic fingerprint a recognition service can match.
 *
 * The seam exists so the provider can be tested without running an FFT over 10 seconds of audio, and
 * so a second recognition backend — which would want its own fingerprint format — can be added without
 * touching the recorder or the repository.
 *
 * Implementations take **mono, signed 16-bit, little-endian PCM at 16 kHz** and are expected to be
 * deterministic: the same bytes must always produce the same string.
 */
internal fun interface AudioSignatureGenerator {

    /**
     * @param pcm16LittleEndian mono 16-bit PCM at [SIGNATURE_SAMPLE_RATE_HZ], little-endian.
     * @return a fingerprint URI ready to be sent to the service.
     * @throws IllegalArgumentException if the input is empty, of odd length, or implausibly large.
     */
    fun generate(pcm16LittleEndian: ByteArray): String
}

/** The only sample rate a signature may be computed at — everything upstream resamples to it. */
internal const val SIGNATURE_SAMPLE_RATE_HZ = 16_000
