package com.owner.assist.data

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Persistent append-only log written to filesDir/assist_debug.log.
 * Survives ADB disconnect — pull next session with:
 *   adb exec-out run-as com.owner.assist cat files/assist_debug.log
 * Rotates at 500KB, keeping the newest 250KB.
 */
object AppLogger {
    private const val TAG = "AppLogger"
    private const val LOG_FILE = "assist_debug.log"
    private const val MAX_BYTES = 500_000L
    private const val KEEP_BYTES = 250_000L

    private val executor = Executors.newSingleThreadExecutor()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    @Volatile private var logFile: File? = null

    fun init(ctx: Context) {
        logFile = File(ctx.filesDir, LOG_FILE)
        log("INIT", "session start — ${android.os.Build.MODEL} Android ${android.os.Build.VERSION.RELEASE}")
    }

    fun log(tag: String, msg: String) {
        val line = "${fmt.format(Date())} [$tag] $msg\n"
        executor.execute {
            val f = logFile ?: return@execute
            try {
                if (f.length() > MAX_BYTES) rotate(f)
                f.appendText(line)
            } catch (e: Exception) {
                Log.w(TAG, "write failed: ${e.message}")
            }
        }
    }

    private fun rotate(f: File) {
        try {
            val bytes = f.readBytes()
            val keep = bytes.copyOfRange(
                (bytes.size - KEEP_BYTES).toInt().coerceAtLeast(0),
                bytes.size,
            )
            // Trim to next newline so we don't start mid-line
            val firstNl = keep.indexOf('\n'.code.toByte()).let { if (it < 0) 0 else it + 1 }
            val header = "--- rotated ${fmt.format(Date())} ---\n".toByteArray()
            f.outputStream().use { out ->
                out.write(header)
                out.write(keep, firstNl, keep.size - firstNl)
            }
        } catch (e: Exception) {
            Log.w(TAG, "rotate failed: ${e.message}")
            try { f.delete() } catch (_: Exception) {}
        }
    }
}
