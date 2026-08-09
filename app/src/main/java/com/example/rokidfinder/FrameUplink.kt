package com.example.rokidfinder

import android.graphics.Bitmap
import android.util.Log
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pushes camera frames to the Mac as `0x01` binary messages.
 *
 * Latest-frame-wins, deliberately: if OkHttp still has anything queued for the wire
 * we drop the new frame instead of adding to the queue. A stale frame is worse than
 * no frame — the brain would be reasoning about a scene the wearer already walked away
 * from — and an unbounded queue on a wearable is how the device dies. The sequence
 * number is monotonic across drops so the Mac can measure the real drop rate.
 *
 * Called from the camera analyzer thread. Everything here is cheap or non-blocking:
 * the rate limit and the queue check come first, the single JPEG encode only happens
 * for a frame we are actually going to send, and `WebSocket.send` hands off to OkHttp's
 * writer thread rather than touching the socket. No network I/O runs on the analyzer.
 */
class FrameUplink(private val link: LinkClient) {

    private val seq = AtomicInteger(0)
    private var lastSentMs: Long = 0L
    private var dropped: Int = 0
    private var lastDropLogMs: Long = 0L

    /**
     * Offer the current upright frame. Never takes ownership — the caller still owns
     * (and recycles) [bitmap].
     */
    fun offer(bitmap: Bitmap) {
        val ws = link.readySocket() ?: return
        val config = link.config

        val fps = config.frameFps
        if (fps <= 0.0) return // frame_fps: 0 means stop the uplink entirely

        val now = System.currentTimeMillis()
        val minGapMs = (1000.0 / fps).toLong()
        if (now - lastSentMs < minGapMs) return

        if (ws.queueSize() > 0) {
            noteDrop(now)
            return
        }

        val payload = try {
            encode(bitmap, config)
        } catch (t: Throwable) {
            Log.w(TAG, "frame encode failed", t)
            return
        } ?: return

        if (ws.send(payload.toByteString())) lastSentMs = now
    }

    /** Downscale (if needed) and encode exactly once: no decode/re-encode round trip. */
    private fun encode(bitmap: Bitmap, config: LinkClient.Config): ByteArray? {
        if (bitmap.isRecycled) return null

        val maxWidth = config.frameMaxWidth
        var scaled: Bitmap? = null
        val source = if (bitmap.width > maxWidth) {
            val h = (bitmap.height.toLong() * maxWidth / bitmap.width).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, maxWidth, h, true).also { scaled = it }
        } else {
            bitmap
        }

        val out = ByteArrayOutputStream(INITIAL_BUFFER)
        val n = seq.getAndIncrement()
        out.write(TAG_FRAME)
        out.write((n ushr 24) and 0xFF)
        out.write((n ushr 16) and 0xFF)
        out.write((n ushr 8) and 0xFF)
        out.write(n and 0xFF)
        val ok = source.compress(Bitmap.CompressFormat.JPEG, config.jpegQuality, out)
        scaled?.recycle() // null unless we made our own downscaled copy
        return if (ok) out.toByteArray() else null
    }

    private fun noteDrop(now: Long) {
        dropped++
        if (now - lastDropLogMs >= DROP_LOG_INTERVAL_MS) {
            Log.i(TAG, "backpressure: dropped $dropped frames")
            dropped = 0
            lastDropLogMs = now
        }
    }

    private companion object {
        const val TAG = "RokidFinder.Uplink"
        /** Binary tag byte for a camera frame (protocol §4). */
        const val TAG_FRAME = 0x01
        const val INITIAL_BUFFER = 64 * 1024
        const val DROP_LOG_INTERVAL_MS = 10_000L
    }
}
