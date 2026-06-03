package com.owner.assist.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * Streamed PCM playback. 16kHz mono PCM_16BIT to match Deepgram Aura-2 output.
 *
 * Output routes through whatever AudioManager.communicationDevice / BT SCO has
 * selected — BluetoothScoManager handles routing setup.
 *
 * writeAsync() runs on Dispatchers.IO to avoid blocking caller's coroutine on
 * AudioTrack drain. AudioTrack.write itself is internally blocking (WRITE_BLOCKING).
 */
class AudioPlayer {

    @Volatile private var track: AudioTrack? = null
    private val totalBytesWritten = AtomicLong(0L)
    @Volatile private var playbackStarted = false
    private val preBuffer = java.io.ByteArrayOutputStream()
    private var dumpStream: FileOutputStream? = null
    private val eventLog = AudioEventLog()

    // Silence trimmer state. Deepgram Aura-2 embeds inter-sentence silence in raw PCM.
    // We replace silent chunks with zeros to keep the AudioTrack buffer full (skipping them
    // drains the buffer faster than Deepgram refills it → underrun). Two transition ramps
    // prevent click artifacts:
    //   fade-OUT on the first zero chunk (speech → silence): ramps lastSpeechSampleValue → 0.
    //   fade-IN on the first speech chunk after silence (silence → speech): ramps 0 → full.
    private var consecutiveSilentChunks = 0
    private var consecutiveSpeechChunks = 0
    private var needsFadeIn = false
    private var lastSpeechSampleValue: Short = 0

    fun start() {
        if (track != null) return
        totalBytesWritten.set(0L)
        playbackStarted = false
        consecutiveSilentChunks = 0
        consecutiveSpeechChunks = 0
        needsFadeIn = false
        lastSpeechSampleValue = 0
        synchronized(preBuffer) { preBuffer.reset() }
        eventLog.start()
        try {
            val f = File("/sdcard/Download/pcm_dump.raw")
            dumpStream = FileOutputStream(f)
            Log.i(TAG, "PCM dump → ${f.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "PCM dump open failed: ${e.message}")
        }
        // 2-second ring buffer. After pre-buffering eliminates the initial underrun,
        // this headroom covers variable-latency network delivery between TTS sentences.
        val bufSize = SAMPLE_RATE * PCM_BYTES_PER_FRAME * 2
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // USAGE_MEDIA routes through BT SCO on Samsung when SCO is active
                    // (system log confirms "STRATEGY_MEDIA force bt sco").
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(bufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        Log.i(TAG, "AudioTrack ready state=${t.state} sessionId=${t.audioSessionId} bufSize=$bufSize")
    }

    /** RMS squared per sample — computed once per chunk, used for both silence detection and logging. */
    private fun computeRmsSq(pcm: ByteArray): Long {
        var sumSq = 0L
        var i = 0
        while (i + 1 < pcm.size) {
            val s = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toLong()
            sumSq += s * s
            i += 2
        }
        val count = pcm.size / 2
        return if (count > 0) sumSq / count else 0L
    }

    /** Write a PCM chunk on an IO thread. Caller must already be in a coroutine. */
    suspend fun writeAsync(pcm: ByteArray) = withContext(Dispatchers.IO) {
        val t = track ?: return@withContext
        val rmsSq = computeRmsSq(pcm)

        // ── Silence path ────────────────────────────────────────────────────────────
        if (rmsSq < SILENCE_RMS_SQ_THRESHOLD) {
            consecutiveSilentChunks++
            consecutiveSpeechChunks = 0
            needsFadeIn = true
            if (playbackStarted) {
                // First silent chunk: ramp from last speech amplitude → 0 (no abrupt click).
                // Subsequent silent chunks: pure zeros.
                val out = if (consecutiveSilentChunks == 1 && lastSpeechSampleValue != 0.toShort()) {
                    applyFadeOut(lastSpeechSampleValue, pcm.size)
                } else {
                    ByteArray(pcm.size)
                }
                val n = t.write(out, 0, out.size, AudioTrack.WRITE_BLOCKING)
                if (n > 0) totalBytesWritten.addAndGet(n.toLong())
                val ev = if (consecutiveSilentChunks == 1) "SIL_FADE" else "SIL_ZERO"
                Log.d(TAG, "silence→${if (consecutiveSilentChunks == 1) "fadeOut(from=$lastSpeechSampleValue)" else "zeros"} chunk=$consecutiveSilentChunks rms=$rmsSq")
                eventLog.log(ev, pcm.size, rmsSq, consecutiveSilentChunks, lastSpeechSampleValue, totalBytesWritten.get(), t.playbackHeadPosition)
            }
            return@withContext
        }

        // ── Speech path ─────────────────────────────────────────────────────────────
        consecutiveSpeechChunks++

        // Minimum-duration gate: after a silence run, require MIN_SPEECH_CHUNKS consecutive
        // speech chunks before switching to speech mode. Prevents brief consonant/transition
        // sounds (9-40ms) between silence gaps from playing as audible clicks.
        if (consecutiveSilentChunks > 0 && consecutiveSpeechChunks < MIN_SPEECH_CHUNKS_AFTER_SILENCE) {
            if (playbackStarted) {
                val zeros = ByteArray(pcm.size)
                val n = t.write(zeros, 0, zeros.size, AudioTrack.WRITE_BLOCKING)
                if (n > 0) totalBytesWritten.addAndGet(n.toLong())
                eventLog.log("SIL_GATE", pcm.size, rmsSq, consecutiveSilentChunks, lastSpeechSampleValue, totalBytesWritten.get(), t.playbackHeadPosition)
                Log.d(TAG, "gate: brief post-silence chunk $consecutiveSpeechChunks/${MIN_SPEECH_CHUNKS_AFTER_SILENCE} rms=$rmsSq → zeros")
            }
            return@withContext
        }

        if (consecutiveSilentChunks > 0) {
            Log.d(TAG, "speech resumed after $consecutiveSilentChunks silent chunks (${consecutiveSilentChunks * 40}ms trimmed)")
        }
        consecutiveSilentChunks = 0

        // Fade-in: apply 5ms (80 samples) linear ramp to first chunk after a silence run.
        val chunkToWrite = if (needsFadeIn) {
            needsFadeIn = false
            applyFadeIn(pcm)
        } else {
            pcm
        }

        if (!playbackStarted) {
            // Accumulate until we have PRE_BUFFER_BYTES, then flush and start playing.
            val accumulated: ByteArray
            synchronized(preBuffer) {
                preBuffer.write(chunkToWrite)
                if (preBuffer.size() < PRE_BUFFER_BYTES) {
                    eventLog.log("PRE_BUF", chunkToWrite.size, rmsSq, 0, lastSpeechSampleValue, totalBytesWritten.get(), 0)
                    return@withContext
                }
                accumulated = preBuffer.toByteArray()
                preBuffer.reset()
            }
            val n = t.write(accumulated, 0, accumulated.size, AudioTrack.WRITE_BLOCKING)
            if (n < 0) {
                Log.w(TAG, "Pre-buffer write failed: error $n — skipping play()")
                eventLog.log("WRITE_ERR", accumulated.size, rmsSq, 0, lastSpeechSampleValue, totalBytesWritten.get(), t.playbackHeadPosition)
                return@withContext
            }
            totalBytesWritten.addAndGet(n.toLong())
            // Track last sample from the pre-buffer for future fade-out
            if (accumulated.size >= 2) {
                val li = accumulated.size - 2
                lastSpeechSampleValue = ((accumulated[li + 1].toInt() shl 8) or (accumulated[li].toInt() and 0xFF)).toShort()
            }
            dumpStream?.write(accumulated, 0, n)
            t.play()
            playbackStarted = true
            val bufMs = accumulated.size * 1000 / PCM_BYTES_PER_FRAME / SAMPLE_RATE
            Log.i(TAG, "Pre-buffer full (${accumulated.size}B = ${bufMs}ms) — playback started. playState=${t.playState}")
            eventLog.log("PLAY_START", accumulated.size, rmsSq, 0, lastSpeechSampleValue, totalBytesWritten.get(), t.playbackHeadPosition)
            return@withContext
        }

        val n = t.write(chunkToWrite, 0, chunkToWrite.size, AudioTrack.WRITE_BLOCKING)
        if (n < 0) {
            Log.w(TAG, "AudioTrack.write error $n")
            eventLog.log("WRITE_ERR", chunkToWrite.size, rmsSq, 0, lastSpeechSampleValue, totalBytesWritten.get(), t.playbackHeadPosition)
        } else {
            if (n < chunkToWrite.size) Log.w(TAG, "AudioTrack.write short: $n/${chunkToWrite.size} B")
            totalBytesWritten.addAndGet(n.toLong())
            // Use n (bytes actually written) not chunkToWrite.size as the upper bound.
            // On a short write, the tail bytes were never played — ramp from them would click.
            if (n >= 2) {
                val li = n - 2
                lastSpeechSampleValue = ((chunkToWrite[li + 1].toInt() shl 8) or (chunkToWrite[li].toInt() and 0xFF)).toShort()
            }
            Log.d(TAG, "chunk: $n/${chunkToWrite.size} B head=${t.playbackHeadPosition}")
            eventLog.log("SPEECH", chunkToWrite.size, rmsSq, 0, lastSpeechSampleValue, totalBytesWritten.get(), t.playbackHeadPosition)
            dumpStream?.write(chunkToWrite, 0, n)
        }
    }

    /** Apply a 5ms (80 samples) linear fade-in to avoid click when resuming after silence. */
    private fun applyFadeIn(pcm: ByteArray): ByteArray {
        val result = pcm.copyOf()
        val fadeSamples = FADE_IN_SAMPLES
        var i = 0
        var sampleIdx = 0
        while (i + 1 < result.size && sampleIdx < fadeSamples) {
            val sample = ((result[i + 1].toInt() shl 8) or (result[i].toInt() and 0xFF)).toShort()
            val faded = (sample * sampleIdx / fadeSamples).toShort()
            result[i] = (faded.toInt() and 0xFF).toByte()
            result[i + 1] = (faded.toInt() shr 8).toByte()
            i += 2
            sampleIdx++
        }
        return result
    }

    /** Apply a linear fade-out ramp: [fromSample] → 0 over the full [size] bytes. */
    private fun applyFadeOut(fromSample: Short, size: Int): ByteArray {
        val result = ByteArray(size)
        val sampleCount = size / 2
        if (sampleCount == 0) return result
        for (i in 0 until sampleCount) {
            val amplitude = (fromSample.toInt() * (sampleCount - 1 - i) / sampleCount)
                .coerceIn(-32768, 32767).toShort()
            result[i * 2] = (amplitude.toInt() and 0xFF).toByte()
            result[i * 2 + 1] = (amplitude.toInt() shr 8).toByte()
        }
        return result
    }

    /** Returns the playback head position in frames — used to gate barge-in. */
    fun playbackHeadFrames(): Int = track?.playbackHeadPosition ?: 0

    /** Set playback gain (0.0 = silent, 1.0 = full). Takes effect immediately mid-stream. */
    fun setVolume(level: Float) {
        track?.setVolume(level.coerceIn(0f, 1f))
    }

    /**
     * Remaining playback time in ms based on how far the hardware head lags behind
     * total bytes written. Use this to calculate how long to wait before calling stop().
     */
    fun remainingMs(): Long {
        val t = track ?: return 0L
        val framesWritten = totalBytesWritten.get() / PCM_BYTES_PER_FRAME
        val framesPlayed = t.playbackHeadPosition.toLong()
        val remaining = maxOf(0L, framesWritten - framesPlayed)
        return remaining * 1000L / SAMPLE_RATE
    }

    fun stop() {
        val headPos = track?.playbackHeadPosition ?: 0
        track?.let {
            try {
                if (playbackStarted) it.stop() else it.flush()
                it.release()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "stop: ${e.message}")
            }
        }
        eventLog.log("STOP", bytesWritten = totalBytesWritten.get(), headPos = headPos)
        eventLog.stop()
        track = null
        totalBytesWritten.set(0L)
        playbackStarted = false
        consecutiveSilentChunks = 0
        consecutiveSpeechChunks = 0
        needsFadeIn = false
        lastSpeechSampleValue = 0
        synchronized(preBuffer) { preBuffer.reset() }
        try { dumpStream?.close() } catch (_: Exception) {}
        dumpStream = null
    }

    companion object {
        private const val TAG = "AudioPlayer"
        const val SAMPLE_RATE = 16_000
        private const val PCM_BYTES_PER_FRAME = 2      // mono PCM_16BIT: 1 channel × 2 bytes
        // 1s of audio to buffer before starting playback. Deepgram streams in ~680ms synthesis
        // batches — 1s > 680ms batch gap = no underrun on the initial burst.
        private const val PRE_BUFFER_BYTES = SAMPLE_RATE * PCM_BYTES_PER_FRAME  // 32000 B = 1s
        // RMS threshold in int16 units squared. 80² = 6400 → ~6% of speech RMS (≈1308 int16).
        private const val SILENCE_RMS_SQ_THRESHOLD = 6400L
        // 5ms fade-in after silence run: 80 samples at 16kHz prevents click at 0→amplitude jump.
        private const val FADE_IN_SAMPLES = 80
        // Require this many consecutive speech chunks after a silence run before switching to
        // speech mode. Prevents brief consonant sounds (< 80ms) between silence gaps from
        // playing as audible clicks. Each chunk ≈ 40ms, so 2 = 80ms minimum gate.
        private const val MIN_SPEECH_CHUNKS_AFTER_SILENCE = 2
    }
}
