package com.owner.assist.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    private var track: AudioTrack? = null

    fun start() {
        if (track != null) return
        // 2-second ring buffer. Absorbs the ~400ms gap between TTS sentences so the
        // AudioTrack never hits bottom mid-response (underrun → BT crackle/pop).
        val bufSize = SAMPLE_RATE * PCM_BYTES_PER_SAMPLE * 2
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
        t.play()
        track = t
        Log.i(TAG, "AudioTrack started state=${t.state} playState=${t.playState} sessionId=${t.audioSessionId} bufSize=$bufSize")
    }

    /** Write a PCM chunk on an IO thread. Caller must already be in a coroutine. */
    suspend fun writeAsync(pcm: ByteArray) = withContext(Dispatchers.IO) {
        val t = track ?: return@withContext
        val n = t.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
        if (n < 0) Log.w(TAG, "AudioTrack.write error $n")
        else Log.d(TAG, "chunk done: wrote $n/${pcm.size} B playHead=${t.playbackHeadPosition}")
    }

    /** Returns the playback head position in frames — used to gate barge-in. */
    fun playbackHeadFrames(): Int = track?.playbackHeadPosition ?: 0

    fun stop() {
        track?.let {
            try {
                it.stop()
                it.flush()
                it.release()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "stop: ${e.message}")
            }
        }
        track = null
    }

    companion object {
        private const val TAG = "AudioPlayer"
        const val SAMPLE_RATE = 16_000
        private const val PCM_BYTES_PER_SAMPLE = 2  // 16-bit = 2 bytes
    }
}
