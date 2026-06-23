package com.example.rokidfinder

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase-4 HUD. Tap the touchpad → listen for ~3s. If you say "where's my phone"
 * (or anything mentioning "phone" + an intent word), opens the Recall view.
 *
 * Grant on the glasses (Android 12):
 *   adb shell pm grant com.example.rokidfinder android.permission.CAMERA
 *   adb shell pm grant com.example.rokidfinder android.permission.RECORD_AUDIO
 */
class MainActivity : AppCompatActivity() {

    private lateinit var phoneStatus: TextView
    private lateinit var confidence: TextView
    private lateinit var fps: TextView
    private lateinit var lastSeenWhen: TextView
    private lateinit var lastSeenThumb: ImageView
    private lateinit var voiceState: TextView

    private lateinit var store: LastSeenStore
    private val timeFmt = SimpleDateFormat("h:mm a", Locale.US)
    private val handler = Handler(Looper.getMainLooper())
    private var voiceReady: Boolean = false
    private var isListening: Boolean = false

    private val tick = object : Runnable {
        override fun run() {
            refreshLastSeen()
            handler.postDelayed(this, 10_000)
        }
    }

    private val clearVoiceMsg = Runnable { voiceState.text = "" }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                FinderService.ACTION_RESULT -> {
                    val visible = intent.getBooleanExtra(FinderService.EXTRA_VISIBLE, false)
                    val conf = intent.getFloatExtra(FinderService.EXTRA_CONFIDENCE, 0f)
                    val f = intent.getFloatExtra(FinderService.EXTRA_FPS, 0f)
                    voiceReady = intent.getBooleanExtra(FinderService.EXTRA_VOICE_READY, false)
                    if (visible) {
                        phoneStatus.text = "phone: VISIBLE"
                        phoneStatus.setTextColor(0xFF00FF66.toInt())
                    } else {
                        phoneStatus.text = "phone: not in view"
                        phoneStatus.setTextColor(0xFFFF6633.toInt())
                    }
                    confidence.text = "conf: ${"%.2f".format(conf)}"
                    fps.text = "fps: ${"%.1f".format(f)} | voice: ${if (voiceReady) "ready" else "loading"}"
                }
                FinderService.ACTION_SAVED -> refreshLastSeen()
                FinderService.ACTION_VOICE -> handleVoice(intent)
            }
        }
    }

    private fun handleVoice(intent: Intent) {
        val state = intent.getStringExtra(FinderService.EXTRA_VOICE_STATE) ?: return
        val transcript = intent.getStringExtra(FinderService.EXTRA_VOICE_TRANSCRIPT) ?: ""
        handler.removeCallbacks(clearVoiceMsg)
        when (state) {
            FinderService.STATE_LISTENING -> {
                isListening = true
                voiceState.text = "● listening…"
                voiceState.setTextColor(0xFFFFCC33.toInt())
            }
            FinderService.STATE_MATCHED -> {
                isListening = false
                voiceState.text = ""
                startActivity(Intent(this, RecallActivity::class.java))
            }
            FinderService.STATE_DONE -> {
                isListening = false
                voiceState.text = if (transcript.isBlank())
                    "didn't catch that"
                else
                    "heard: \"$transcript\""
                voiceState.setTextColor(0xFFFFFFFF.toInt())
                handler.postDelayed(clearVoiceMsg, 2500)
            }
            FinderService.STATE_NOT_READY -> {
                isListening = false
                voiceState.text = "voice model still loading…"
                voiceState.setTextColor(0xFF888888.toInt())
                handler.postDelayed(clearVoiceMsg, 2500)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        phoneStatus = findViewById(R.id.phoneStatus)
        confidence = findViewById(R.id.confidence)
        fps = findViewById(R.id.fps)
        lastSeenWhen = findViewById(R.id.lastSeenWhen)
        lastSeenThumb = findViewById(R.id.lastSeenThumb)
        voiceState = findViewById(R.id.voiceState)

        store = LastSeenStore(this)
        refreshLastSeen()
        requestNeededPermissions()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(FinderService.ACTION_RESULT)
            addAction(FinderService.ACTION_SAVED)
            addAction(FinderService.ACTION_VOICE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
        handler.post(tick)
    }

    override fun onStop() {
        super.onStop()
        runCatching { unregisterReceiver(receiver) }
        handler.removeCallbacks(tick)
    }

    private var inputBlockedUntilMs: Long = 0L

    override fun onResume() {
        super.onResume()
        if (granted(Manifest.permission.CAMERA)) startFinder()
        refreshLastSeen()
        inputBlockedUntilMs = System.currentTimeMillis() + INPUT_BLOCK_MS
    }

    private fun requestNeededPermissions() {
        val needed = mutableListOf<String>()
        if (!granted(Manifest.permission.CAMERA)) needed += Manifest.permission.CAMERA
        if (!granted(Manifest.permission.RECORD_AUDIO)) needed += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !granted(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isEmpty()) {
            startFinder()
        } else {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQ)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (granted(Manifest.permission.CAMERA)) {
            startFinder()
        } else {
            phoneStatus.text = "camera denied — grant via adb"
        }
    }

    private fun startFinder() {
        ContextCompat.startForegroundService(this, Intent(this, FinderService::class.java))
    }

    private fun refreshLastSeen() {
        val ts = store.timestampMs()
        if (ts == 0L) {
            lastSeenWhen.text = "never"
            lastSeenThumb.setImageDrawable(null)
            return
        }
        lastSeenWhen.text = "${timeFmt.format(Date(ts))}  (${humanAgo(ts)})"
        store.photoPath()?.let { path ->
            val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
            val bmp = BitmapFactory.decodeFile(path, opts)
            if (bmp != null) lastSeenThumb.setImageBitmap(bmp)
        }
    }

    private fun humanAgo(tsMs: Long): String {
        val s = (System.currentTimeMillis() - tsMs) / 1000
        return when {
            s < 60 -> "just now"
            s < 3600 -> "${s / 60} min ago"
            s < 86_400 -> "${s / 3600} hr ago"
            else -> "${s / 86_400} d ago"
        }
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        Log.i(TAG, "main onKeyDown keycode=$keyCode")
        if (keyCode in IGNORED_KEYS) return super.onKeyDown(keyCode, event)
        if (System.currentTimeMillis() < inputBlockedUntilMs) return true
        if (isListening) return true
        // Trigger voice query in the FinderService.
        sendBroadcast(
            Intent(FinderService.ACTION_REQUEST_LISTEN).setPackage(packageName)
        )
        // Block further touchpad events until after the listen window finishes.
        inputBlockedUntilMs = System.currentTimeMillis() + (FinderService.LISTEN_MS + 500L)
        return true
    }

    companion object {
        private const val TAG = "RokidFinder.Main"
        private const val REQ = 100
        private const val INPUT_BLOCK_MS = 700L
        private val IGNORED_KEYS = setOf(
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_POWER,
            KeyEvent.KEYCODE_BACK,
        )
    }
}
