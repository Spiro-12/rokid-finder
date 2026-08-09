package com.example.rokidfinder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.util.Log
import androidx.core.content.ContextCompat
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Streams one spoken utterance to the Mac as `0x02` binary messages (protocol §4.2).
 *
 * The mic opens on an explicit tap and closes as soon as the wearer stops talking. That
 * is a privacy property, not an optimization: audio only ever exists inside an utterance.
 *
 * Endpointing runs here rather than on the Mac so the brain is never paid for dead air,
 * and so the wearer's "I'm done" moment is decided by the device holding the microphone.
 * Two thresholds do the work: a pre-speech window (nothing said at all → give up) and a
 * trailing-silence window (`silence_endpoint_ms` from the Mac's config). A hard
 * `max_utterance_ms` ceiling catches a stuck VAD or a wearer who never pauses.
 *
 * Threading: every utterance runs on a single dedicated worker thread. Callers (the
 * broadcast receiver on the main thread) only ever enqueue. A [generation] counter makes
 * cancellation cheap and race-free — a second [start] simply invalidates the first, which
 * is exactly the "only one utterance open at a time" rule the protocol states.
 *
 * The AudioRecord is released in a `finally` block without exception: a leaked mic on this
 * HAL means every later attempt fails until the process dies, which would silently break
 * both this path *and* the on-device Vosk fallback.
 */
class AudioStreamer(
    context: Context,
    private val link: LinkClient,
    private val events: Events,
) {

    /** All callbacks arrive on the audio worker thread, never the main thread. */
    interface Events {
        /** The mic is open and audio is being captured. */
        fun onListening()

        /** The mic or the link could not be used at all; nothing was sent. */
        fun onUnavailable()

        /**
         * The utterance is closed. [heardSpeech] is false when the wearer never crossed
         * the speech threshold — the caller can say "Didn't catch that" immediately
         * instead of waiting on a transcript that will never be interesting.
         */
        fun onFinished(reason: String, heardSpeech: Boolean)
    }

    private val appContext = context.applicationContext

    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "AudioStreamer").apply { isDaemon = true }
    }

    /** Bumped on every start and on [cancel]; a worker whose generation is stale exits. */
    @Volatile private var generation: Int = 0
    @Volatile private var stopped: Boolean = false

    /** True between the mic opening and the `utterance_end` going out. */
    @Volatile var isStreaming: Boolean = false
        private set

    /**
     * Capture and stream one utterance. Returns immediately; progress arrives via [Events].
     * A second call implicitly cancels the one in flight, per protocol §4.2.
     */
    fun start() {
        if (stopped) return
        val gen = ++generation
        runCatching { exec.execute { runUtterance(gen) } }
            .onFailure { events.onUnavailable() }
    }

    /** Abandon any open utterance. Safe to call from any thread, including teardown. */
    fun cancel() {
        generation++
    }

    fun shutdown() {
        stopped = true
        generation++
        exec.shutdownNow()
    }

    // --- the worker ----------------------------------------------------------

    private fun runUtterance(gen: Int) {
        if (gen != generation) return

        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "no RECORD_AUDIO permission")
            events.onUnavailable()
            return
        }
        if (link.readySocket() == null) {
            events.onUnavailable()
            return
        }

        // Earcon before the mic opens, deliberately: the beep is then physically
        // incapable of landing in the audio we send, and the short settle after it
        // doubles as "the device heard your tap" feedback.
        playEarcon()
        if (gen != generation) return

        val recorder = openRecorder()
        if (recorder == null) {
            Log.e(TAG, "AudioRecord could not open any source")
            events.onUnavailable()
            return
        }

        val config = link.config
        val silenceMs = config.silenceEndpointMs
        val maxMs = config.maxUtteranceMs
        // A wearer who is slow to start talking must not be cut off by the trailing
        // -silence rule, so pre-speech gets its own, more generous window.
        val preSpeechMs = (silenceMs * 2).coerceAtLeast(PRE_SPEECH_MIN_MS).coerceAtMost(maxMs)

        val utteranceId = "utt_${System.currentTimeMillis()}"
        val startedAt = System.currentTimeMillis()
        var reason = "cancelled"
        var heardSpeech = false
        var started = false
        var noiseFloor = 0.0
        var peakRms = 0.0

        try {
            recorder.startRecording()
            isStreaming = true
            events.onListening()

            sendJson(
                JSONObject()
                    .put("type", "utterance_start")
                    .put("id", utteranceId)
                    .put("ts", startedAt)
            ) ?: run { reason = "cancelled"; return }
            started = true

            val buf = ShortArray(CHUNK_SAMPLES)
            val bytes = ByteArray(TAG_AND_SEQ_BYTES + CHUNK_SAMPLES * 2)
            bytes[0] = TAG_AUDIO.toByte()

            // Pre-roll: the ~200 ms before the VAD fires still contains the attack of
            // the first word. We hold it back rather than dropping it, so "no dead air"
            // doesn't turn into a clipped first syllable.
            val preRoll = ArrayDeque<ShortArray>()
            var seq = 0
            var chunks = 0
            var lastSpeechMs = 0L
            var readErrors = 0

            while (gen == generation) {
                val elapsed = System.currentTimeMillis() - startedAt
                if (elapsed >= maxMs) { reason = "max_duration"; break }

                val n = recorder.read(buf, 0, CHUNK_SAMPLES)
                if (n <= 0) {
                    if (++readErrors > MAX_READ_ERRORS) { reason = "cancelled"; break }
                    continue
                }

                val rms = rms(buf, n)
                chunks++
                // Seed the noise floor from the first few chunks, then let it drift
                // slowly downward-biased so a passing truck doesn't deafen the VAD.
                noiseFloor = if (chunks <= NOISE_SEED_CHUNKS) {
                    if (chunks == 1) rms else noiseFloor * 0.7 + rms * 0.3
                } else {
                    minOf(noiseFloor, noiseFloor * 0.98 + rms * 0.02)
                }
                val isSpeech = rms > ABSOLUTE_SPEECH_RMS && rms > noiseFloor * SPEECH_OVER_FLOOR
                if (rms > peakRms) peakRms = rms

                val now = System.currentTimeMillis()
                if (isSpeech) {
                    heardSpeech = true
                    lastSpeechMs = now
                }

                if (!heardSpeech) {
                    preRoll.addLast(buf.copyOf(n))
                    while (preRoll.size > PRE_ROLL_CHUNKS) preRoll.removeFirst()
                    if (now - startedAt >= preSpeechMs) { reason = "silence"; break }
                    continue
                }

                // First speech chunk: flush the pre-roll ahead of it, in order.
                var sendOk = true
                while (sendOk && preRoll.isNotEmpty()) {
                    val held = preRoll.removeFirst()
                    sendOk = sendChunk(bytes, seq++, held, held.size)
                }
                if (sendOk) sendOk = sendChunk(bytes, seq++, buf, n)
                if (!sendOk) { reason = "cancelled"; break }

                if (!isSpeech && now - lastSpeechMs >= silenceMs) { reason = "silence"; break }
            }
            if (gen != generation) reason = "cancelled"
        } catch (t: Throwable) {
            Log.e(TAG, "capture failed", t)
            reason = "cancelled"
        } finally {
            // Unconditional, in this order: a mic left open here poisons every later
            // attempt, including the on-device Vosk fallback.
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
            isStreaming = false
        }

        val durationMs = System.currentTimeMillis() - startedAt
        if (started) {
            sendJson(
                JSONObject()
                    .put("type", "utterance_end")
                    .put("id", utteranceId)
                    .put("reason", reason)
                    .put("duration_ms", durationMs)
            )
        }
        // Peak and floor are logged because the speech thresholds below are tuned to
        // this HAL's mic gain, which no amount of reasoning can predict. If an
        // utterance is wrongly judged silent, these two numbers say by how much.
        Log.i(TAG, "utterance $utteranceId end reason=$reason speech=$heardSpeech " +
                "${durationMs}ms peakRms=${"%.0f".format(peakRms)} " +
                "noiseFloor=${"%.0f".format(noiseFloor)} " +
                "(speech needs rms>$ABSOLUTE_SPEECH_RMS and rms>${
                    "%.0f".format(noiseFloor * SPEECH_OVER_FLOOR)})")

        // A superseded utterance's outcome belongs to the newer one; stay quiet.
        if (gen != generation) return
        events.onFinished(reason, heardSpeech)
    }

    /**
     * Reuse the proven source ladder: on this HAL only `MIC` actually works
     * (`VOICE_RECOGNITION` fails with ENODEV), but the fallbacks cost nothing and
     * keep the app alive on any other device someone sideloads it onto.
     */
    private fun openRecorder(): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)
        val bufSize = maxOf(minBuf * 2, CHUNK_SAMPLES * 2 * 8)
        val sources = intArrayOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.DEFAULT,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
        )
        for (src in sources) {
            val r = try {
                AudioRecord(
                    src, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufSize
                )
            } catch (t: Throwable) {
                Log.w(TAG, "AudioRecord source=$src unavailable", t); null
            }
            if (r != null && r.state == AudioRecord.STATE_INITIALIZED) {
                Log.i(TAG, "AudioRecord opened with source=$src")
                return r
            }
            r?.release()
        }
        return null
    }

    /** `[0x02][4-byte BE seq][s16le PCM]`. Returns false if the link went away. */
    private fun sendChunk(scratch: ByteArray, seq: Int, samples: ShortArray, n: Int): Boolean {
        val ws = link.readySocket() ?: return false
        // Audio is not droppable the way frames are — a hole in the stream is a hole in
        // the transcript — but an unbounded backlog means the link is gone in practice.
        if (ws.queueSize() > MAX_QUEUED_BYTES) {
            Log.w(TAG, "audio backpressure (${ws.queueSize()} queued) — abandoning utterance")
            return false
        }
        scratch[1] = ((seq ushr 24) and 0xFF).toByte()
        scratch[2] = ((seq ushr 16) and 0xFF).toByte()
        scratch[3] = ((seq ushr 8) and 0xFF).toByte()
        scratch[4] = (seq and 0xFF).toByte()
        var o = TAG_AND_SEQ_BYTES
        for (i in 0 until n) {
            val s = samples[i].toInt()
            scratch[o++] = (s and 0xFF).toByte()
            scratch[o++] = ((s shr 8) and 0xFF).toByte()
        }
        val payload = if (o == scratch.size) scratch else scratch.copyOf(o)
        return ws.send(payload.toByteString())
    }

    /** Returns null if the link is not up, which the caller treats as an abort. */
    private fun sendJson(msg: JSONObject): Boolean? {
        val ws = link.readySocket() ?: return null
        return if (ws.send(msg.toString())) true else null
    }

    /**
     * Root-mean-square of the chunk, in raw s16 units. Cheap enough to run on every
     * 40 ms block without touching the CPU budget the detector needs.
     */
    private fun rms(buf: ShortArray, n: Int): Double {
        var sum = 0.0
        for (i in 0 until n) {
            val v = buf[i].toDouble()
            sum += v * v
        }
        return Math.sqrt(sum / n)
    }

    /**
     * Short "go ahead" beep. ToneGenerator is used rather than a bundled asset because
     * it costs nothing to ship and cannot fail to decode.
     */
    private fun playEarcon() {
        val tone = runCatching {
            ToneGenerator(AudioManager.STREAM_MUSIC, EARCON_VOLUME)
        }.getOrNull() ?: return
        runCatching {
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, EARCON_MS)
            Thread.sleep(EARCON_MS + EARCON_SETTLE_MS)
        }
        runCatching { tone.release() }
    }

    private companion object {
        const val TAG = "RokidFinder.Audio"

        /** Binary tag byte for an audio chunk (protocol §4). */
        const val TAG_AUDIO = 0x02
        const val TAG_AND_SEQ_BYTES = 5

        const val SAMPLE_RATE = 16_000
        /** 40 ms at 16 kHz mono = 640 samples = 1280 bytes, per protocol §4.2. */
        const val CHUNK_SAMPLES = 640

        const val PRE_ROLL_CHUNKS = 5          // ~200 ms held back before speech onset
        const val PRE_SPEECH_MIN_MS = 3_000L
        const val NOISE_SEED_CHUNKS = 8        // ~320 ms to establish the room's floor
        /**
         * Speech thresholds, measured on this HAL rather than assumed.
         *
         * The first guess here was 550, which is a reasonable number for a phone
         * held near the mouth and hopelessly wrong for these glasses: a quiet room
         * floors at rms 1–3, and speech played across a desk peaked at 49. A 550
         * floor meant the wearer could never be heard at all.
         *
         * 25 is deliberately generous, because the two failure modes are not equal:
         * too high and the wearer talks to a device that ignores them, which reads as
         * broken; too low and an occasional room noise opens an utterance that the
         * Mac's own silence guard and Whisper then discard, costing a "didn't catch
         * that". Given a room floor of 1–3, 25 is still an order of magnitude clear of
         * ambient, and close speech sits far above it.
         */
        const val ABSOLUTE_SPEECH_RMS = 25.0
        const val SPEECH_OVER_FLOOR = 3.0
        const val MAX_READ_ERRORS = 20
        const val MAX_QUEUED_BYTES = 512L * 1024

        const val EARCON_VOLUME = 70
        const val EARCON_MS = 90
        const val EARCON_SETTLE_MS = 60L
    }
}
