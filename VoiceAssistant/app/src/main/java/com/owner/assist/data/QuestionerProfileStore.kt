package com.owner.assist.data

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class QuestionerProfile(val name: String, val speakerId: Int, val avgRmsDb: Float)

object QuestionerProfileStore {
    private const val TAG = "QuestProfileStore"
    private const val FILE_NAME = "questioner_profiles.json"
    private const val RMS_WINDOW_DB = 10f

    fun add(ctx: Context, name: String, speakerId: Int, avgRmsDb: Float) {
        try {
            val file = file(ctx)
            val arr = if (file.exists()) JSONArray(file.readText()) else JSONArray()
            arr.put(
                JSONObject()
                    .put("name", name)
                    .put("speakerId", speakerId)
                    .put("avgRmsDb", avgRmsDb.toDouble())
            )
            file.writeText(arr.toString())
            Log.i(TAG, "Saved questioner: $name speaker=$speakerId rms=${"%.1f".format(avgRmsDb)}")
        } catch (e: Exception) {
            Log.w(TAG, "add failed: ${e.message}")
        }
    }

    fun list(ctx: Context): List<QuestionerProfile> = try {
        val file = file(ctx)
        if (!file.exists()) emptyList()
        else {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                QuestionerProfile(o.getString("name"), o.getInt("speakerId"), o.getDouble("avgRmsDb").toFloat())
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "list failed: ${e.message}")
        emptyList()
    }

    fun clear(ctx: Context) {
        try { file(ctx).delete() } catch (e: Exception) { Log.w(TAG, "clear failed: ${e.message}") }
    }

    fun nextName(ctx: Context): String = "Questioner ${list(ctx).size + 1}"

    /**
     * Returns the matching questioner name, or null if no profile matches.
     * Works with an empty list — returns null so callers can treat it as "unknown".
     */
    fun matchName(profiles: List<QuestionerProfile>, speakerId: Int, rmsDb: Float): String? {
        if (profiles.isEmpty() || speakerId < 0) return null
        return profiles.firstOrNull { p ->
            p.speakerId == speakerId &&
                rmsDb in (p.avgRmsDb - RMS_WINDOW_DB)..(p.avgRmsDb + RMS_WINDOW_DB)
        }?.name
    }

    private fun file(ctx: Context) = File(ctx.filesDir, FILE_NAME)
}
