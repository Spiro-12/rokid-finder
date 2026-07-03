package com.example.rokidfinder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.Detection
import org.tensorflow.lite.task.vision.detector.ObjectDetector

/**
 * Multi-item detector. Wraps the same EfficientDet-Lite0 (COCO) we used in PART 1
 * and reports every detection we care about — tracked items plus scene-context
 * objects (surfaces, neighbors) — with bounding boxes, so [FinderService] can both
 * run its per-item visibility state machines and derive "on the dining table,
 * next to your laptop" context via [SceneContext] at save time.
 *
 * The model runs at [CONTEXT_THRESHOLD]; the stricter per-item visibility cut
 * ([SCORE_THRESHOLD]) is applied by FinderService so context objects can ride
 * along at slightly lower confidence without loosening item tracking.
 */
class ItemDetector(context: Context) {

    /** One detection this frame. [box] is in source-bitmap pixel coordinates. */
    data class Det(val label: String, val score: Float, val box: RectF)

    private val detector: ObjectDetector = ObjectDetector.createFromFileAndOptions(
        context,
        MODEL_ASSET,
        ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(BaseOptions.builder().setNumThreads(2).build())
            .setMaxResults(15)
            .setScoreThreshold(CONTEXT_THRESHOLD)
            .build()
    )

    /**
     * Returns all detections whose label is a tracked item or a context object,
     * best-scoring category per detection box.
     */
    fun detect(bitmap: Bitmap): List<Det> {
        val input = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap
                    else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val results: List<Detection> = detector.detect(TensorImage.fromBitmap(input))
        val out = mutableListOf<Det>()
        for (det in results) {
            for (cat in det.categories) {
                val label = cat.label.lowercase()
                if (label in TrackedItems.COCO_LABELS || label in CONTEXT_LABELS) {
                    out += Det(label, cat.score, RectF(det.boundingBox))
                }
            }
        }
        if (input !== bitmap) input.recycle()
        return out
    }

    fun close() {
        runCatching { detector.close() }.onFailure { Log.w(TAG, "detector close", it) }
    }

    companion object {
        private const val TAG = "ItemDetector"
        private const val MODEL_ASSET = "efficientdet_lite0.tflite"

        /** Visibility cut for tracked items (unchanged from Phase 1). */
        const val SCORE_THRESHOLD = 0.40f

        /** Model floor — context objects are useful at slightly lower confidence. */
        const val CONTEXT_THRESHOLD = 0.35f

        /**
         * COCO labels that make good "next to your laptop"-style anchors.
         * Chosen for (a) EfficientDet-Lite0 detecting them reliably and
         * (b) being speakable in a "where is it" answer without sounding odd.
         */
        private val NEIGHBOR_LABELS: Set<String> = setOf(
            "tv", "keyboard", "mouse", "book", "bottle",
            "refrigerator", "backpack", "handbag",
        )

        /**
         * All scene-context labels the detector keeps. Unioned with
         * [SceneContext.SURFACE_LABELS] so a surface added there can never be
         * silently filtered out here before SceneContext gets to see it.
         */
        val CONTEXT_LABELS: Set<String> = NEIGHBOR_LABELS + SceneContext.SURFACE_LABELS

        /** [detections] → per-tracked-COCO-label best confidence (missing = absent). */
        fun bestByTrackedLabel(detections: List<Det>): Map<String, Float> {
            val out = mutableMapOf<String, Float>()
            for (d in detections) {
                if (d.label in TrackedItems.COCO_LABELS) {
                    val cur = out[d.label] ?: 0f
                    if (d.score > cur) out[d.label] = d.score
                }
            }
            return out
        }
    }
}
