package fm.rizx.player.core.error

/**
 * Structured, typed failures raised by the data layer (real providers, network). Extends [Exception]
 * so it flows through the existing `catch (e: Exception)` sites (repositories, resolver, ViewModels)
 * while carrying a stable category and a human-readable [message]. Mandated by ADR 0006: a broken
 * provider must fail independently and surface as a typed error — never crash the app.
 */
sealed class AppError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** No active provider of the requested kind (e.g. all providers disabled). */
    class ProviderUnavailable(kind: String) : AppError("No active $kind provider")

    /** A provider call failed (non-2xx HTTP, bad payload, empty required data, …). */
    class ProviderFailure(
        val providerName: String,
        detail: String,
        cause: Throwable? = null,
    ) : AppError("$providerName: $detail", cause)

    /** Transport-level failure (no connectivity, timeout, DNS, …). */
    class Network(detail: String, cause: Throwable? = null) : AppError("Network error: $detail", cause)
}

/**
 * The single point of translation from "whatever an exception says" to "what a user is safe to read".
 * [AppError]'s own [Exception.message] embeds the raw provider/network detail (HTTP codes, hostnames,
 * zip-entry paths, transpiler output, …) — useful for logs, never for the UI. This never reads that
 * detail: known [AppError] kinds map to a fixed, generic sentence built only from already-safe fields
 * (a provider's display name); anything else — including a bare [AppError] subtype added later, or a
 * raw platform exception that slipped past a provider's normalization — falls through to [fallback].
 */
fun Throwable.toSafeMessage(fallback: String): String = when (this) {
    is AppError.Network -> "You're offline. Connect and try again."
    is AppError.ProviderUnavailable -> "No source is available for this right now."
    is AppError.ProviderFailure -> "Couldn't reach $providerName. Try again in a moment."
    else -> fallback
}
