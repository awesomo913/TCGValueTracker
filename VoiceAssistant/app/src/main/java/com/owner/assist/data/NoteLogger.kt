package com.owner.assist.data

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class NoteEntry(val timestamp: Long, val content: String)

object NoteLogger {
    private const val TAG = "NoteLogger"
    private const val FILE_NAME = "notebook.json"
    private const val MAX_ENTRIES = 500

    fun append(ctx: Context, content: String) {
        val trimmed = content.trim()
        if (trimmed.isBlank()) return
        try {
            val file = file(ctx)
            val arr = if (file.exists()) JSONArray(file.readText()) else JSONArray()
            arr.put(JSONObject().put("ts", System.currentTimeMillis()).put("text", trimmed))
            if (arr.length() > MAX_ENTRIES) {
                val kept = JSONArray()
                for (i in arr.length() - MAX_ENTRIES until arr.length()) kept.put(arr.get(i))
                file.writeText(kept.toString())
            } else {
                file.writeText(arr.toString())
            }
            Log.i(TAG, "Note saved: ${trimmed.take(60)}")
        } catch (e: Exception) {
            Log.w(TAG, "append failed: ${e.message}")
        }
    }

    fun list(ctx: Context): List<NoteEntry> = try {
        val file = file(ctx)
        if (!file.exists()) emptyList()
        else {
            val arr = JSONArray(file.readText())
            (arr.length() - 1 downTo 0).map { i ->
                val obj = arr.getJSONObject(i)
                NoteEntry(obj.getLong("ts"), obj.getString("text"))
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "list failed: ${e.message}")
        emptyList()
    }

    fun delete(ctx: Context, timestamp: Long) {
        try {
            val file = file(ctx)
            if (!file.exists()) return
            val arr = JSONArray(file.readText())
            val out = JSONArray()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                if (obj.getLong("ts") != timestamp) out.put(obj)
            }
            file.writeText(out.toString())
        } catch (e: Exception) {
            Log.w(TAG, "delete failed: ${e.message}")
        }
    }

    fun clear(ctx: Context) {
        try { file(ctx).delete() } catch (e: Exception) { Log.w(TAG, "clear failed: ${e.message}") }
    }

    private fun file(ctx: Context) = File(ctx.filesDir, FILE_NAME)
}
