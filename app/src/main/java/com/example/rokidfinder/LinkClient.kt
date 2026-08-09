package com.example.rokidfinder

import android.content.Context
import android.os.BatteryManager
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * The single WebSocket to the Mac "brain": frames up, commands and speech down.
 *
 * Owned by [FinderService] — deliberately a plain class rather than a second Android
 * Service, so the link's lifetime is exactly the camera's lifetime and there is one
 * process-level owner of teardown.
 *
 * The glasses always dial out. The Mac never connects to us, which is what makes this
 * survive NAT, firewalls, and the Mac not knowing the glasses' address. Losing the link
 * degrades the product but never breaks it: the on-device EfficientDet/Vosk/SQLite path
 * keeps running untouched while we reconnect in the background.
 *
 * Threading: every state transition and every outbound JSON message happens on the
 * single-threaded [exec]; OkHttp delivers inbound messages on its own reader thread and
 * we only mutate volatiles there. Nothing here touches the main thread or the camera
 * analyzer thread.
 */
class LinkClient(
    context: Context,
    private val speakQueue: SpeakQueue,
    private val onState: (State) -> Unit,
) {

    enum class State { OFFLINE, CONNECTING, CONNECTED }

    /** The Mac owns capture policy; these are only the defaults until `welcome` lands. */
    data class Config(
        val frameFps: Double = 2.5,
        val frameMaxWidth: Int = 640,
        val jpegQuality: Int = 80,
    )

    private val appContext = context.applicationContext

    @Volatile var config: Config = Config()
        private set

    @Volatile private var state: State = State.OFFLINE
    @Volatile private var socket: WebSocket? = null
    @Volatile private var handshakeDone: Boolean = false
    @Volatile private var lastPongMs: Long = 0L
    @Volatile private var generation: Int = 0
    @Volatile private var stopped: Boolean = false

    private var attempt: Int = 0
    private var heartbeat: ScheduledFuture<*>? = null
    private var reconnect: ScheduledFuture<*>? = null

    private val exec = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "LinkClient").apply { isDaemon = true }
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        // No socket read timeout: idleness is legitimate. Our own pong watchdog is
        // the liveness check, and it is the one the protocol specifies.
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(0, TimeUnit.MILLISECONDS)
        .build()

    /**
     * The socket to push binary frames into, or null if the handshake hasn't completed.
     * Frames must never precede `welcome`.
     */
    fun readySocket(): WebSocket? = if (handshakeDone) socket else null

    /** Post to the link thread, tolerating a shutdown race from an OkHttp callback. */
    private fun submit(block: () -> Unit) {
        runCatching { exec.execute(block) }
    }

    fun start() {
        submit { connect() }
    }

    fun stop() {
        stopped = true
        submit {
            cancelTimers()
            runCatching { socket?.close(1000, "shutting down") }
            socket = null
            handshakeDone = false
            setState(State.OFFLINE)
        }
        exec.shutdown()
    }

    // --- connection lifecycle (exec thread) ---------------------------------

    private fun connect() {
        if (stopped) return
        cancelTimers()
        handshakeDone = false
        setState(State.CONNECTING)

        val endpoint = Discovery.resolve(appContext)
        if (endpoint == null) {
            Log.i(TAG, "no brain on the LAN; staying degraded")
            setState(State.OFFLINE)
            scheduleReconnect()
            return
        }

        val gen = ++generation
        val url = "ws://${endpoint.host}:${endpoint.port}$PATH"
        Log.i(TAG, "connecting to $url")
        socket = http.newWebSocket(
            Request.Builder().url(url).build(),
            Listener(gen, endpoint.host, endpoint.port),
        )
    }

    /** Tears the current socket down and queues a backoff retry. Idempotent per generation. */
    private fun fail(gen: Int, why: String) {
        if (gen != generation) return
        Log.i(TAG, "link down: $why")
        generation++
        cancelTimers()
        runCatching { socket?.cancel() }
        socket = null
        handshakeDone = false
        setState(State.OFFLINE)
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (stopped) return
        val base = BACKOFF_MS.getOrElse(attempt) { BACKOFF_MS.last() }
        attempt++
        // ±20% jitter so a router reboot doesn't sync every retry into one burst.
        val delay = (base * (0.8 + Random.nextDouble() * 0.4)).toLong()
        Log.i(TAG, "reconnecting in ${delay}ms (attempt $attempt)")
        reconnect = runCatching {
            exec.schedule({ connect() }, delay, TimeUnit.MILLISECONDS)
        }.getOrNull()
    }

    private fun cancelTimers() {
        heartbeat?.cancel(false); heartbeat = null
        reconnect?.cancel(false); reconnect = null
    }

    private fun startHeartbeat(gen: Int) {
        heartbeat?.cancel(false)
        heartbeat = runCatching {
            exec.scheduleAtFixedRate({
                if (gen != generation) return@scheduleAtFixedRate
                val now = System.currentTimeMillis()
                if (now - lastPongMs > PONG_TIMEOUT_MS) {
                    fail(gen, "no pong in ${PONG_TIMEOUT_MS}ms")
                    return@scheduleAtFixedRate
                }
                socket?.send(JSONObject().put("type", "ping").put("ts", now).toString())
            }, PING_INTERVAL_MS, PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
        }.getOrNull()
    }

    private fun setState(next: State) {
        if (state == next) return
        state = next
        onState(next)
    }

    // --- messages ------------------------------------------------------------

    private fun sendHello(ws: WebSocket) {
        val hello = JSONObject()
            .put("type", "hello")
            .put("protocol_version", PROTOCOL_VERSION)
            .put("device", "rokid-glasses")
            .put("app_version", appVersion())
            .put("capabilities", org.json.JSONArray(listOf("frames", "tts", "hud")))
        batteryPct()?.let { hello.put("battery_pct", it) }
        ws.send(hello.toString())
    }

    private fun handleText(gen: Int, text: String) {
        if (gen != generation) return
        val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (val type = msg.optString("type")) {
            "welcome" -> onWelcome(gen, msg)
            "pong" -> lastPongMs = System.currentTimeMillis()
            "config" -> applyConfig(msg)
            "speak" -> speakQueue.offer(msg.optString("text"), msg.optString("priority"))
            // `speak` is the authoritative channel for all three; HUD text is Phase 2.
            "answer", "reminder" -> speakQueue.offer(msg.optString("speak"), "normal")
            "alert" -> speakQueue.offer(msg.optString("speak"), "urgent")
            "transcript" -> Log.i(TAG, "transcript: ${msg.optString("text")}")
            "error" -> onError(gen, msg)
            // Forward compatibility: either side may add types. Never fatal.
            else -> Log.i(TAG, "ignoring unknown message type '$type'")
        }
    }

    private fun onWelcome(gen: Int, msg: JSONObject) {
        val version = msg.optInt("protocol_version", PROTOCOL_VERSION)
        if (version != PROTOCOL_VERSION) {
            Log.w(TAG, "protocol mismatch: mac=$version glasses=$PROTOCOL_VERSION")
        }
        msg.optJSONObject("config")?.let { applyConfig(it) }
        handshakeDone = true
        lastPongMs = System.currentTimeMillis()
        Log.i(TAG, "welcome, session=${msg.optString("session_id")}")
        submit {
            if (gen != generation) return@submit
            attempt = 0
            setState(State.CONNECTED)
            startHeartbeat(gen)
        }
    }

    private fun onError(gen: Int, msg: JSONObject) {
        val code = msg.optString("code")
        Log.w(TAG, "brain error: $code ${msg.optString("message")}")
        msg.optString("speak").takeIf { it.isNotBlank() }?.let { speakQueue.offer(it, "normal") }
        if (msg.optBoolean("fatal", false)) submit { fail(gen, "fatal error $code") }
    }

    private fun applyConfig(msg: JSONObject) {
        val current = config
        config = Config(
            frameFps = msg.optDouble("frame_fps", current.frameFps)
                .takeIf { !it.isNaN() } ?: current.frameFps,
            frameMaxWidth = msg.optInt("frame_max_width", current.frameMaxWidth)
                .coerceIn(64, 1920),
            jpegQuality = msg.optInt("jpeg_quality", current.jpegQuality).coerceIn(1, 100),
        )
        Log.i(TAG, "config: $config")
    }

    // --- helpers -------------------------------------------------------------

    private fun appVersion(): String = runCatching {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
    }.getOrNull() ?: "unknown"

    /** Optional in `hello`; omitted rather than guessed if the HAL won't say. */
    private fun batteryPct(): Int? = runCatching {
        val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
    }.getOrNull()

    private inner class Listener(
        private val gen: Int,
        private val host: String,
        private val port: Int,
    ) : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (gen != generation) return
            // A TCP+HTTP upgrade that actually completed is proof the endpoint is
            // real, so cache it now — the next cold start can skip mDNS entirely.
            Discovery.cache(appContext, host, port)
            sendHello(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) = handleText(gen, text)

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // Nothing binary flows Mac→glasses in v1; images are fetched over HTTP.
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            submit { fail(gen, t.message ?: t.javaClass.simpleName) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            submit { fail(gen, "closing $code $reason") }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            submit { fail(gen, "closed $code $reason") }
        }
    }

    private companion object {
        const val TAG = "RokidFinder.Link"
        const val PROTOCOL_VERSION = 1
        const val PATH = "/link"
        const val CONNECT_TIMEOUT_MS = 5_000L
        const val PING_INTERVAL_MS = 10_000L
        const val PONG_TIMEOUT_MS = 25_000L
        /** 1, 2, 4, 8, 15 s, then every 30 s — jitter applied at use. */
        val BACKOFF_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000, 30_000)
    }
}
