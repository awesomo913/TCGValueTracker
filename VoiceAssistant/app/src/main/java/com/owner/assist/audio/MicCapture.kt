package com.owner.assist.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 16kHz mono PCM_16BIT mic capture.
 *
 * Sample rate locked to 16k: Deepgram streaming STT accepts it natively, smallest payload.
 * Emits ~20ms chunks (640 bytes) which keeps WS framing efficient.
 */
class MicCapture(private val ctx: Context) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Emits PCM byte chunks. Closes the underlying AudioRecord when the collector cancels.
     */
    @SuppressLint("MissingPermission")
    fun stream(): Flow<ByteArray> = callbackFlow {
        check(hasPermission()) { "RECORD_AUDIO permission not granted" }

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val bufSize = (minBuf * 2).coerceAtLeast(CHUNK_BYTES * 4)

        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL,
            ENCODING,
            bufSize,
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            close(IllegalStateException("AudioRecord init failed"))
            return@callbackFlow
        }
        rec.startRecording()

        val buf = ByteArray(CHUNK_BYTES)
        try {
            while (!isClosedForSend && rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val read = rec.read(buf, 0, buf.size)
                if (read > 0) {
                    trySend(buf.copyOf(read))
                } else if (read < 0) {
                    Log.w(TAG, "AudioRecord.read returned $read")
                    break
                }
            }
        } finally {
            try {
                rec.stop()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "rec.stop: ${e.message}")
            }
            rec.release()
        }

        awaitClose { /* AudioRecord released in finally */ }
    }.flowOn(Dispatchers.IO)

    /**
     * Convenience: dump PCM straight to a file for the task #3 smoke test.
     * Returns a Job. Cancel it to stop the recording.
     */
    fun recordToFile(path: String, scope: CoroutineScope): Job =
        scope.launch(Dispatchers.IO) {
            val file = java.io.File(path)
            file.parentFile?.mkdirs()
            file.outputStream().use { out ->
                stream().collect { chunk ->
                    if (!isActive) return@collect
                    out.write(chunk)
                }
            }
        }

    companion object {
        private const val TAG = "MicCapture"
        const val SAMPLE_RATE = 16_000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        // 20ms @ 16kHz mono 16-bit = 16000 * 0.020 * 2 = 640 bytes
        const val CHUNK_BYTES = 640
    }
}
