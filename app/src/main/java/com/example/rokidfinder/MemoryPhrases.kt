package com.example.rokidfinder

/**
 * Single source of truth for turning a [MemoryStore.Memory] into words — the
 * spoken answer and the on-screen captions compose from the same structured
 * fields, so eyes and ears always agree.
 *
 * Target shape (fields degrade gracefully when absent):
 *   "Your phone, on the dining table, next to your laptop,
 *    in a coffee shop, five minutes ago."
 */
object MemoryPhrases {

    /** Full spoken answer for TTS. Numbers come out as words — Piper reads them better. */
    fun spoken(m: MemoryStore.Memory, nowMs: Long = System.currentTimeMillis()): String {
        val display = TrackedItems.byKey(m.item)?.display ?: m.item
        val parts = mutableListOf("Your ${display.lowercase()}")
        contextClause(m.surface, m.near, m.place)?.let { parts += it }
        parts += agoSpoken(m.tsMs, nowMs)
        return parts.joinToString(", ")
    }

    /**
     * The middle of the sentence — "on the dining table, next to your laptop,
     * in a coffee shop" — or null when there's no context at all.
     */
    fun contextClause(surface: String?, near: List<String>, place: String?): String? {
        val parts = mutableListOf<String>()
        surface?.let { parts += "on the $it" }
        if (near.isNotEmpty()) {
            parts += "next to " + near.joinToString(" and ") { spokenLabel(it) }
        }
        place?.let { parts += "in a $it" }
        return if (parts.isEmpty()) null else parts.joinToString(", ")
    }

    /** Caption under a recall photo: context (if any) + relative time. */
    fun caption(m: MemoryStore.Memory, nowMs: Long = System.currentTimeMillis()): String {
        val ago = agoDisplay(m.tsMs, nowMs)
        val context = contextClause(m.surface, m.near, m.place)
            ?.replaceFirstChar { it.titlecase() }
        return if (context != null) "$context · $ago" else ago
    }

    /** Tracked items are "your laptop"; everything else is "the keyboard". */
    private fun spokenLabel(cocoLabel: String): String {
        val tracked = TrackedItems.byCocoLabel(cocoLabel)
        return if (tracked != null) "your ${tracked.display.lowercase()}" else "the $cocoLabel"
    }

    /**
     * The single threshold ladder both renderings share — the screen and the
     * voice must always bucket a timestamp identically.
     */
    private sealed class Ago {
        object JustNow : Ago()
        object Yesterday : Ago()
        class Minutes(val n: Long) : Ago()
        class Hours(val n: Long) : Ago()
        class Days(val n: Long) : Ago()
    }

    private fun agoBucket(tsMs: Long, nowMs: Long): Ago {
        val s = (nowMs - tsMs) / 1000
        return when {
            s < 60 -> Ago.JustNow
            s < 3600 -> Ago.Minutes(s / 60)
            s < 86_400 -> Ago.Hours(s / 3600)
            s < 172_800 -> Ago.Yesterday
            else -> Ago.Days(s / 86_400)
        }
    }

    /** Relative time for screens — digits are fine to read. */
    fun agoDisplay(tsMs: Long, nowMs: Long = System.currentTimeMillis()): String =
        when (val b = agoBucket(tsMs, nowMs)) {
            Ago.JustNow -> "Just now"
            is Ago.Minutes -> if (b.n == 1L) "1 minute ago" else "${b.n} minutes ago"
            is Ago.Hours -> if (b.n == 1L) "1 hour ago" else "${b.n} hours ago"
            Ago.Yesterday -> "Yesterday"
            is Ago.Days -> "${b.n} days ago"
        }

    /** Relative time for TTS — "five minutes ago" reads better than "5". */
    private fun agoSpoken(tsMs: Long, nowMs: Long): String =
        when (val b = agoBucket(tsMs, nowMs)) {
            Ago.JustNow -> "just now"
            is Ago.Minutes -> if (b.n == 1L) "one minute ago" else "${numberWord(b.n)} minutes ago"
            is Ago.Hours -> if (b.n == 1L) "one hour ago" else "${numberWord(b.n)} hours ago"
            Ago.Yesterday -> "yesterday"
            is Ago.Days -> "${numberWord(b.n)} days ago"
        }

    private val ONES = arrayOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight",
        "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen",
        "sixteen", "seventeen", "eighteen", "nineteen",
    )
    private val TENS = arrayOf("", "", "twenty", "thirty", "forty", "fifty")

    /** 0..59 in words — all we ever need for minutes/hours/days here. */
    private fun numberWord(n: Long): String = when {
        n < 20 -> ONES[n.toInt()]
        n < 60 -> {
            val t = TENS[(n / 10).toInt()]
            val r = (n % 10).toInt()
            if (r == 0) t else "$t-${ONES[r]}"
        }
        else -> n.toString()
    }
}
