package com.example.rokidfinder

import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen "where is my phone?" answer: the last-seen photo + "X min ago".
 * Any key/tap dismisses back to the watching HUD.
 */
class RecallActivity : AppCompatActivity() {

    private lateinit var photo: ImageView
    private lateinit var empty: TextView
    private lateinit var ago: TextView
    private lateinit var at: TextView
    private lateinit var store: LastSeenStore

    private val timeFmt = SimpleDateFormat("h:mm a", Locale.US)
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 10_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_recall)
        photo = findViewById(R.id.photo)
        empty = findViewById(R.id.empty)
        ago = findViewById(R.id.ago)
        at = findViewById(R.id.at)
        store = LastSeenStore(this)
        refresh()
    }

    private var inputBlockedUntilMs: Long = 0L

    override fun onResume() {
        super.onResume()
        // Touchpad emits two keycodes per tap (83 then 66, ~540ms apart). Swallow the
        // trailing one so it doesn't immediately dismiss the screen we just opened.
        inputBlockedUntilMs = System.currentTimeMillis() + INPUT_BLOCK_MS
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    /** Any key dismisses. Log it so we know which physical control fired. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        Log.i(TAG, "recall onKeyDown keycode=$keyCode")
        if (System.currentTimeMillis() < inputBlockedUntilMs) return true
        if (keyCode == KeyEvent.KEYCODE_BACK || isInputKey(keyCode)) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun isInputKey(keyCode: Int): Boolean = keyCode !in IGNORED_KEYS

    private fun refresh() {
        val ts = store.timestampMs()
        if (ts == 0L) {
            photo.setImageDrawable(null)
            empty.visibility = android.view.View.VISIBLE
            ago.text = ""
            at.text = ""
            return
        }
        empty.visibility = android.view.View.GONE
        store.photoPath()?.let { path ->
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            val bmp = BitmapFactory.decodeFile(path, opts)
            if (bmp != null) photo.setImageBitmap(bmp)
        }
        ago.text = humanAgo(ts)
        at.text = "at ${timeFmt.format(Date(ts))}"
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

    companion object {
        private const val TAG = "RokidFinder.Recall"
        private const val INPUT_BLOCK_MS = 700L
        private val IGNORED_KEYS = setOf(
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_POWER,
        )
    }
}
