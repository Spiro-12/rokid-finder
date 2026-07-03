package com.example.rokidfinder

import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max

/**
 * Derives spoken-answer context from a single frame's detections: which surface
 * the item sits ON and which objects it is NEAR. Pure box geometry — no extra
 * model, no extra inference. Runs once, at memory-save time, on the same frame
 * as the saved snapshot, so the words always match the photo.
 */
object SceneContext {

    /** [surface] is a COCO label ("dining table") or null; [near] is 0..MAX_NEAR labels. */
    data class Context(val surface: String?, val near: List<String>)

    val EMPTY = Context(null, emptyList())

    /**
     * COCO labels an item can plausibly rest ON. [ItemDetector.CONTEXT_LABELS]
     * is derived from this set so a label added here can never be silently
     * filtered out before we see it.
     */
    val SURFACE_LABELS: Set<String> = setOf("dining table", "couch", "bed", "chair", "sink")

    private const val MAX_NEAR = 2

    /**
     * Minimum score for a detection to be *spoken about*. Stricter than the
     * model floor ([ItemDetector.CONTEXT_THRESHOLD]) and than item visibility:
     * a wrong "next to the book" costs more trust than a missing one, so
     * context claims must clear a higher bar than the item itself.
     */
    private const val SPOKEN_THRESHOLD = 0.45f

    /** Neighbor center must be within this many item-widths of the item center. */
    private const val NEAR_FACTOR = 1.5f

    /**
     * Compute context for the tracked item [itemKey] out of [detections]
     * (one frame's worth, from [ItemDetector.detect]).
     */
    fun derive(itemKey: String, detections: List<ItemDetector.Det>): Context {
        val spec = TrackedItems.byKey(itemKey) ?: return EMPTY
        val itemCoco = spec.coco.lowercase()
        // The item's own box: best-scoring detection of its label this frame.
        val item = detections.filter { it.label == itemCoco }.maxByOrNull { it.score }
            ?: return EMPTY

        // Only detections confident enough to speak about qualify as context.
        val candidates = detections.filter { it.score >= SPOKEN_THRESHOLD }

        val surface = candidates
            .filter { it.label in SURFACE_LABELS && isOn(item.box, it.box) }
            .maxByOrNull { it.box.width() * it.box.height() }
            ?.label

        val itemSpan = max(item.box.width(), item.box.height())
        val near = candidates.asSequence()
            .filter { it !== item && it.label != itemCoco && it.label != surface }
            .filter { centerDistance(item.box, it.box) <= NEAR_FACTOR * itemSpan }
            // Tracked items ("your laptop") make better anchors than generic
            // objects; among peers, prefer the confident ones.
            .sortedWith(
                compareByDescending<ItemDetector.Det> { it.label in TrackedItems.COCO_LABELS }
                    .thenByDescending { it.score }
            )
            .map { it.label }
            .distinct()
            .take(MAX_NEAR)
            .toList()

        return Context(surface, near)
    }

    /**
     * "Item is ON surface": the item's horizontal center falls inside the
     * surface's horizontal span, and the item's bottom edge lies within the
     * surface's vertical span (allowing a small margin above the surface top —
     * a cup on a table often crops the table's box just below the rim).
     */
    private fun isOn(item: RectF, surface: RectF): Boolean {
        val cx = item.centerX()
        if (cx < surface.left || cx > surface.right) return false
        val margin = item.height() * 0.3f
        return item.bottom >= surface.top - margin && item.bottom <= surface.bottom
    }

    private fun centerDistance(a: RectF, b: RectF): Float {
        val dx = abs(a.centerX() - b.centerX())
        val dy = abs(a.centerY() - b.centerY())
        return max(dx, dy) // Chebyshev — cheap and direction-agnostic
    }
}
