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
 * Foreground service: camera + detection + voice. Runs the watching loop, persists
 * a last-seen photo when the phone disappears, and handles voice-query requests
 * dispatched from the UI via [ACTION_REQUEST_LISTEN].
 */
class FinderService : LifecycleService() {

    private var source: CameraFrameSource? = null
    private var detector: PhoneDetector? = null
    private var voice: VoiceListener? = null
    private lateinit var store: LastSeenStore
    private val voiceExecutor = Executors.newSingleThreadExecutor()

    private val goodFrames = ArrayDeque<Bitmap>()

    @Volatile private var lastInferMs: Long = 0L
    @Volatile private var lastFpsTickMs: Long = 0L
    @Volatile private var fpsCounter: Int = 0
    @Volatile private var measuredFps: Float = 0f

    @Volatile private var lastSeenAtMs: Long = 0L
    @Volatile private var lastSaveAtMs: Long = 0L
    @Volatile private var pendingAbsence: Boolean = false

    private val listenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_REQUEST_LISTEN) handleListenRequest()
        }
    }

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        store = LastSeenStore(this)

        val det = PhoneDetector(this)
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

            val result = try {
                det.detect(bitmap)
            } catch (t: Throwable) {
                PhoneDetector.Result(false, 0f)
            }

            if (result.visible) onVisible(bitmap, now) else onAbsent(bitmap, now)

            fpsCounter++
            if (lastFpsTickMs == 0L) lastFpsTickMs = now
            if (now - lastFpsTickMs >= 1000) {
                measuredFps = fpsCounter * 1000f / (now - lastFpsTickMs)
                fpsCounter = 0
                lastFpsTickMs = now
            }

            broadcastResult(result, measuredFps)
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
    private fun onVisible(bitmap: Bitmap, nowMs: Long) {
        lastSeenAtMs = nowMs
        pendingAbsence = true
        goodFrames.addLast(bitmap)
        while (goodFrames.size > BUFFER_SIZE) {
            goodFrames.removeFirst().recycle()
        }
    }

    @Synchronized
    private fun onAbsent(bitmap: Bitmap, nowMs: Long) {
        bitmap.recycle()
        if (!pendingAbsence) return
        if (lastSeenAtMs == 0L || goodFrames.isEmpty()) return
        val gone = nowMs - lastSeenAtMs
        if (gone < ABSENT_THRESHOLD_MS) return
        if (nowMs - lastSaveAtMs < SAVE_COOLDOWN_MS) {
            clearBuffer()
            pendingAbsence = false
            return
        }

        val best = goodFrames.last()
        if (store.save(best)) {
            lastSaveAtMs = nowMs
            broadcastSaved(nowMs)
        }
        clearBuffer()
        pendingAbsence = false
    }

    private fun clearBuffer() {
        while (goodFrames.isNotEmpty()) goodFrames.removeFirst().recycle()
    }

    private fun handleListenRequest() {
        val v = voice ?: return
        if (!v.isReady()) {
            broadcastVoice(state = STATE_NOT_READY, transcript = null)
            return
        }
        voiceExecutor.execute {
            broadcastVoice(state = STATE_LISTENING, transcript = null)
            val text = v.listen(windowMs = LISTEN_MS)
            val matched = text != null && isPhoneQuery(text)
            broadcastVoice(
                state = if (matched) STATE_MATCHED else STATE_DONE,
                transcript = text
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
        clearBuffer()
        source = null
        detector = null
        voice = null
        super.onDestroy()
    }

    private fun broadcastResult(r: PhoneDetector.Result, fps: Float) {
        sendBroadcast(
            Intent(ACTION_RESULT)
                .setPackage(packageName)
                .putExtra(EXTRA_VISIBLE, r.visible)
                .putExtra(EXTRA_CONFIDENCE, r.confidence)
                .putExtra(EXTRA_FPS, fps)
                .putExtra(EXTRA_LAST_SAVED_TS, store.timestampMs())
                .putExtra(EXTRA_VOICE_READY, voice?.isReady() == true)
        )
    }

    private fun broadcastSaved(tsMs: Long) {
        sendBroadcast(
            Intent(ACTION_SAVED)
                .setPackage(packageName)
                .putExtra(EXTRA_LAST_SAVED_TS, tsMs)
        )
    }

    private fun broadcastVoice(state: String, transcript: String?) {
        sendBroadcast(
            Intent(ACTION_VOICE)
                .setPackage(packageName)
                .putExtra(EXTRA_VOICE_STATE, state)
                .putExtra(EXTRA_VOICE_TRANSCRIPT, transcript ?: "")
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
            .setContentText("On-device phone finder")
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

        const val ACTION_RESULT = "com.example.rokidfinder.RESULT"
        const val ACTION_SAVED = "com.example.rokidfinder.SAVED"
        const val ACTION_VOICE = "com.example.rokidfinder.VOICE"
        const val ACTION_REQUEST_LISTEN = "com.example.rokidfinder.REQUEST_LISTEN"
        const val EXTRA_VISIBLE = "visible"
        const val EXTRA_CONFIDENCE = "confidence"
        const val EXTRA_FPS = "fps"
        const val EXTRA_LAST_SAVED_TS = "lastSavedTs"
        const val EXTRA_VOICE_READY = "voiceReady"
        const val EXTRA_VOICE_STATE = "voiceState"
        const val EXTRA_VOICE_TRANSCRIPT = "voiceTranscript"

        const val STATE_LISTENING = "listening"
        const val STATE_MATCHED = "matched"
        const val STATE_DONE = "done"
        const val STATE_NOT_READY = "not_ready"

        private const val CHANNEL_ID = "rokidfinder"
        private const val NOTIF_ID = 1

        /**
         * Heard text counts as a phone query if it mentions the phone. The user
         * tapped before speaking, so the intent is implicit — no extra verb required.
         */
        fun isPhoneQuery(text: String): Boolean = "phone" in text.lowercase()
    }
}
