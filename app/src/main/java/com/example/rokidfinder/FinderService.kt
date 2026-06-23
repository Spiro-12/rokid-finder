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
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import java.util.concurrent.Executors

/**
 * Foreground service: camera + multi-item detection + voice.
 *
 * Runs ItemDetector on each throttled frame, maintains one independent
 * visible→absent state machine per tracked item, persists a memory via
 * [MemoryStore] when an item disappears for [ABSENT_THRESHOLD_MS], and handles
 * voice queries dispatched from the UI via [ACTION_REQUEST_LISTEN].
 */
class FinderService : LifecycleService() {

    private var source: CameraFrameSource? = null
    private var detector: ItemDetector? = null
    private var voice: VoiceListener? = null
    private lateinit var store: MemoryStore
    private val voiceExecutor = Executors.newSingleThreadExecutor()

    /** Per-item state. Mutable; only touched from the camera analyzer thread. */
    private class ItemState(val key: String) {
        var lastSeenAtMs: Long = 0L
        var lastSaveAtMs: Long = 0L
        var pendingAbsence: Boolean = false
        var lastSeenConf: Float = 0f
        val goodFrames: ArrayDeque<Bitmap> = ArrayDeque()
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

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        store = MemoryStore(this)

        val det = ItemDetector(this)
        detector = det

        val v = VoiceListener(this).also { it.preload() }
        voice = v

        val cam = CameraFrameSource(this) { bitmap ->
            val now = System.currentTimeMillis()
            if (now - lastInferMs < (1000 / TARGET_FPS)) {
                bitmap.recycle()
                return@CameraFrameSource
            }
            lastInferMs = now

            val confs: Map<String, Float> = try {
                det.detect(bitmap)
            } catch (t: Throwable) {
                emptyMap()
            }

            val visibleNow = mutableListOf<String>()
            for (spec in TrackedItems.ALL) {
                val conf = confs[spec.coco.lowercase()] ?: 0f
                val state = states[spec.key] ?: continue
                if (conf >= VISIBLE_THRESHOLD) {
                    onVisible(state, bitmap, now, conf)
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
    private fun onVisible(state: ItemState, src: Bitmap, nowMs: Long, conf: Float) {
        state.lastSeenAtMs = nowMs
        state.lastSeenConf = conf
        state.pendingAbsence = true
        // Own copy: the analyzer recycles the source after the loop, and other items
        // in the same frame may also want to keep it.
        state.goodFrames.addLast(src.copy(Bitmap.Config.ARGB_8888, false))
        while (state.goodFrames.size > BUFFER_SIZE) {
            state.goodFrames.removeFirst().recycle()
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
        val saved = store.save(state.key, best, state.lastSeenConf)
        if (saved != null) {
            state.lastSaveAtMs = nowMs
            broadcastSaved(state.key, nowMs)
        }
        clearBuffer(state)
        state.pendingAbsence = false
    }

    private fun clearBuffer(state: ItemState) {
        while (state.goodFrames.isNotEmpty()) state.goodFrames.removeFirst().recycle()
    }

    private fun clearAllBuffers() {
        for (s in states.values) clearBuffer(s)
    }

    private fun handleListenRequest() {
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(listenReceiver) }
        voiceExecutor.shutdownNow()
        source?.stop()
        detector?.close()
        voice?.close()
        clearAllBuffers()
        source = null
        detector = null
        voice = null
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
        const val BUFFER_SIZE = 3
        const val ABSENT_THRESHOLD_MS = 2000L
        const val SAVE_COOLDOWN_MS = 15_000L
        const val LISTEN_MS = 3000L
        const val VISIBLE_THRESHOLD = ItemDetector.SCORE_THRESHOLD

        const val ACTION_RESULT = "com.example.rokidfinder.RESULT"
        const val ACTION_SAVED = "com.example.rokidfinder.SAVED"
        const val ACTION_VOICE = "com.example.rokidfinder.VOICE"
        const val ACTION_REQUEST_LISTEN = "com.example.rokidfinder.REQUEST_LISTEN"

        const val EXTRA_VISIBLE_SUMMARY = "visibleSummary"
        const val EXTRA_FPS = "fps"
        const val EXTRA_VOICE_READY = "voiceReady"
        const val EXTRA_SAVED_ITEM_KEY = "savedItemKey"
        const val EXTRA_LAST_SAVED_TS = "lastSavedTs"
        const val EXTRA_VOICE_STATE = "voiceState"
        const val EXTRA_VOICE_TRANSCRIPT = "voiceTranscript"
        const val EXTRA_VOICE_ITEM_KEY = "voiceItemKey"

        const val STATE_LISTENING = "listening"
        const val STATE_MATCHED = "matched"
        const val STATE_DONE = "done"
        const val STATE_NOT_READY = "not_ready"

        private const val CHANNEL_ID = "rokidfinder"
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
