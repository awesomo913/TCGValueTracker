package com.owner.assist.audio

import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

/**
 * Writes one CSV row per AudioPlayer event to /sdcard/Download/audio_events.csv.
 *
 * Pull from device:
 *   adb -s RFCY514BLJH exec-out "cat /sdcard/Download/audio_events.csv" > audio_events.csv
 *
 * Analyze on PC:
 *   python scripts/analyze_audio.py audio_events.csv
 *   python scripts/analyze_audio.py --pull RFCY514BLJH   (pull + analyze in one shot)
 *
 * Events written:
 *   PRE_BUF    — chunk added to pre-buffer (not yet playing)
 *   PLAY_START — pre-buffer flushed, AudioTrack.play() called
 *   SPEECH     — direct speech write to AudioTrack
 *   SIL_FADE   — first silent chunk: fade-out ramp written
 *   SIL_ZERO   — subsequent silent chunks: zeros written
 *   WRITE_ERR  — AudioTrack.write() returned an error code
 *   STOP       — player stopped
 */
class AudioEventLog {
    private var writer: BufferedWriter? = null
    private var startMs: Long = 0L

    fun start() {
        startMs = System.currentTimeMillis()
        try {
            val f = File("/sdcard/Download/audio_events.csv")
            // Intentional overwrite (not append) — each session gets a fresh CSV.
            // Pull with adb exec-out immediately after a test run before starting another.
            writer = BufferedWriter(FileWriter(f, false))
            writer?.write("ts_ms,event,chunk_bytes,rms_sq,consec_silent,last_speech,bytes_written,head_pos\n")
            writer?.flush()
            Log.i(TAG, "Event log → ${f.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "EventLog open failed: ${e.message}")
        }
    }

    fun log(
        event: String,
        chunkBytes: Int = 0,
        rmsSq: Long = 0L,
        consecSilent: Int = 0,
        lastSpeech: Short = 0,
        bytesWritten: Long = 0L,
        headPos: Int = 0,
    ) {
        val ts = System.currentTimeMillis() - startMs
        try {
            writer?.write("$ts,$event,$chunkBytes,$rmsSq,$consecSilent,$lastSpeech,$bytesWritten,$headPos\n")
        } catch (e: Exception) {
            Log.w(TAG, "EventLog write failed: ${e.message}")
        }
    }

    fun stop() {
        try {
            writer?.flush()
            writer?.close()
        } catch (_: Exception) {}
        writer = null
    }

    companion object {
        private const val TAG = "AudioEventLog"
    }
}
