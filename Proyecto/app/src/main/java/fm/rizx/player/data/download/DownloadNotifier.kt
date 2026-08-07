package fm.rizx.player.data.download

/**
 * Keeps the process alive while downloads are in flight. Implemented by the foreground service, so a
 * batch survives the user leaving the app.
 *
 * An interface rather than a direct service call so the repository stays testable on the JVM, and so a
 * rejected foreground start degrades to a plain background download instead of crashing.
 */
interface DownloadNotifier {
    /**
     * Called when there is work to keep alive. Must be idempotent.
     *
     * **There is deliberately no `stop`.** More than one thing now needs the process kept alive —
     * downloads and 8D renders — and a caller that stopped the service when *its own* queue drained
     * would kill the other one's work halfway through. The service watches every queue and stops
     * itself, which is the only place that can see all of them at once.
     */
    fun start()
}

/** For tests and any build where downloads run without a service. */
object NoopDownloadNotifier : DownloadNotifier {
    override fun start() = Unit
}
