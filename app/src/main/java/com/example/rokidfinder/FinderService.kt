package com.example.rokidfinder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.app.NotificationCompat
import android.util.Log
import androidx.lifecycle.LifecycleService
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Foreground service: camera + multi-item detection + voice.
 *
 * Runs ItemDetector on each throttled frame, maintains one independent
 * visible→absent state machine per tracked item, persists a memory via
 * [MemoryStore] when an item disappears for [ABSENT_THRESHOLD_MS], and handles
 * voice queries dispatched from the UI via [ACTION_REQUEST_LISTEN].
 *
 * Also owns the optional link to the Mac "brain" ([LinkClient] + [FrameUplink]).
 * The link is additive: when it is down, everything above still works, which is the
 * whole point of keeping the on-device pipeline.
 */
class FinderService : LifecycleService() {

    private var source: CameraFrameSource? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var detector: ItemDetector? = null
    private var voice: VoiceListener? = null
    private var link: LinkClient? = null
    private var uplink: FrameUplink? = null
    private var streamer: AudioStreamer? = null
    private var speakQueue: SpeakQueue? = null
    private lateinit var store: MemoryStore
    private val voiceExecutor = Executors.newSingleThreadExecutor()

    /** Image fetches and the answer watchdog; kept off the Vosk executor. */
    private val netExecutor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "FinderNet").apply { isDaemon = true }
    }
    private var answerWatchdog: ScheduledFuture<*>? = null

    /** One buffered good look: the frame plus everything detected in it. */
    private class Sighting(val frame: Bitmap, val dets: List<ItemDetector.Det>)

    /** Per-item state. Mutable; only touched from the camera analyzer thread. */
    private class ItemState(val key: String) {
        var lastSeenAtMs: Long = 0L
        var lastSaveAtMs: Long = 0L
        var pendingAbsence: Boolean = false
        var lastSeenConf: Float = 0f
        val goodFrames: ArrayDeque<Sighting> = ArrayDeque()
    }
    private val states: Map<String, ItemState> =
        TrackedItems.ALL.associate { it.key to ItemState(it.key) }

    @Volatile private var lastInferMs: Long = 0L
    @Volatile private var lastFpsTickMs: Long = 0L
    @Volatile private var fpsCounter: Int = 0
    @Volatile private var measuredFps: Float = 0f

    private val listenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_REQUEST_LISTEN) handleListenRequest()
        }
    }

    /**
     * Ask Android to keep the radio up for as long as we are watching.
     *
     * These glasses power WiFi down when they go idle — observed three times in one
     * afternoon, on a device with 8 hours of uptime and no reboot in between, which
     * rules out a boot-time default. For a product whose whole promise is answering
     * the moment it is asked, a radio that switches itself off is not a nuisance but
     * a broken premise. A WifiLock is the sanctioned way to say "this app needs the
     * network while it runs"; it is released with the service, so nothing is held
     * once the camera stops.
     */
    private fun acquireWifiLock() {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = runCatching {
            wm.createWifiLock(mode, "rokidfinder:link").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
        Log.i(TAG, "wifi lock held=${wifiLock?.isHeld == true}")
    }

    override fun onCreate() {
        super.onCreate()
        acquireWifiLock()
        startInForeground()
        store = MemoryStore(this)

        val det = ItemDetector(this)
        detector = det

        val v = VoiceListener(this).also { it.preload() }
        voice = v

        // The brain speaks through the same Piper engine the local pipeline uses,
        // so it must be warm even if MainActivity never ran.
        Tts.ensureInit(this)
        val queue = SpeakQueue()
        speakQueue = queue
        val client = LinkClient(this, queue) { state -> broadcastLink(state) }
        link = client
        uplink = FrameUplink(client)
        client.inbound = inbound
        streamer = AudioStreamer(this, client, audioEvents)
        client.start()

        val cam = CameraFrameSource(this) { bitmap ->
            val now = System.currentTimeMillis()

            // Mirror the frame to the display for screen recording (off by
            // default). Done before the detection throttle so the preview is
            // smooth at the camera's native rate, independent of inference fps.
            if (FramePreview.enabled) {
                val w = PREVIEW_WIDTH
                val h = (bitmap.height.toLong() * w / bitmap.width).toInt().coerceAtLeast(1)
                runCatching {
                    FramePreview.publish(Bitmap.createScaledBitmap(bitmap, w, h, true))
                }
            }

            // Uplink before the inference throttle: the Mac dictates its own frame
            // rate via `config`, and it must not be coupled to local inference fps.
            // Non-blocking — drops the frame if the socket is busy.
            uplink?.offer(bitmap)

            if (now - lastInferMs < (1000 / TARGET_FPS)) {
                bitmap.recycle()
                return@CameraFrameSource
            }
            lastInferMs = now

            val dets: List<ItemDetector.Det> = try {
                det.detect(bitmap)
            } catch (t: Throwable) {
                emptyList()
            }
            val confs = ItemDetector.bestByTrackedLabel(dets)

            val visibleNow = mutableListOf<String>()
            for (spec in TrackedItems.ALL) {
                val conf = confs[spec.coco.lowercase()] ?: 0f
                val state = states[spec.key] ?: continue
                if (conf >= VISIBLE_THRESHOLD) {
                    onVisible(state, bitmap, dets, now, conf)
                    visibleNow += spec.display
                } else {
                    onAbsent(state, now)
                }
            }
            bitmap.recycle()

            fpsCounter++
            if (lastFpsTickMs == 0L) lastFpsTickMs = now
            if (now - lastFpsTickMs >= 1000) {
                measuredFps = fpsCounter * 1000f / (now - lastFpsTickMs)
                fpsCounter = 0
                lastFpsTickMs = now
            }

            broadcastResult(visibleNow.joinToString(", "), measuredFps)
        }
        cam.start(this)
        source = cam

        val filter = IntentFilter(ACTION_REQUEST_LISTEN)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(listenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(listenReceiver, filter)
        }
    }

    @Synchronized
    private fun onVisible(
        state: ItemState,
        src: Bitmap,
        dets: List<ItemDetector.Det>,
        nowMs: Long,
        conf: Float,
    ) {
        state.lastSeenAtMs = nowMs
        state.lastSeenConf = conf
        state.pendingAbsence = true
        // Own copy: the analyzer recycles the source after the loop, and other items
        // in the same frame may also want to keep it. The frame's detections ride
        // along so save-time context comes from the exact frame we snapshot.
        state.goodFrames.addLast(Sighting(src.copy(Bitmap.Config.ARGB_8888, false), dets))
        while (state.goodFrames.size > BUFFER_SIZE) {
            state.goodFrames.removeFirst().frame.recycle()
        }
    }

    @Synchronized
    private fun onAbsent(state: ItemState, nowMs: Long) {
        if (!state.pendingAbsence) return
        if (state.lastSeenAtMs == 0L || state.goodFrames.isEmpty()) return
        val gone = nowMs - state.lastSeenAtMs
        if (gone < ABSENT_THRESHOLD_MS) return
        if (nowMs - state.lastSaveAtMs < SAVE_COOLDOWN_MS) {
            clearBuffer(state); state.pendingAbsence = false; return
        }
        val best = state.goodFrames.last()
        val context = SceneContext.derive(state.key, best.dets)
        val saved = store.save(
            state.key, best.frame, state.lastSeenConf,
            surface = context.surface,
            near = context.near,
        )
        if (saved != null) {
            state.lastSaveAtMs = nowMs
            broadcastSaved(state.key, nowMs)
        }
        clearBuffer(state)
        state.pendingAbsence = false
    }

    private fun clearBuffer(state: ItemState) {
        while (state.goodFrames.isNotEmpty()) state.goodFrames.removeFirst().frame.recycle()
    }

    private fun clearAllBuffers() {
        for (s in states.values) clearBuffer(s)
    }

    /**
     * A tap is either a question for the brain or a question for the device, and which
     * one is decided *here, now* — never cached at startup. [LinkClient.isConnected]
     * reflects the live handshake state, so a Mac that went away between two taps
     * silently costs the wearer nothing but a narrower vocabulary.
     */
    private fun handleListenRequest() {
        val client = link
        val stream = streamer
        if (client != null && stream != null && client.isConnected() && client.config.audioEnabled) {
            stream.start()
            return
        }
        Log.i(TAG, "brain unreachable — answering on-device")
        handleListenLocally()
    }

    /** The Phase 1 path, untouched: 3 s of Vosk against the fixed grammar. */
    private fun handleListenLocally() {
        val v = voice ?: return
        if (!v.isReady()) {
            broadcastVoice(state = STATE_NOT_READY, transcript = null, itemKey = null)
            return
        }
        voiceExecutor.execute {
            broadcastVoice(state = STATE_LISTENING, transcript = null, itemKey = null)
            val text = v.listen(windowMs = LISTEN_MS)
            val itemKey = if (text != null) matchItem(text) else null
            broadcastVoice(
                state = if (itemKey != null) STATE_MATCHED else STATE_DONE,
                transcript = text,
                itemKey = itemKey,
            )
        }
    }

    // --- the streamed voice loop ---------------------------------------------

    private val audioEvents = object : AudioStreamer.Events {
        override fun onListening() {
            broadcastVoice(STATE_LISTENING, transcript = null, itemKey = null)
        }

        /**
         * The mic or the link failed before anything was sent, so nothing has been
         * committed to the streamed path yet — fall back rather than fail the tap.
         */
        override fun onUnavailable() {
            Log.w(TAG, "audio stream unavailable — falling back on-device")
            handleListenLocally()
        }

        override fun onFinished(reason: String, heardSpeech: Boolean) {
            if (!heardSpeech) {
                // No point waiting on the Mac to tell us what silence sounded like.
                broadcastVoice(STATE_DONE, transcript = null, itemKey = null)
                return
            }
            armAnswerWatchdog()
        }
    }

    private val inbound = object : LinkClient.Inbound {
        override fun onTranscript(text: String, final: Boolean) {
            if (text.isBlank()) return
            broadcastVoice(STATE_HEARD, transcript = text, itemKey = null)
        }

        override fun onAnswer(answer: LinkClient.Answer) {
            cancelAnswerWatchdog()
            // Speech first and unconditionally: the photo is a bonus, never a gate.
            speakQueue?.offer(answer.speak, "normal")
            val imageId = answer.imageId
            if (imageId == null) {
                broadcastAnswer(answer, photoPath = null)
                return
            }
            netExecutor.execute {
                val path = link?.fetchImage(imageId)?.let { cacheAnswerPhoto(imageId, it) }
                broadcastAnswer(answer, photoPath = path)
            }
        }

        override fun onQueryError(code: String) {
            cancelAnswerWatchdog()
            // `speak` on the error has already been queued by LinkClient; the HUD only
            // needs to stop signalling that we are still working on it.
            broadcastVoice(STATE_DONE, transcript = null, itemKey = null)
        }
    }

    /**
     * Write the answer photo somewhere the UI process can read it. A single reused
     * filename per image id keeps the cache from growing without bound, and stale
     * files are harmless — the id is in the name, so a hit is always the right photo.
     */
    private fun cacheAnswerPhoto(imageId: String, bytes: ByteArray): String? = runCatching {
        val dir = File(cacheDir, ANSWER_PHOTO_DIR).apply { mkdirs() }
        // Ids come from the Mac; keep them from escaping the directory.
        val safe = imageId.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val f = File(dir, "$safe.jpg")
        f.writeBytes(bytes)
        f.absolutePath
    }.getOrNull()

    /**
     * If the Mac never answers, the HUD must not pulse forever. This is the only
     * timeout on the streamed path — everything else is driven by messages.
     */
    private fun armAnswerWatchdog() {
        cancelAnswerWatchdog()
        answerWatchdog = runCatching {
            netExecutor.schedule({
                Log.w(TAG, "no answer within ${ANSWER_TIMEOUT_MS}ms")
                broadcastVoice(STATE_DONE, transcript = null, itemKey = null)
            }, ANSWER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }.getOrNull()
    }

    private fun cancelAnswerWatchdog() {
        answerWatchdog?.cancel(false)
        answerWatchdog = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
        runCatching { unregisterReceiver(listenReceiver) }
        cancelAnswerWatchdog()
        voiceExecutor.shutdownNow()
        netExecutor.shutdownNow()
        source?.stop()
        detector?.close()
        voice?.close()
        // Before the link, so an open utterance stops writing to a socket we are closing.
        streamer?.shutdown()
        link?.inbound = null
        link?.stop()
        speakQueue?.shutdown()
        clearAllBuffers()
        source = null
        detector = null
        voice = null
        streamer = null
        uplink = null
        link = null
        speakQueue = null
        super.onDestroy()
    }

    private fun broadcastResult(visibleSummary: String, fps: Float) {
        sendBroadcast(
            Intent(ACTION_RESULT)
                .setPackage(packageName)
                .putExtra(EXTRA_VISIBLE_SUMMARY, visibleSummary)
                .putExtra(EXTRA_FPS, fps)
                .putExtra(EXTRA_VOICE_READY, voice?.isReady() == true)
        )
    }

    private fun broadcastSaved(itemKey: String, tsMs: Long) {
        sendBroadcast(
            Intent(ACTION_SAVED)
                .setPackage(packageName)
                .putExtra(EXTRA_SAVED_ITEM_KEY, itemKey)
                .putExtra(EXTRA_LAST_SAVED_TS, tsMs)
        )
    }

    /** Called from the link thread; sendBroadcast is safe off the main thread. */
    private fun broadcastLink(state: LinkClient.State) {
        sendBroadcast(
            Intent(ACTION_LINK)
                .setPackage(packageName)
                .putExtra(EXTRA_LINK_STATE, state.name)
        )
    }

    /**
     * The answer travels to [MainActivity] rather than being shown from here: a
     * foreground service on Android 12 cannot reliably start an activity, and the
     * activity is already on screen. [photoPath] is null whenever the photo was
     * absent or unfetchable — the answer still shows and has already been spoken.
     */
    private fun broadcastAnswer(answer: LinkClient.Answer, photoPath: String?) {
        sendBroadcast(
            Intent(ACTION_VOICE)
                .setPackage(packageName)
                .putExtra(EXTRA_VOICE_STATE, STATE_ANSWER)
                .putExtra(EXTRA_VOICE_TRANSCRIPT, "")
                .putExtra(EXTRA_VOICE_ITEM_KEY, "")
                .putExtra(EXTRA_ANSWER_TITLE, answer.hudTitle)
                .putExtra(EXTRA_ANSWER_TEXT, answer.hudText.ifBlank { answer.speak })
                .putExtra(EXTRA_ANSWER_PHOTO, photoPath ?: "")
        )
    }

    private fun broadcastVoice(state: String, transcript: String?, itemKey: String?) {
        sendBroadcast(
            Intent(ACTION_VOICE)
                .setPackage(packageName)
                .putExtra(EXTRA_VOICE_STATE, state)
                .putExtra(EXTRA_VOICE_TRANSCRIPT, transcript ?: "")
                .putExtra(EXTRA_VOICE_ITEM_KEY, itemKey ?: "")
        )
    }

    private fun startInForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "RokidFinder", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RokidFinder watching")
            .setContentText("On-device second-brain memory")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .build()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, type)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    companion object {
        const val TARGET_FPS = 3
        /** Downscaled width of the record-mode display preview (height keeps aspect). */
        const val PREVIEW_WIDTH = 480
        const val BUFFER_SIZE = 3
        const val ABSENT_THRESHOLD_MS = 2000L
        const val SAVE_COOLDOWN_MS = 15_000L
        const val LISTEN_MS = 3000L
        const val VISIBLE_THRESHOLD = ItemDetector.SCORE_THRESHOLD

        const val ACTION_RESULT = "com.example.rokidfinder.RESULT"
        const val ACTION_SAVED = "com.example.rokidfinder.SAVED"
        const val ACTION_VOICE = "com.example.rokidfinder.VOICE"
        const val ACTION_REQUEST_LISTEN = "com.example.rokidfinder.REQUEST_LISTEN"
        const val ACTION_LINK = "com.example.rokidfinder.LINK"

        const val EXTRA_VISIBLE_SUMMARY = "visibleSummary"
        const val EXTRA_FPS = "fps"
        const val EXTRA_VOICE_READY = "voiceReady"
        const val EXTRA_SAVED_ITEM_KEY = "savedItemKey"
        const val EXTRA_LAST_SAVED_TS = "lastSavedTs"
        const val EXTRA_VOICE_STATE = "voiceState"
        const val EXTRA_VOICE_TRANSCRIPT = "voiceTranscript"
        const val EXTRA_VOICE_ITEM_KEY = "voiceItemKey"
        const val EXTRA_ANSWER_TITLE = "answerTitle"
        const val EXTRA_ANSWER_TEXT = "answerText"
        /** Absolute path to a cached JPEG, or empty when there is no photo. */
        const val EXTRA_ANSWER_PHOTO = "answerPhoto"
        /** One of [LinkClient.State] names. */
        const val EXTRA_LINK_STATE = "linkState"

        const val STATE_LISTENING = "listening"
        const val STATE_MATCHED = "matched"
        const val STATE_DONE = "done"
        const val STATE_NOT_READY = "not_ready"
        /** The Mac's transcript of what it heard — HUD only, never spoken. */
        const val STATE_HEARD = "heard"
        /** A full answer from the Mac: speech is already under way. */
        const val STATE_ANSWER = "answer"

        private const val TAG = "RokidFinder.Service"
        private const val CHANNEL_ID = "rokidfinder"
        private const val ANSWER_PHOTO_DIR = "answers"
        /** Generous: local ASR plus a local LLM on the Mac is not instant. */
        private const val ANSWER_TIMEOUT_MS = 12_000L
        private const val NOTIF_ID = 1

        /**
         * Map a transcribed query to a tracked item key (or null). The user
         * tapped before speaking, so the intent is implicit — we just look for
         * any tracked item name as a substring.
         */
        fun matchItem(text: String): String? {
            val t = text.lowercase()
            // "phone" / "laptop" / "cup" — exact display name as the trigger word.
            for (spec in TrackedItems.ALL) {
                if (spec.display.lowercase() in t) return spec.key
            }
            return null
        }
    }
}
