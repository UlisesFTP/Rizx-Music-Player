package fm.rizx.player.data.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.time.Duration

/**
 * The account's invalidation channel over Supabase Realtime, spoken directly on the app's OkHttp
 * (no second HTTP stack, ADR 0028): one WebSocket, one private topic `user:<uid>:sync`, heartbeats
 * every 25 s as the protocol asks, the JWT refreshed in place before it expires, and reconnection with
 * backoff when the link drops. A refusal that no retry can fix — no permission, a bad token, Realtime
 * off for the project — ends the attempt until the next [connect].
 *
 * It never applies anything: a `sync` broadcast becomes a [SyncInvalidation] and the scheduler decides
 * whether that is worth a run. Losing a message is harmless, so nothing is acknowledged or replayed.
 */
class SyncInvalidationSocket(
    private val client: OkHttpClient,
    private val endpoint: String,
    /** The account's current access token, refreshed by the caller when close to expiry. */
    private val tokenProvider: suspend () -> String?,
    private val scope: CoroutineScope,
    private val heartbeatEvery: Duration = Duration.ofSeconds(25),
    private val tokenRefreshEvery: Duration = Duration.ofMinutes(45),
    private val backoffMillis: LongArray = longArrayOf(1_000, 2_000, 5_000, 10_000, 30_000, 60_000),
    private val log: (String) -> Unit = {},
) : SyncInvalidations {

    private val _events = MutableSharedFlow<SyncInvalidation>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: Flow<SyncInvalidation> = _events.asSharedFlow()

    private val lock = Any()
    private var wanted: String? = null
    private var session: Session? = null
    private var reconnect: Job? = null
    private var failures = 0

    override fun connect(userId: String) {
        synchronized(lock) {
            if (wanted == userId && session != null) return
            wanted = userId
            failures = 0
            reconnect?.cancel()
            reconnect = null
            session?.close()
            session = null
            open()
        }
    }

    override fun disconnect() {
        synchronized(lock) {
            wanted = null
            reconnect?.cancel()
            reconnect = null
            session?.close()
            session = null
        }
    }

    /** Caller holds [lock]. */
    private fun open() {
        val userId = wanted ?: return
        session = Session(userId).also { it.start() }
    }

    private fun sessionEnded(ended: Session, why: String, retry: Boolean) {
        synchronized(lock) {
            if (session !== ended) return
            ended.close()
            session = null
            if (wanted == null) return
            if (!retry) {
                log("invalidation channel gave up: $why")
                return
            }
            val wait = backoffMillis[minOf(failures, backoffMillis.lastIndex)]
            failures++
            log("invalidation channel lost ($why); retrying in ${wait}ms")
            reconnect = scope.launch {
                delay(wait)
                synchronized(lock) { if (wanted != null && session == null) open() }
            }
        }
    }

    private inner class Session(userId: String) : WebSocketListener() {
        private val topic = PhoenixFrame.syncTopic(userId)
        private val job = SupervisorJob(scope.coroutineContext[Job])
        private val sessionScope = CoroutineScope(scope.coroutineContext + job)

        @Volatile private var socket: WebSocket? = null
        @Volatile private var joinRef: String? = null
        @Volatile private var closed = false
        @Volatile private var token = ""
        private var refs = 0

        private fun nextRef(): String = synchronized(this) { (++refs).toString() }

        fun start() {
            sessionScope.launch {
                val fresh = tokenProvider()
                if (fresh == null) {
                    sessionEnded(this@Session, "no session token", retry = false)
                    return@launch
                }
                token = fresh
                if (!closed) socket = client.newWebSocket(Request.Builder().url(endpoint).build(), this@Session)
            }
        }

        fun close() {
            if (closed) return
            closed = true
            job.cancel()
            socket?.close(NORMAL_CLOSURE, null)
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            val ref = nextRef()
            joinRef = ref
            webSocket.send(PhoenixFrame.join(topic, ref, token).encode())
            sessionScope.launch {
                while (isActive) {
                    delay(heartbeatEvery.toMillis())
                    webSocket.send(PhoenixFrame.heartbeat(nextRef()).encode())
                }
            }
            sessionScope.launch {
                while (isActive) {
                    delay(tokenRefreshEvery.toMillis())
                    val renewed = tokenProvider() ?: continue
                    val joined = joinRef ?: continue
                    webSocket.send(PhoenixFrame.accessToken(topic, nextRef(), joined, renewed).encode())
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = PhoenixFrame.decode(text) ?: return
            when (frame.event) {
                PhoenixFrame.REPLY -> if (frame.topic == topic && frame.ref == joinRef) onJoinReply(frame)
                PhoenixFrame.BROADCAST -> frame.invalidation()?.let { _events.tryEmit(it) }
                PhoenixFrame.ERROR, PhoenixFrame.CLOSE ->
                    if (frame.topic == topic) sessionEnded(this, frame.event, retry = true)
                PhoenixFrame.SYSTEM ->
                    if (frame.topic == topic && frame.replyStatus == "error") sessionEnded(this, "system: ${frame.reason}", retry = true)
            }
        }

        private fun onJoinReply(frame: PhoenixFrame) {
            if (frame.replyStatus == "ok") {
                synchronized(lock) { failures = 0 }
                log("invalidation channel joined")
                return
            }
            val reason = frame.reason ?: "join refused"
            sessionEnded(this, "join: $reason", retry = FINAL_REASONS.none { reason.contains(it, ignoreCase = true) })
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            sessionEnded(this, "failure: ${t.message ?: t.javaClass.simpleName}", retry = true)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            sessionEnded(this, "closed $code", retry = true)
        }
    }

    private companion object {
        const val NORMAL_CLOSURE = 1000

        /** Join refusals the protocol documents as "do not retry"; an expired token is deliberately not here. */
        val FINAL_REASONS = listOf("Unauthorized", "MalformedJWT", "JwtSignatureError", "RealtimeDisabled", "TenantNotFound", "TopicNameRequired")
    }
}
