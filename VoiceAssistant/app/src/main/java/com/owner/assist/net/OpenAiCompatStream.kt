package com.owner.assist.net

import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

/**
 * Shared SSE streaming for OpenAI-compatible chat completion APIs.
 * Both Groq and DeepSeek implement the same wire format.
 *
 * Emits content delta strings as the model generates.
 */
internal object OpenAiCompatStream {

    private const val TAG = "OpenAiCompatStream"
    private val JSON_MEDIA = "application/json".toMediaType()

    fun stream(
        endpoint: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        userMessage: String,
        temperature: Double = 0.4,
        maxTokens: Int = 300,
        httpClient: OkHttpClient = SharedHttp.client,
    ): Flow<String> = callbackFlow {
        require(apiKey.isNotBlank()) { "LLM API key is blank" }


        val body = buildJsonObject {
            put("model", model)
            put("temperature", temperature)
            put("max_tokens", maxTokens)
            put("stream", true)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", userMessage)
                })
            })
        }.toString()

        val req = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody(JSON_MEDIA))
            .build()

        val source: EventSource = EventSources.createFactory(httpClient).newEventSource(
            req,
            object : EventSourceListener() {
                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    if (data == "[DONE]") {
                        close()
                        return
                    }
                    runCatching {
                        val obj = Json.parseToJsonElement(data).jsonObject
                        val delta = obj["choices"]
                            ?.jsonArray?.firstOrNull()
                            ?.jsonObject?.get("delta")
                            ?.jsonObject?.get("content")
                            ?.jsonPrimitive?.content
                        if (!delta.isNullOrEmpty()) trySend(delta)
                    }.onFailure {
                        Log.w(TAG, "delta parse error: ${it.message}")
                    }
                }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                    Log.w(TAG, "SSE failure: ${t?.message} http=${response?.code}")
                    close(t)
                }

                override fun onClosed(eventSource: EventSource) {
                    close()
                }
            },
        )

        awaitClose { source.cancel() }
    }
}

