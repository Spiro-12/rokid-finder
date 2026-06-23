package com.example.rokidfinder

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.Detection
import org.tensorflow.lite.task.vision.detector.ObjectDetector

/**
 * Multi-item detector. Wraps the same EfficientDet-Lite0 (COCO) we used in PART 1
 * and reports the max confidence we saw for each [TrackedItems] COCO label this
 * frame. The visibility flag for an individual item is decided by [FinderService]
 * using its own per-item threshold so the model itself stays vocabulary-agnostic.
 */
class ItemDetector(context: Context) {

    private val detector: ObjectDetector = ObjectDetector.createFromFileAndOptions(
        context,
        MODEL_ASSET,
        ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(BaseOptions.builder().setNumThreads(2).build())
            .setMaxResults(15)
            .setScoreThreshold(SCORE_THRESHOLD)
            .build()
    )

    /** Returns [coco-label-lowercase → best confidence this frame]. Missing labels = 0. */
    fun detect(bitmap: Bitmap): Map<String, Float> {
        val input = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap
                    else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val results: List<Detection> = detector.detect(TensorImage.fromBitmap(input))
        val out = mutableMapOf<String, Float>()
        for (det in results) {
            for (cat in det.categories) {
                val label = cat.label.lowercase()
                if (label in TrackedItems.COCO_LABELS) {
                    val cur = out[label] ?: 0f
                    if (cat.score > cur) out[label] = cat.score
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
        const val SCORE_THRESHOLD = 0.40f
    }
}
