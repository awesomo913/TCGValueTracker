package com.owner.assist.vision

import android.util.Log
import com.owner.assist.data.AppLogger
import com.owner.assist.data.SecureKeyStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Routes a JPEG frame (base64) to:
 *   1. Pi vision server (YOLOv8m, port 8766) — fast, local, object detection
 *   2. Groq vision API (llama-3.2-11b-vision) — rich description, requires internet
 *
 * Pi server expected API:
 *   POST <piUrl>/detect   multipart/form-data  field: "image" (JPEG bytes)
 *   Response: {"detections":[{"class":"wrench","confidence":0.91},...]}
 *   OR any JSON with a top-level "detections" or "objects" array.
 *
 * If Pi is unreachable or returns an error, falls back to Groq automatically.
 */
object VisionClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Returns a short spoken-English description of the image.
     * [utterance] is the user's original phrase — used to focus the Groq prompt.
     */
    fun describe(base64Jpeg: String, utterance: String, keys: SecureKeyStore): String {
        val piUrl = keys.piVisionUrl.trim().trimEnd('/')
        if (piUrl.isNotBlank()) {
            try {
                val result = queryPi(base64Jpeg, piUrl)
                if (result.isNotBlank()) {
                    AppLogger.log("VISION", "Pi result: ${result.take(80)}")
                    return result
                }
            } catch (e: Exception) {
                Log.w(TAG, "Pi vision failed: ${e.message} — falling back to Groq")
                AppLogger.log("VISION", "Pi failed: ${e.javaClass.simpleName} ${e.message}")
            }
        }

        return try {
            val result = queryGroq(base64Jpeg, utterance, keys.groqKey)
            AppLogger.log("VISION", "Groq result: ${result.take(80)}")
            result
        } catch (e: Exception) {
            Log.w(TAG, "Groq vision failed: ${e.message}")
            AppLogger.log("VISION", "Groq failed: ${e.javaClass.simpleName} ${e.message}")
            "I couldn't identify what I'm looking at. Check your connection and try again."
        }
    }

    // ── Pi (YOLOv8 FastAPI) ───────────────────────────────────────────────────

    private fun queryPi(base64Jpeg: String, piUrl: String): String {
        val imageBytes = android.util.Base64.decode(base64Jpeg, android.util.Base64.NO_WRAP)
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "image", "frame.jpg",
                imageBytes.toRequestBody("image/jpeg".toMediaType()),
            )
            .build()

        val req = Request.Builder().url("$piUrl/detect").post(body).build()
        val resp = http.newCall(req).execute()
        if (!resp.isSuccessful) throw IOException("Pi HTTP ${resp.code}")
        val json = JSONObject(resp.body?.string() ?: throw IOException("empty Pi response"))

        val detections = json.optJSONArray("detections") ?: json.optJSONArray("objects")
            ?: return ""

        if (detections.length() == 0) return "Nothing notable detected."

        val items = buildList {
            for (i in 0 until detections.length()) {
                val d = detections.optJSONObject(i) ?: continue
                val label = d.optString("class").ifBlank { d.optString("label") }
                val conf = d.optDouble("confidence", 0.0)
                if (label.isNotBlank() && conf > 0.4) add("$label (${"%.0f".format(conf * 100)}%)")
            }
        }
        return if (items.isEmpty()) "Nothing identified with high confidence."
               else "I see: ${items.joinToString(", ")}."
    }

    // ── Groq vision (llama-3.2-11b) ──────────────────────────────────────────

    private fun queryGroq(base64Jpeg: String, utterance: String, groqKey: String): String {
        if (groqKey.isBlank()) throw IllegalStateException("Groq key not set")

        val prompt = buildGroqPrompt(utterance)
        val payload = JSONObject().apply {
            put("model", GROQ_VISION_MODEL)
            put("max_tokens", 150)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().apply {
                                put("url", "data:image/jpeg;base64,$base64Jpeg")
                            })
                        })
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", prompt)
                        })
                    })
                })
            })
        }

        val req = Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .addHeader("Authorization", "Bearer $groqKey")
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val resp = http.newCall(req).execute()
        if (!resp.isSuccessful) throw IOException("Groq HTTP ${resp.code}: ${resp.body?.string()?.take(200)}")
        val json = JSONObject(resp.body?.string() ?: throw IOException("empty Groq response"))
        return json.getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
            .trim()
    }

    private fun buildGroqPrompt(utterance: String): String {
        val lower = utterance.lowercase()
        return when {
            lower.contains("read") ->
                "Read any text, labels, or numbers visible in this image. Speak them clearly."
            lower.contains("tool") || lower.contains("part") ->
                "Identify any tools, parts, or equipment you see. Be specific — brand, type, condition if worn."
            lower.contains("hazard") || lower.contains("safe") ->
                "Identify any safety hazards, warnings, or unsafe conditions visible."
            else ->
                "Describe what you see in 1-2 sentences for a shop or warehouse technician. " +
                "Focus on tools, equipment, parts, labels, or anything relevant to repair or maintenance. " +
                "Be direct and practical."
        }
    }

    private const val TAG = "VisionClient"
    private const val GROQ_VISION_MODEL = "llama-3.2-11b-vision-preview"
}
