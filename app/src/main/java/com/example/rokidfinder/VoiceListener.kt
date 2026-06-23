package com.example.rokidfinder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import java.io.File
import java.util.concurrent.Executors

/**
 * Fully-offline speech recognition using Vosk.
 *
 *  - Model: bundled in assets/model-en-us, unpacked once to filesDir on first run.
 *  - Capture: 16 kHz mono PCM via AudioRecord, fed into a Vosk Recognizer.
 *  - One-shot: [listen] records for [windowMs] ms, returns the best transcript.
 *
 * Designed to live for the lifetime of [FinderService] (model load is slow,
 * so we do it once and reuse).
 */
class VoiceListener(private val context: Context) {

    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var model: Model? = null
    @Volatile private var loadFailed: Boolean = false

    /** Kick off the (slow) model load on a background thread. */
    fun preload() {
        executor.execute {
            try {
                StorageService.unpack(
                    context, MODEL_ASSET_DIR, MODEL_UNPACK_DIR,
                    { loaded -> model = loaded; Log.i(TAG, "vosk model ready") },
                    { ex -> loadFailed = true; Log.e(TAG, "vosk unpack failed", ex) }
                )
            } catch (t: Throwable) {
                // Fallback: model may already be unpacked from a prior run.
                try {
                    val dir = File(context.filesDir, MODEL_UNPACK_DIR)
                    if (dir.exists()) {
                        model = Model(dir.absolutePath)
                        Log.i(TAG, "vosk model loaded from cache")
                    } else {
                        loadFailed = true
                        Log.e(TAG, "vosk load failed and no cached model", t)
                    }
                } catch (t2: Throwable) {
                    loadFailed = true
                    Log.e(TAG, "vosk load fully failed", t2)
                }
            }
        }
    }

    fun isReady(): Boolean = model != null
    fun isFailed(): Boolean = loadFailed

    /**
     * Records the mic for [windowMs] and returns the recognized text (lowercase),
     * or null if no audio could be captured. Blocking — call on a background thread.
     */
    fun listen(windowMs: Long = 3000L): String? {
        val m = model ?: return null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "no RECORD_AUDIO permission")
            return null
        }

        val sampleRate = SAMPLE_RATE
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)
        val bufSize = minBuf * 2

        // Constrain Vosk to the handful of phrases we actually care about. With a
        // small vocabulary, the recognizer becomes far more accurate for short
        // tap-to-talk queries — anything else collapses to "[unk]".
        val recognizer = Recognizer(m, sampleRate.toFloat(), PHONE_GRAMMAR)
        // Try several audio sources — the glasses' HAL doesn't support all of them.
        val sources = intArrayOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.DEFAULT,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
        )
        var recorder: AudioRecord? = null
        var lastErr: Throwable? = null
        for (src in sources) {
            val r = try {
                AudioRecord(src, sampleRate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufSize)
            } catch (t: Throwable) {
                lastErr = t; null
            }
            if (r != null && r.state == AudioRecord.STATE_INITIALIZED) {
                Log.i(TAG, "AudioRecord opened with source=$src")
                recorder = r
                break
            }
            r?.release()
        }
        if (recorder == null) {
            Log.e(TAG, "AudioRecord could not open any source", lastErr)
            recognizer.close()
            return null
        }

        val buf = ShortArray(bufSize / 2)
        val end = System.currentTimeMillis() + windowMs
        var collected: String? = null
        var readErrors = 0
        var samplesRead = 0L
        try {
            recorder.startRecording()
            while (System.currentTimeMillis() < end) {
                val n = recorder.read(buf, 0, buf.size)
                if (n < 0) { readErrors++; if (readErrors > 20) break; continue }
                if (n == 0) continue
                samplesRead += n
                if (recognizer.acceptWaveForm(buf, n)) {
                    collected = mergeResult(collected, recognizer.result)
                }
            }
            collected = mergeResult(collected, recognizer.finalResult)
            Log.i(TAG, "listen done: samples=$samplesRead errors=$readErrors text='${collected ?: ""}'")
        } catch (t: Throwable) {
            Log.e(TAG, "record/recognize error", t)
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
            recognizer.close()
        }
        return collected?.trim()?.lowercase()
    }

    /** Vosk returns JSON like {"text":"..."}; extract and append. */
    private fun mergeResult(prev: String?, jsonStr: String?): String? {
        val piece = try {
            JSONObject(jsonStr ?: "").optString("text", "").trim()
        } catch (_: Throwable) { "" }
        if (piece.isEmpty()) return prev
        return if (prev.isNullOrBlank()) piece else "$prev $piece"
    }

    fun close() {
        executor.shutdown()
        runCatching { model?.close() }
        model = null
    }

    companion object {
        private const val TAG = "VoiceListener"
        private const val SAMPLE_RATE = 16000
        private const val MODEL_ASSET_DIR = "model-en-us"
        private const val MODEL_UNPACK_DIR = "model-en-us"

        // Vosk grammar (JSON array of allowed phrases). Constrained to the small
        // set of tracked items + a few intent prefixes — accuracy is far better
        // than free-form. "[unk]" so noise collapses to unknown rather than being
        // force-matched. Update both this list AND [TrackedItems.ALL] when adding.
        private const val PHONE_GRAMMAR = """[
"where is my phone","where's my phone","wheres my phone","find my phone","my phone","phone",
"where is my laptop","where's my laptop","wheres my laptop","find my laptop","my laptop","laptop",
"where is my cup","where's my cup","wheres my cup","find my cup","my cup","cup",
"[unk]"
]"""
    }
}
