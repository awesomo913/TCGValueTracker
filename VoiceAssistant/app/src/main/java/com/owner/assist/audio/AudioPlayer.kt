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

    // Silence trimmer state: Deepgram Aura-2 embeds inter-sentence silence (up to 400ms)
    // in the raw PCM. We pass through up to MAX_SILENCE_CHUNKS of consecutive silence, then
    // drop further silent chunks until speech resumes. Eliminates audible "skips" without
    // cutting natural short pauses.
    private var consecutiveSilentChunks = 0

    fun start() {
        if (track != null) return
        totalBytesWritten.set(0L)
        playbackStarted = false
        consecutiveSilentChunks = 0
        synchronized(preBuffer) { preBuffer.reset() }
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
                    // USAGE_VOICE_COMMUNICATION blocks when no active call session.
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
        // Do NOT call t.play() here. Playback deferred to first writeAsync() call so
        // the buffer has data before the hardware starts consuming it.
        track = t
        Log.i(TAG, "AudioTrack ready (not yet playing) state=${t.state} sessionId=${t.audioSessionId} bufSize=$bufSize")
    }

    /** True if the RMS of [pcm] is below SILENCE_RMS_THRESHOLD (int16 scale). */
    private fun isSilentChunk(pcm: ByteArray): Boolean {
        var sumSq = 0L
        var i = 0
        while (i + 1 < pcm.size) {
            val s = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toLong()
            sumSq += s * s
            i += 2
        }
        val count = pcm.size / 2
        // RMS in int16 units. Compare to threshold squared to avoid sqrt.
        return sumSq / count < SILENCE_RMS_SQ_THRESHOLD
    }

    /** Write a PCM chunk on an IO thread. Caller must already be in a coroutine. */
    suspend fun writeAsync(pcm: ByteArray) = withContext(Dispatchers.IO) {
        val t = track ?: return@withContext

        // Silence trimmer: allow up to MAX_SILENCE_CHUNKS consecutive silent chunks through,
        // then drop until speech resumes. Removes Deepgram's inter-sentence silence (up to 400ms)
        // that causes audible "skipping" without affecting short natural pauses (≤80ms).
        if (isSilentChunk(pcm)) {
            consecutiveSilentChunks++
            if (consecutiveSilentChunks > MAX_SILENCE_CHUNKS) {
                Log.d(TAG, "silence trimmed (chunk $consecutiveSilentChunks)")
                return@withContext
            }
        } else {
            if (consecutiveSilentChunks > MAX_SILENCE_CHUNKS) {
                Log.d(TAG, "speech resumed after ${consecutiveSilentChunks} silent chunks (${consecutiveSilentChunks * 40}ms trimmed)")
            }
            consecutiveSilentChunks = 0
        }

        if (!playbackStarted) {
            // Accumulate until we have PRE_BUFFER_BYTES, then flush and start playing.
            val accumulated: ByteArray
            synchronized(preBuffer) {
                preBuffer.write(pcm)
                if (preBuffer.size() < PRE_BUFFER_BYTES) return@withContext
                accumulated = preBuffer.toByteArray()
                preBuffer.reset()
            }
            // Write accumulated data into the (still-paused) AudioTrack buffer, then play.
            val n = t.write(accumulated, 0, accumulated.size, AudioTrack.WRITE_BLOCKING)
            if (n > 0) {
                totalBytesWritten.addAndGet(n.toLong())
                dumpStream?.write(accumulated, 0, n)
                t.play()
                playbackStarted = true
                val bufMs = accumulated.size * 1000 / PCM_BYTES_PER_FRAME / SAMPLE_RATE
                Log.i(TAG, "Pre-buffer full (${accumulated.size}B = ${bufMs}ms) — playback started. playState=${t.playState}")
            }
            return@withContext
        }

        val n = t.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
        if (n < 0) Log.w(TAG, "AudioTrack.write error $n")
        else {
            if (n < pcm.size) Log.w(TAG, "AudioTrack.write short: $n/${pcm.size} B")
            totalBytesWritten.addAndGet(n.toLong())
            Log.d(TAG, "chunk done: wrote $n/${pcm.size} B playHead=${t.playbackHeadPosition}")
            dumpStream?.write(pcm, 0, n)
        }
    }

    /** Returns the playback head position in frames — used to gate barge-in. */
    fun playbackHeadFrames(): Int = track?.playbackHeadPosition ?: 0

    /**
     * Remaining playback time in ms based on how far the hardware head lags behind
     * total bytes written. Use this to calculate how long to wait before calling stop().
     */
    fun remainingMs(): Long {
        val t = track ?: return 0L
        // Mono PCM_16BIT: 1 frame = 2 bytes. playbackHeadPosition is in frames (Int, wraps at ~37h).
        val framesWritten = totalBytesWritten.get() / PCM_BYTES_PER_FRAME
        val framesPlayed = t.playbackHeadPosition.toLong()
        val remaining = maxOf(0L, framesWritten - framesPlayed)
        return remaining * 1000L / SAMPLE_RATE
    }

    fun stop() {
        track?.let {
            try {
                // stop() in streaming mode drains remaining buffer before halting.
                // Do NOT call flush() — flush() discards buffered audio immediately.
                if (playbackStarted) it.stop() else it.flush()
                it.release()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "stop: ${e.message}")
            }
        }
        track = null
        totalBytesWritten.set(0L)
        playbackStarted = false
        synchronized(preBuffer) { preBuffer.reset() }
        try { dumpStream?.close() } catch (_: Exception) {}
        dumpStream = null
    }

    companion object {
        private const val TAG = "AudioPlayer"
        const val SAMPLE_RATE = 16_000
        private const val PCM_BYTES_PER_FRAME = 2      // mono PCM_16BIT: 1 channel × 2 bytes
        // 1s of audio to buffer before starting playback. Deepgram streams in ~680ms synthesis
        // batches even for a single speak() call — 500ms (old value) drained before the next
        // batch arrived (logcat: restartIfDisabled at t+526ms). 1s > 680ms batch gap = no underrun.
        private const val PRE_BUFFER_BYTES = SAMPLE_RATE * PCM_BYTES_PER_FRAME  // 32000 B = 1s
        // Silence trimmer constants. Each chunk = 1280 bytes = 640 samples = 40ms at 16kHz.
        // Allow 1 consecutive silent chunk (40ms) through, drop the rest until speech resumes.
        private const val MAX_SILENCE_CHUNKS = 1
        // RMS threshold in int16 units squared (avoids sqrt). Active speech RMS ≈ 1308 int16.
        // 80^2 = 6400 → ~6% of speech level. Catches Deepgram's low-amplitude background tone
        // between sentences that the old per-sample peak check missed.
        private const val SILENCE_RMS_SQ_THRESHOLD = 6400L  // RMS ≈ 80 / 32768 ≈ 0.0024 float
    }
}
