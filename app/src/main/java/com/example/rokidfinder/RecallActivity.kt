package com.example.rokidfinder

import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen recall: scrollable list of memories — one row per tracked item
 * that has been seen at least once. If [EXTRA_FOCUS_ITEM] is set (e.g. from a
 * voice query), that item's row is rendered first. Any non-system key dismisses.
 */
class RecallActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private lateinit var empty: TextView
    private lateinit var hint: TextView
    private lateinit var store: MemoryStore

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
        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        hint = findViewById(R.id.hint)
        store = MemoryStore(this)
        refresh()
    }

    private var inputBlockedUntilMs: Long = 0L

    override fun onResume() {
        super.onResume()
        // Same touchpad debounce trick as MainActivity: the temple tap emits two
        // keycodes ~540 ms apart; swallow the trailing one so we don't dismiss
        // the moment we appear.
        inputBlockedUntilMs = System.currentTimeMillis() + INPUT_BLOCK_MS
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    /** Any key dismisses. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        Log.i(TAG, "recall onKeyDown keycode=$keyCode")
        if (System.currentTimeMillis() < inputBlockedUntilMs) return true
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode !in IGNORED_KEYS) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun refresh() {
        // Strip out everything between the title and the hint, then rebuild.
        while (list.childCount > 2) list.removeViewAt(1)

        val focusKey = intent.getStringExtra(EXTRA_FOCUS_ITEM)
        val memories = store.all().sortedWith(compareByDescending<MemoryStore.Memory> {
            it.item == focusKey
        }.thenByDescending { it.tsMs })

        if (memories.isEmpty()) {
            empty.visibility = View.VISIBLE
            hint.text = "tap to return"
            return
        }
        empty.visibility = View.GONE

        val inflater = LayoutInflater.from(this)
        for (m in memories) {
            val row = inflater.inflate(R.layout.memory_row, list, false)
            val display = TrackedItems.byKey(m.item)?.display ?: m.item
            row.findViewById<TextView>(R.id.itemName).text = display
            row.findViewById<TextView>(R.id.whenLine).text =
                "${humanAgo(m.tsMs)} · at ${timeFmt.format(Date(m.tsMs))}"
            val photo = row.findViewById<ImageView>(R.id.photo)
            val path = m.snapshotPath
            if (path != null) {
                // Down-sample so we don't stash full-res bitmaps in the view.
                val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                val bmp = BitmapFactory.decodeFile(path, opts)
                if (bmp != null) photo.setImageBitmap(bmp) else photo.setImageDrawable(null)
            } else {
                photo.setImageDrawable(null)
            }
            // Insert before the hint TextView (the last child).
            list.addView(row, list.childCount - 1)
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

    companion object {
        private const val TAG = "RokidFinder.Recall"
        private const val INPUT_BLOCK_MS = 700L
        const val EXTRA_FOCUS_ITEM = "focusItemKey"
        private val IGNORED_KEYS = setOf(
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_POWER,
        )
    }
}
