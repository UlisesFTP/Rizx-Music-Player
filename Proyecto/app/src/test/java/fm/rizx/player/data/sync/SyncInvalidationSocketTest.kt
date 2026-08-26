package fm.rizx.player.data.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The socket against a local Realtime stand-in: real frames over a real WebSocket, nothing mocked inside OkHttp. */
class SyncInvalidationSocketTest {

    private val server = MockWebServer()
    private val client = OkHttpClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val url get() = server.url("/realtime/v1/websocket?apikey=k&vsn=1.0.0").toString()
    private val peers = mutableListOf<Peer>()

    /**
     * Fixture clean-up only, so nothing here may fail a test: OkHttp's cancel paths and MockWebServer's
     * shutdown (which waits 5 s for a WebSocket task queue that a torn-down socket can hold for its
     * 60 s close timeout) both throw on their own, and neither is the behaviour under test.
     */
    @After
    fun tearDown() {
        scope.cancel()
        peers.forEach { peer -> runCatching { peer.socket?.cancel() } }
        runCatching { client.dispatcher.cancelAll() }
        runCatching { client.dispatcher.executorService.shutdown() }
        runCatching { client.connectionPool.evictAll() }
        runCatching { server.shutdown() }
    }

    /** The server end of one WebSocket: records what the client sends and can answer in kind. */
    private class Peer : WebSocketListener() {
        val frames = LinkedBlockingQueue<PhoenixFrame>()
        @Volatile var socket: WebSocket? = null
        override fun onOpen(webSocket: WebSocket, response: Response) { socket = webSocket }
        override fun onMessage(webSocket: WebSocket, text: String) { PhoenixFrame.decode(text)?.let(frames::add) }
        fun next(): PhoenixFrame = frames.poll(5, TimeUnit.SECONDS) ?: error("the client sent nothing")
        fun reply(to: PhoenixFrame, status: String, reason: String? = null) {
            val payload = buildJsonObject {
                put("status", status)
                putJsonObject("response") { if (reason != null) put("reason", reason) }
            }
            socket!!.send(PhoenixFrame(to.topic, PhoenixFrame.REPLY, payload, to.ref, to.joinRef).encode())
        }
    }

    private fun expect(peer: Peer) {
        peers += peer
        server.enqueue(MockResponse().withWebSocketUpgrade(peer))
    }

    private fun socket(
        heartbeat: Duration = Duration.ofSeconds(25),
        tokens: suspend () -> String? = { "jwt-1" },
    ) = SyncInvalidationSocket(
        client, url, tokenProvider = tokens, scope = scope,
        heartbeatEvery = heartbeat, backoffMillis = longArrayOf(50), log = {},
    )

    @Test
    fun `joins the account's private channel with the token and turns a broadcast into an event`() = runBlocking {
        val peer = Peer()
        expect(peer)
        val socket = socket()
        val received = Channel<SyncInvalidation>(Channel.UNLIMITED)
        val collecting = scope.launch { socket.events.collect { received.send(it) } }

        socket.connect("u1")

        val join = peer.next()
        assertEquals(PhoenixFrame.JOIN, join.event)
        assertEquals("realtime:user:u1:sync", join.topic)
        assertEquals(join.ref, join.joinRef)
        val payload = join.payload.jsonObject
        assertEquals("jwt-1", payload["access_token"]!!.jsonPrimitive.content)
        assertTrue(payload["config"]!!.jsonObject["private"]!!.jsonPrimitive.boolean)

        peer.reply(join, "ok")
        peer.socket!!.send(
            """{"topic":"realtime:user:u1:sync","event":"broadcast","payload":{"event":"sync","type":"broadcast",""" +
                """"payload":{"revision":42,"device_id":"other"}},"ref":null}""",
        )
        assertEquals(SyncInvalidation(42, "other"), withTimeout(5_000) { received.receive() })

        collecting.cancel()
        socket.disconnect()
    }

    @Test
    fun `keeps the connection alive with protocol heartbeats`() {
        val peer = Peer()
        expect(peer)
        val socket = socket(heartbeat = Duration.ofMillis(100))

        socket.connect("u1")
        peer.reply(peer.next(), "ok")

        val beat = generateSequence { peer.frames.poll(2, TimeUnit.SECONDS) }.firstOrNull { it.event == "heartbeat" }
        assertNotNull("no heartbeat within two seconds", beat)
        assertEquals(PhoenixFrame.HEARTBEAT_TOPIC, beat!!.topic)
        socket.disconnect()
    }

    @Test
    fun `an unauthorized join is final while an expired token is retried with a fresh one`() {
        val refusing = Peer()
        expect(refusing)
        val socket = socket()
        socket.connect("u1")
        refusing.reply(refusing.next(), "error", "Unauthorized: You do not have permissions to read from this Channel topic")
        Thread.sleep(400)
        assertEquals("a final refusal is not retried", 1, server.requestCount)
        socket.disconnect()

        var issued = 0
        val first = Peer()
        val second = Peer()
        expect(first)
        expect(second)
        val retrying = socket(tokens = { "jwt-${++issued}" })
        retrying.connect("u1")
        first.reply(first.next(), "error", "InvalidJWTExpiration: Token has expired 300 seconds ago")
        val rejoin = second.next()
        assertEquals(PhoenixFrame.JOIN, rejoin.event)
        assertEquals("a fresh token on the rejoin", "jwt-2", rejoin.payload.jsonObject["access_token"]!!.jsonPrimitive.content)
        retrying.disconnect()
    }
}
