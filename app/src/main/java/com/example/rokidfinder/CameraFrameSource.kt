package com.example.rokidfinder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * Binds CameraX to a LifecycleOwner, pulls YUV frames, converts each to an upright
 * Bitmap, and pushes it to [onFrame]. YUV/NV21/rotation handling is borrowed verbatim
 * from RokidCam's CameraStreamer because the Rokid sensor's reported rotation is
 * unreliable (a fixed 270° correction is what actually works).
 */
class CameraFrameSource(
    private val context: Context,
    private val lensFacing: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA,
    private val targetSize: Size = Size(640, 480),
    private val rotationDegrees: Int = 270,
    private val onFrame: (Bitmap) -> Unit,
) {
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null

    fun start(owner: LifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider

                val resolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            targetSize,
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                        )
                    )
                    .build()

                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()

                analysis.setAnalyzer(analysisExecutor) { image ->
                    try {
                        val bmp = imageToBitmap(image)
                        if (bmp != null) onFrame(bmp)
                    } catch (e: Exception) {
                        Log.e(TAG, "frame convert failed", e)
                    } finally {
                        image.close()
                    }
                }

                val selector = runCatching {
                    if (provider.hasCamera(lensFacing)) lensFacing
                    else CameraSelector.DEFAULT_FRONT_CAMERA
                }.getOrDefault(lensFacing)

                provider.unbindAll()
                provider.bindToLifecycle(owner, selector, analysis)
                Log.i(TAG, "camera bound ($selector) at $targetSize")
            } catch (e: Exception) {
                Log.e(TAG, "camera start failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        analysisExecutor.shutdown()
    }

    private fun imageToBitmap(image: ImageProxy): Bitmap? {
        val nv21 = yuv420ToNv21(image)
        val yuv = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, image.width, image.height), 85, out)
        val jpeg = out.toByteArray()
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return null
        if (rotationDegrees == 0) return bmp
        val m = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (rotated !== bmp) bmp.recycle()
        return rotated
    }

    private fun yuv420ToNv21(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val nv21 = ByteArray(ySize + ySize / 2)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBytes = ByteArray(yPlane.buffer.remaining()).also { yPlane.buffer.get(it) }
        val uBytes = ByteArray(uPlane.buffer.remaining()).also { uPlane.buffer.get(it) }
        val vBytes = ByteArray(vPlane.buffer.remaining()).also { vPlane.buffer.get(it) }

        var pos = 0
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        for (row in 0 until height) {
            val rowStart = row * yRowStride
            if (yPixelStride == 1) {
                val n = minOf(width, yBytes.size - rowStart).coerceAtLeast(0)
                if (n > 0) System.arraycopy(yBytes, rowStart, nv21, pos, n)
                pos += width
            } else {
                for (col in 0 until width) {
                    val idx = rowStart + col * yPixelStride
                    nv21[pos++] = if (idx < yBytes.size) yBytes[idx] else 0
                }
            }
        }

        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride
        val chromaHeight = height / 2
        val chromaWidth = width / 2
        for (row in 0 until chromaHeight) {
            val rowStart = row * uvRowStride
            for (col in 0 until chromaWidth) {
                val uvIndex = rowStart + col * uvPixelStride
                nv21[pos++] = if (uvIndex < vBytes.size) vBytes[uvIndex] else 0
                nv21[pos++] = if (uvIndex < uBytes.size) uBytes[uvIndex] else 0
            }
        }
        return nv21
    }

    companion object {
        private const val TAG = "CameraFrameSource"
    }
}
