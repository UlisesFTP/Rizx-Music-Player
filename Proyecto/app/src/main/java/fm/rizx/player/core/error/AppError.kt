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
