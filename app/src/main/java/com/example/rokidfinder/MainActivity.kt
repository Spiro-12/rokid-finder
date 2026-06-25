package com.example.rokidfinder

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Watching HUD — designed to disappear until needed. Idle wear shows only a
 * dim presence dot; saves and voice feedback surface as brief whispers; the
 * answer (full-bleed photo) lives in [RecallActivity]. A touchpad tap starts a
 * voice query; a matched item opens recall focused on that item.
 *
 * Grant on the glasses (Android 12):
 *   adb shell pm grant com.example.rokidfinder android.permission.CAMERA
 *   adb shell pm grant com.example.rokidfinder android.permission.RECORD_AUDIO
 */
class MainActivity : AppCompatActivity() {

    private lateinit var cameraPreview: ImageView
    private lateinit var whisper: TextView
    private lateinit var presenceDot: View

    private val handler = Handler(Looper.getMainLooper())
    private var voiceReady: Boolean = false
    private var isListening: Boolean = false

    private val hideWhisper = Runnable {
        whisper.animate().cancel()
        whisper.animate()
            .alpha(0f)
            .setDuration(WHISPER_FADE_OUT_MS)
            .withEndAction { whisper.visibility = View.INVISIBLE }
            .start()
    }

    /**
     * Show a brief, plain-English line low on screen, then fade it away. The
     * single ambient message slot for save events and voice feedback — Phase 2
     * replaces the four colored monospace branches of the old voiceState text.
     */
    private fun showWhisper(text: String, holdMs: Long = WHISPER_HOLD_DEFAULT_MS) {
        handler.removeCallbacks(hideWhisper)
        whisper.animate().cancel()
        whisper.text = text
        whisper.visibility = View.VISIBLE
        whisper.animate()
            .alpha(1f)
            .setDuration(WHISPER_FADE_IN_MS)
            .start()
        handler.postDelayed(hideWhisper, holdMs)
    }

    /** Slow alpha pulse on the presence dot — signals "listening". */
    private fun startDotPulse() {
        presenceDot.animate().cancel()
        pulseDown()
    }
    private fun pulseDown() {
        presenceDot.animate()
            .alpha(0.35f)
            .setDuration(DOT_PULSE_MS)
            .withEndAction { if (isListening) pulseUp() }
            .start()
    }
    private fun pulseUp() {
        presenceDot.animate()
            .alpha(1f)
            .setDuration(DOT_PULSE_MS)
            .withEndAction { if (isListening) pulseDown() }
            .start()
    }
    private fun stopDotPulse() {
        presenceDot.animate().cancel()
        presenceDot.alpha = 1f
    }

    /** Pulls the latest camera frame onto the display while record-mode is on. */
    private val previewTick = object : Runnable {
        override fun run() {
            FramePreview.take()?.let { cameraPreview.setImageBitmap(it) }
            if (FramePreview.enabled) handler.postDelayed(this, PREVIEW_FRAME_MS)
        }
    }

    /**
     * Record mode: mirror what the camera sees onto the monochrome display,
     * behind the HUD, so a screen recording captures the scene too. Toggled by
     * the volume-up key on the temple so it's hands-free while filming.
     */
    private fun togglePreview() {
        val on = !FramePreview.enabled
        Log.i(TAG, "togglePreview -> $on")
        FramePreview.enabled = on
        if (on) {
            cameraPreview.visibility = View.VISIBLE
            handler.removeCallbacks(previewTick)
            handler.post(previewTick)
        } else {
            handler.removeCallbacks(previewTick)
            cameraPreview.setImageBitmap(null)
            cameraPreview.visibility = View.GONE
            FramePreview.clear()
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                FinderService.ACTION_RESULT -> {
                    // Phase 1: the idle HUD is just the presence dot. We still
                    // track voiceReady internally so voice flows can short-circuit.
                    voiceReady = intent.getBooleanExtra(FinderService.EXTRA_VOICE_READY, false)
                }
                FinderService.ACTION_SAVED -> {
                    val key = intent.getStringExtra(FinderService.EXTRA_SAVED_ITEM_KEY) ?: ""
                    val display = TrackedItems.byKey(key)?.display ?: key
                    if (display.isNotBlank()) {
                        showWhisper("${display.replaceFirstChar { it.titlecase() }} remembered")
                    }
                }
                FinderService.ACTION_VOICE -> handleVoice(intent)
            }
        }
    }

    /**
     * Dev/recording trigger for record mode, so it can also be toggled from a
     * tethered laptop while filming:
     *   adb shell am broadcast -a com.example.rokidfinder.TOGGLE_PREVIEW
     * Exported on purpose so the adb shell uid can reach it; debug-only convenience.
     */
    private val toggleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_TOGGLE_PREVIEW) togglePreview()
        }
    }

    private fun handleVoice(intent: Intent) {
        val state = intent.getStringExtra(FinderService.EXTRA_VOICE_STATE) ?: return
        val itemKey = intent.getStringExtra(FinderService.EXTRA_VOICE_ITEM_KEY) ?: ""
        when (state) {
            FinderService.STATE_LISTENING -> {
                isListening = true
                startDotPulse()
            }
            FinderService.STATE_MATCHED -> {
                isListening = false
                stopDotPulse()
                openRecall(focusItemKey = itemKey.ifBlank { null })
            }
            FinderService.STATE_DONE -> {
                isListening = false
                stopDotPulse()
                showWhisper("Didn't catch that")
            }
            FinderService.STATE_NOT_READY -> {
                isListening = false
                stopDotPulse()
                showWhisper("One moment…")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        cameraPreview = findViewById(R.id.cameraPreview)
        whisper = findViewById(R.id.whisper)
        presenceDot = findViewById(R.id.presenceDot)

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
        // Exported on purpose so an `adb shell am broadcast` from a tethered
        // laptop can flip record mode. ContextCompat applies the right export
        // flag across API levels (targetSdk 34 defaults to not-exported otherwise).
        ContextCompat.registerReceiver(
            this, toggleReceiver, IntentFilter(ACTION_TOGGLE_PREVIEW),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    override fun onStop() {
        super.onStop()
        runCatching { unregisterReceiver(receiver) }
        runCatching { unregisterReceiver(toggleReceiver) }
        handler.removeCallbacks(previewTick)
        handler.removeCallbacks(hideWhisper)
        isListening = false
        stopDotPulse()
    }

    private var inputBlockedUntilMs: Long = 0L

    override fun onResume() {
        super.onResume()
        if (granted(Manifest.permission.CAMERA)) startFinder()
        if (FramePreview.enabled) {
            cameraPreview.visibility = View.VISIBLE
            handler.removeCallbacks(previewTick)
            handler.post(previewTick)
        }
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
            showWhisper("Camera denied", holdMs = 3000L)
        }
    }

    private fun startFinder() {
        ContextCompat.startForegroundService(this, Intent(this, FinderService::class.java))
    }

    private fun openRecall(focusItemKey: String?) {
        val i = Intent(this, RecallActivity::class.java)
        if (focusItemKey != null) i.putExtra(RecallActivity.EXTRA_FOCUS_ITEM, focusItemKey)
        startActivity(i)
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        Log.i(TAG, "main onKeyDown keycode=$keyCode")
        // Volume-up toggles record mode (camera preview on the display). Hands-free
        // while filming; volume-down still does normal system volume.
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            togglePreview()
            return true
        }
        if (keyCode in IGNORED_KEYS) return super.onKeyDown(keyCode, event)
        if (System.currentTimeMillis() < inputBlockedUntilMs) return true
        if (isListening) return true
        sendBroadcast(Intent(FinderService.ACTION_REQUEST_LISTEN).setPackage(packageName))
        inputBlockedUntilMs = System.currentTimeMillis() + (FinderService.LISTEN_MS + 500L)
        return true
    }

    companion object {
        private const val TAG = "RokidFinder.Main"
        private const val REQ = 100
        private const val INPUT_BLOCK_MS = 700L
        /** Display preview refresh interval (~30fps) while record mode is on. */
        private const val PREVIEW_FRAME_MS = 33L
        private const val WHISPER_FADE_IN_MS = 220L
        private const val WHISPER_FADE_OUT_MS = 400L
        private const val WHISPER_HOLD_DEFAULT_MS = 1800L
        private const val DOT_PULSE_MS = 700L
        const val ACTION_TOGGLE_PREVIEW = "com.example.rokidfinder.TOGGLE_PREVIEW"
        private val IGNORED_KEYS = setOf(
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_POWER,
            KeyEvent.KEYCODE_BACK,
        )
    }
}
