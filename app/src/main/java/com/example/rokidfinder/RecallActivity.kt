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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Recall has two modes:
 *  - HERO answer (EXTRA_FOCUS_ITEM set, i.e. came from a voice query): full-bleed
 *    photo of where the item was last seen, with one big caption. The photo IS
 *    the answer. Auto-dismisses after [HERO_AUTODISMISS_MS] or any tap.
 *  - BROWSE list (no focus): calm photo cards, one human caption each. Swipe to
 *    scroll, tap to dismiss.
 */
class RecallActivity : AppCompatActivity() {

    private lateinit var hero: FrameLayout
    private lateinit var heroPhoto: ImageView
    private lateinit var heroName: TextView
    private lateinit var heroWhen: TextView
    private lateinit var scroller: ScrollView
    private lateinit var list: LinearLayout
    private lateinit var empty: TextView
    private lateinit var store: MemoryStore

    private val handler = Handler(Looper.getMainLooper())
    private val autoDismiss = Runnable { finish() }

    /** Hero-mode state: the full memory list and which one we're showing. */
    private var heroMemories: List<MemoryStore.Memory> = emptyList()
    private var heroIndex: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_recall)
        hero = findViewById(R.id.hero)
        heroPhoto = findViewById(R.id.heroPhoto)
        heroName = findViewById(R.id.heroName)
        heroWhen = findViewById(R.id.heroWhen)
        scroller = findViewById(R.id.scroller)
        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        store = MemoryStore(this)
        render()
    }

    private var inputBlockedUntilMs: Long = 0L

    override fun onResume() {
        super.onResume()
        // Same touchpad debounce trick as MainActivity: the temple tap emits two
        // keycodes ~540 ms apart; swallow the trailing one so we don't dismiss
        // the moment we appear.
        inputBlockedUntilMs = System.currentTimeMillis() + INPUT_BLOCK_MS
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(autoDismiss)
    }

    /**
     * Touchpad routing:
     *  - In hero mode, any input dismisses.
     *  - In browse mode, swipes scroll; tap/back dismiss.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        Log.i(TAG, "recall onKeyDown keycode=$keyCode")
        if (System.currentTimeMillis() < inputBlockedUntilMs) return true
        if (hero.visibility == View.VISIBLE) {
            return when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_PAGE_UP -> { stepHero(-1); true }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_PAGE_DOWN -> { stepHero(+1); true }
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_BACK -> { finish(); true }
                in IGNORED_KEYS -> super.onKeyDown(keyCode, event)
                else -> true
            }
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_PAGE_UP -> {
                scroller.smoothScrollBy(0, -SCROLL_STEP_PX); true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_PAGE_DOWN -> {
                scroller.smoothScrollBy(0, SCROLL_STEP_PX); true
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_BACK -> { finish(); true }
            in IGNORED_KEYS -> super.onKeyDown(keyCode, event)
            else -> true
        }
    }

    private fun render() {
        val focusKey = intent.getStringExtra(EXTRA_FOCUS_ITEM)
        val all = store.all().sortedByDescending { it.tsMs }
        val startIdx = focusKey?.let { key -> all.indexOfFirst { it.item == key } } ?: -1
        if (startIdx >= 0) {
            heroMemories = all
            heroIndex = startIdx
            showHeroAt(heroIndex)
            // Phase 2: speak the answer alongside the photo. Only on the initial
            // voice-driven entry — manual swipe-through is silent so the user
            // isn't talked at every time they glance.
            speakHero(heroMemories[heroIndex])
        } else {
            renderBrowse()
        }
    }

    /** Compose and speak a one-line answer for [m]. */
    private fun speakHero(m: MemoryStore.Memory) {
        val display = TrackedItems.byKey(m.item)?.display ?: m.item
        val phrase = "Your ${display.lowercase()}, ${humanAgo(m.tsMs).lowercase()}"
        Tts.speak(phrase)
    }

    private fun showHeroAt(index: Int) {
        val m = heroMemories.getOrNull(index) ?: return
        hero.visibility = View.VISIBLE
        scroller.visibility = View.GONE
        val display = TrackedItems.byKey(m.item)?.display ?: m.item
        heroName.text = display.replaceFirstChar { it.titlecase() }
        heroWhen.text = humanAgo(m.tsMs)
        val path = m.snapshotPath
        heroPhoto.setImageDrawable(null)
        if (path != null) {
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            val bmp = BitmapFactory.decodeFile(path, opts)
            if (bmp != null) heroPhoto.setImageBitmap(bmp)
        }
        handler.removeCallbacks(autoDismiss)
        handler.postDelayed(autoDismiss, HERO_AUTODISMISS_MS)
    }

    /** Cycle hero index by [delta], wrapping at both ends. Resets auto-dismiss. */
    private fun stepHero(delta: Int) {
        if (heroMemories.size <= 1) return
        val n = heroMemories.size
        heroIndex = ((heroIndex + delta) % n + n) % n
        showHeroAt(heroIndex)
    }

    private fun renderBrowse() {
        hero.visibility = View.GONE
        scroller.visibility = View.VISIBLE

        // Keep only the `empty` placeholder; rebuild the rest.
        while (list.childCount > 1) list.removeViewAt(list.childCount - 1)

        val memories = store.all().sortedByDescending { it.tsMs }
        if (memories.isEmpty()) {
            empty.visibility = View.VISIBLE
            return
        }
        empty.visibility = View.GONE

        val inflater = LayoutInflater.from(this)
        for (m in memories) {
            val row = inflater.inflate(R.layout.memory_row, list, false)
            val display = TrackedItems.byKey(m.item)?.display ?: m.item
            row.findViewById<TextView>(R.id.itemName).text =
                display.replaceFirstChar { it.titlecase() }
            row.findViewById<TextView>(R.id.whenLine).text = humanAgo(m.tsMs)
            val photo = row.findViewById<ImageView>(R.id.photo)
            val path = m.snapshotPath
            if (path != null) {
                val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                val bmp = BitmapFactory.decodeFile(path, opts)
                if (bmp != null) photo.setImageBitmap(bmp) else photo.setImageDrawable(null)
            } else {
                photo.setImageDrawable(null)
            }
            list.addView(row)
        }
    }

    private fun humanAgo(tsMs: Long): String {
        val s = (System.currentTimeMillis() - tsMs) / 1000
        return when {
            s < 60 -> "Just now"
            s < 120 -> "1 minute ago"
            s < 3600 -> "${s / 60} minutes ago"
            s < 7200 -> "1 hour ago"
            s < 86_400 -> "${s / 3600} hours ago"
            s < 172_800 -> "Yesterday"
            else -> "${s / 86_400} days ago"
        }
    }

    companion object {
        private const val TAG = "RokidFinder.Recall"
        private const val INPUT_BLOCK_MS = 700L
        private const val SCROLL_STEP_PX = 240
        private const val HERO_AUTODISMISS_MS = 8000L
        const val EXTRA_FOCUS_ITEM = "focusItemKey"
        private val IGNORED_KEYS = setOf(
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_POWER,
        )
    }
}
