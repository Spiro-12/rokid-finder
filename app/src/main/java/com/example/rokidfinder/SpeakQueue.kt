package com.example.rokidfinder

import android.util.Log

/**
 * Serializes everything the Mac asks the glasses to say through [Tts].
 *
 * Speech is the primary channel on this device — the display is optical see-through
 * and easy to miss, the ear is not — so utterances must not overlap or be silently
 * dropped. One worker thread owns the ordering; callers (the OkHttp reader thread)
 * never block.
 *
 * Priority: an `urgent` item (a safety alert) jumps the queue *and* cuts off whatever
 * is currently speaking. `normal` items (answers, reminders) queue behind it.
 *
 * Piper gives no "finished speaking" callback, so the worker waits out an estimate
 * derived from word count. The estimate only has to be roughly right: overshooting
 * adds a small gap, and an urgent item wakes the worker immediately regardless.
 */
class SpeakQueue {

    private data class Item(val text: String, val urgent: Boolean)

    private val lock = Object()
    private val queue = ArrayDeque<Item>()
    private var running = true

    private val worker = Thread({ loop() }, "SpeakQueue").apply {
        isDaemon = true
        start()
    }

    /** [priority] is the protocol's "normal" | "urgent"; anything else is treated as normal. */
    fun offer(text: String, priority: String?) {
        if (text.isBlank()) return
        val urgent = priority == "urgent"
        synchronized(lock) {
            if (!running) return
            if (urgent) queue.addFirst(Item(text, true)) else queue.addLast(Item(text, false))
            if (queue.size > MAX_QUEUE) queue.removeLast()
            lock.notifyAll()
        }
    }

    fun shutdown() {
        synchronized(lock) {
            running = false
            queue.clear()
            lock.notifyAll()
        }
        worker.interrupt()
    }

    private fun loop() {
        while (true) {
            val item: Item
            synchronized(lock) {
                while (running && queue.isEmpty()) {
                    try {
                        lock.wait()
                    } catch (_: InterruptedException) {
                        return
                    }
                }
                if (!running) return
                item = queue.removeFirst()
            }

            Log.i(TAG, "speak(${if (item.urgent) "urgent" else "normal"}): ${item.text}")
            // Tts.speak() flushes the AudioTrack, so this is also the interrupt.
            runCatching { Tts.speak(item.text) }

            // Hold the floor for roughly as long as the phrase takes, unless an
            // urgent item arrives — then wake early and pre-empt.
            val until = System.currentTimeMillis() + estimateMs(item.text)
            synchronized(lock) {
                while (running) {
                    val remaining = until - System.currentTimeMillis()
                    if (remaining <= 0) break
                    if (queue.firstOrNull()?.urgent == true) break
                    try {
                        lock.wait(remaining)
                    } catch (_: InterruptedException) {
                        return
                    }
                }
                if (!running) return
            }
        }
    }

    private fun estimateMs(text: String): Long {
        val words = text.split(Regex("\\s+")).count { it.isNotBlank() }.coerceAtLeast(1)
        return (words * MS_PER_WORD + TAIL_MS).coerceIn(MIN_MS, MAX_MS)
    }

    private companion object {
        const val TAG = "RokidFinder.SpeakQueue"
        const val MAX_QUEUE = 8
        /** ~165 wpm, measured against the bundled en_US-amy-low voice. */
        const val MS_PER_WORD = 364L
        const val TAIL_MS = 500L
        const val MIN_MS = 800L
        const val MAX_MS = 20_000L
    }
}
