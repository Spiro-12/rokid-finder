package com.example.rokidfinder

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.Detection
import org.tensorflow.lite.task.vision.detector.ObjectDetector

/**
 * On-device "cell phone" detector. EfficientDet-Lite0 (int8) ships in assets/
 * and uses COCO labels — we only care about the "cell phone" class.
 */
class PhoneDetector(context: Context) {

    private val detector: ObjectDetector = ObjectDetector.createFromFileAndOptions(
        context,
        MODEL_ASSET,
        ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(BaseOptions.builder().setNumThreads(2).build())
            .setMaxResults(10)
            .setScoreThreshold(SCORE_THRESHOLD)
            .build()
    )

    data class Result(val visible: Boolean, val confidence: Float)

    fun detect(bitmap: Bitmap): Result {
        // TFLite Task Vision needs ARGB_8888; CameraFrameSource gives us that already.
        val input = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap
                    else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val results: List<Detection> = detector.detect(TensorImage.fromBitmap(input))
        var best = 0f
        for (det in results) {
            for (cat in det.categories) {
                if (cat.label.equals(TARGET_LABEL, ignoreCase = true) && cat.score > best) {
                    best = cat.score
                }
            }
        }
        if (input !== bitmap) input.recycle()
        return Result(visible = best >= SCORE_THRESHOLD, confidence = best)
    }

    fun close() {
        runCatching { detector.close() }.onFailure { Log.w(TAG, "detector close", it) }
    }

    companion object {
        private const val TAG = "PhoneDetector"
        private const val MODEL_ASSET = "efficientdet_lite0.tflite"
        private const val TARGET_LABEL = "cell phone"
        private const val SCORE_THRESHOLD = 0.40f
    }
}
