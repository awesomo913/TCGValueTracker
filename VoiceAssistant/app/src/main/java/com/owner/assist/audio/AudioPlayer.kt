package com.owner.assist.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    // Pre-buffer accumulator. We write into the AudioTrack buffer before calling play()
    // so the hardware never starts on an empty buffer (which causes an immediate underrun
    // → crackle/dropout at the very start of every response).
    @Volatile private var playbackStarted = false
    private val preBuffer = java.io.ByteArrayOutputStream()

    fun start() {
        if (track != null) return
        totalBytesWritten.set(0L)
        playbackStarted = false
        synchronized(preBuffer) { preBuffer.reset() }
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

    /** Write a PCM chunk on an IO thread. Caller must already be in a coroutine. */
    suspend fun writeAsync(pcm: ByteArray) = withContext(Dispatchers.IO) {
        val t = track ?: return@withContext

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
                t.play()
                playbackStarted = true
                Log.i(TAG, "Pre-buffer full (${accumulated.size}B) — playback started. playState=${t.playState}")
            }
            return@withContext
        }

        val n = t.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
        if (n < 0) Log.w(TAG, "AudioTrack.write error $n")
        else {
            if (n < pcm.size) Log.w(TAG, "AudioTrack.write short: $n/${pcm.size} B")
            totalBytesWritten.addAndGet(n.toLong())
            Log.d(TAG, "chunk done: wrote $n/${pcm.size} B playHead=${t.playbackHeadPosition}")
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
    }

    companion object {
        private const val TAG = "AudioPlayer"
        const val SAMPLE_RATE = 16_000
        private const val PCM_BYTES_PER_FRAME = 2      // mono PCM_16BIT: 1 channel × 2 bytes
        // 500ms of audio to buffer before starting playback. Chosen to cover the typical
        // TTS first-byte latency (~640ms) so the hardware never starts on an empty buffer.
        private const val PRE_BUFFER_BYTES = SAMPLE_RATE * PCM_BYTES_PER_FRAME / 2  // 16000 B
    }
}
