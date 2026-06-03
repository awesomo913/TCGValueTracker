package com.owner.assist.data

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class ChatTurn(val ts: Long, val heard: String, val response: String)

/**
 * Appends every conversation turn to chat_log.jsonl in internal storage.
 * Newest first when read back. Rotates at MAX_ENTRIES to keep the file small.
 *
 * The file is accessible via adb pull or by the in-app log screen.
 * Claude can also see it during troubleshooting — pull with:
 *   adb exec-out run-as com.owner.assist cat files/chat_log.jsonl
 */
object ChatLogger {
    private const val TAG = "ChatLogger"
    private const val MAX_ENTRIES = 200
    private const val FILE_NAME = "chat_log.jsonl"
    private val json = Json { ignoreUnknownKeys = true }

    fun log(ctx: Context, heard: String, response: String) {
        try {
            val file = File(ctx.filesDir, FILE_NAME)
            val line = json.encodeToString(ChatTurn(System.currentTimeMillis(), heard, response.trim()))
            file.appendText(line + "\n")
            trimIfNeeded(file)
        } catch (e: Exception) {
            Log.w(TAG, "log failed: ${e.message}")
        }
    }

    fun readAll(ctx: Context): List<ChatTurn> {
        return try {
            val file = File(ctx.filesDir, FILE_NAME)
            if (!file.exists()) return emptyList()
            file.readLines()
                .filter { it.isNotBlank() }
                .mapNotNull { line -> runCatching { json.decodeFromString<ChatTurn>(line) }.getOrNull() }
                .reversed()
        } catch (e: Exception) {
            Log.w(TAG, "readAll failed: ${e.message}")
            emptyList()
        }
    }

    fun clear(ctx: Context) {
        try { File(ctx.filesDir, FILE_NAME).delete() } catch (e: Exception) {
            Log.w(TAG, "clear failed: ${e.message}")
        }
    }

    private fun trimIfNeeded(file: File) {
        val lines = try { file.readLines() } catch (_: Exception) { return }
        if (lines.size > MAX_ENTRIES) {
            file.writeText(lines.takeLast(MAX_ENTRIES).joinToString("\n") + "\n")
        }
    }
}
