package com.example.rokidfinder

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.getOfflineTtsConfig
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * Process-global speech output via on-device Piper VITS (sherpa-onnx).
 *
 * The Rokid Glasses ship YodaOS without a system TTS engine (Android's
 * built-in TextToSpeech returned status=-1), so we bundle our own:
 *  - sherpa-onnx-jni native libs in jniLibs/arm64-v8a/ (~34 MB)
 *  - vits-piper-en_US-amy-low int8 voice in assets/ (~36 MB)
 *
 * Init takes a couple of seconds on first start; we kick it off from
 * [MainActivity.onCreate] so the engine is warm before the first answer.
 * Synthesis streams FloatArray samples into an [AudioTrack] via the callback
 * API, so playback begins before the full waveform is generated.
 */
object Tts {
    private const val TAG = "RokidFinder.Tts"
    private const val MODEL_DIR = "vits-piper-en_US-amy-low-int8"
    private const val MODEL_NAME = "en_US-amy-low.onnx"
    private const val SPEAKER_ID = 0
    private const val SPEED = 1.0f

    @Volatile private var engine: OfflineTts? = null
    /** Set once the load is scheduled — see [ensureInit] for why this exists. */
    @Volatile private var initStarted: Boolean = false
    @Volatile private var track: AudioTrack? = null
    @Volatile private var stopRequested: Boolean = false
    private val executor = Executors.newSingleThreadExecutor()

    /**
     * Kick off heavy init on a background thread. Idempotent.
     *
     * The guard is [initStarted], not `engine != null`: the load is asynchronous,
     * so `engine` stays null for seconds after the first call. Two callers racing
     * that window (MainActivity and FinderService both warm the engine) would each
     * load a full Piper model and AudioTrack, doubling ~36 MB of model plus native
     * memory on a device with none to spare, and leaking the first track.
     */
    @Synchronized
    fun ensureInit(context: Context) {
        if (initStarted) return
        initStarted = true
        val app = context.applicationContext
        executor.execute {
            try {
                // Piper's phonemizer needs espeak-ng-data on disk — it cannot
                // read from Android's asset manager. Copy on first run; skip
                // afterwards. Cost: ~18 MB once.
                val espeakDir = ensureEspeakDataOnDisk(app)

                val config = getOfflineTtsConfig(
                    modelDir = MODEL_DIR,
                    modelName = MODEL_NAME,
                    acousticModelName = "",
                    vocoder = "",
                    voices = "",
                    lexicon = "",
                    dataDir = espeakDir.absolutePath,
                    dictDir = "",
                    ruleFsts = "",
                    ruleFars = "",
                )
                val tts = OfflineTts(assetManager = app.assets, config = config)
                initAudioTrack(tts.sampleRate())
                engine = tts
                Log.i(TAG, "Sherpa-onnx Piper ready (sampleRate=${tts.sampleRate()})")
            } catch (t: Throwable) {
                // Allow a later retry rather than leaving the glasses mute forever.
                initStarted = false
                Log.e(TAG, "TTS init failed", t)
            }
        }
    }

    /**
     * Copy `vits-piper-…/espeak-ng-data` out of assets into [Context.getFilesDir]
     * on first run and return the on-disk directory. Subsequent runs see the
     * sentinel file and skip the copy (slow — hundreds of small files).
     */
    private fun ensureEspeakDataOnDisk(context: Context): File {
        val targetRoot = File(context.filesDir, "espeak-ng-data")
        val sentinel = File(targetRoot, ".unpacked")
        if (sentinel.exists()) return targetRoot
        Log.i(TAG, "Unpacking espeak-ng-data to ${targetRoot.absolutePath} (first run, ~18 MB)")
        targetRoot.mkdirs()
        copyAssetDir(context, "$MODEL_DIR/espeak-ng-data", targetRoot)
        sentinel.writeText("1")
        Log.i(TAG, "espeak-ng-data unpacked")
        return targetRoot
    }

    private fun copyAssetDir(context: Context, assetPath: String, destDir: File) {
        val entries = context.assets.list(assetPath) ?: return
        if (entries.isEmpty()) {
            // Leaf: copy as a file.
            destDir.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                FileOutputStream(destDir).use { output -> input.copyTo(output) }
            }
            return
        }
        destDir.mkdirs()
        for (name in entries) {
            copyAssetDir(context, "$assetPath/$name", File(destDir, name))
        }
    }

    private fun initAudioTrack(sampleRate: Int) {
        val bufLength = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        val attr = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setSampleRate(sampleRate)
            .build()
        val t = AudioTrack(
            attr, format, bufLength, AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        t.play()
        track = t
    }

    /**
     * Sherpa calls this with each generated chunk. Returning 1 keeps generating,
     * 0 aborts — we honour [stopRequested] so a rapid second speech request can
     * cut the current one off (see [speak]).
     */
    private fun callback(samples: FloatArray): Int {
        val t = track
        return if (!stopRequested && t != null) {
            t.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            1
        } else {
            0
        }
    }

    /** Speak [text]. Silently drops if engine isn't initialised yet. */
    fun speak(text: String) {
        val tts = engine
        val t = track
        if (tts == null || t == null) {
            Log.i(TAG, "TTS not ready; skipping: $text")
            return
        }
        executor.execute {
            try {
                // Cut off whatever was playing — keeps recall snappy if the
                // user fires a follow-up query before the previous answer ends.
                stopRequested = true
                t.pause()
                t.flush()
                t.play()
                stopRequested = false
                tts.generateWithCallback(
                    text = text,
                    sid = SPEAKER_ID,
                    speed = SPEED,
                    callback = ::callback,
                )
            } catch (th: Throwable) {
                Log.e(TAG, "TTS speak failed", th)
            }
        }
    }

    fun shutdown() {
        stopRequested = true
        initStarted = false
        track?.stop()
        track?.release()
        track = null
        engine?.free()
        engine = null
        executor.shutdown()
    }
}
