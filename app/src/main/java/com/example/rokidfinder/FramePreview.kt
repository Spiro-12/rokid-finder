package com.example.rokidfinder

import android.graphics.Bitmap

/**
 * One-slot hand-off of the latest camera frame from [FinderService] (producer,
 * camera thread) to [MainActivity] (consumer, UI thread), used only to mirror
 * what the camera sees onto the monochrome display for screen recording.
 *
 * Off by default: when [enabled] is false the service never publishes, so this
 * costs nothing during normal wear. The display is optical see-through, so the
 * camera image is otherwise never drawn — this is the bridge that lets a screen
 * recording (e.g. scrcpy) capture the scene as well as the HUD overlay.
 *
 * Ownership: [publish] takes a bitmap the service no longer needs and recycles
 * any previous frame that was never taken; [take] transfers ownership to the
 * caller (which hands it to an ImageView). Nothing recycles a taken frame.
 */
object FramePreview {

    @Volatile
    var enabled: Boolean = false

    private var slot: Bitmap? = null

    @Synchronized
    fun publish(scaled: Bitmap) {
        if (!enabled) {
            scaled.recycle()
            return
        }
        // Previous frame was never consumed; safe to recycle (no view holds it).
        slot?.recycle()
        slot = scaled
    }

    /** Latest frame, ownership transferred to the caller, or null if none new. */
    @Synchronized
    fun take(): Bitmap? {
        val b = slot
        slot = null
        return b
    }

    @Synchronized
    fun clear() {
        slot?.recycle()
        slot = null
    }
}
