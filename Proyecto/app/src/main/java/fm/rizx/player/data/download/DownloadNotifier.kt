package fm.rizx.player.data.download

/**
 * Keeps the process alive while downloads are in flight. Implemented by the foreground service, so a
 * batch survives the user leaving the app.
 *
 * An interface rather than a direct service call so the repository stays testable on the JVM, and so a
 * rejected foreground start degrades to a plain background download instead of crashing.
 */
interface DownloadNotifier {
    /** Called when the queue becomes non-empty. Must be idempotent. */
    fun start()

    /** Called when the queue drains. Must be idempotent. */
    fun stop()
}

/** For tests and any build where downloads run without a service. */
object NoopDownloadNotifier : DownloadNotifier {
    override fun start() = Unit
    override fun stop() = Unit
}
