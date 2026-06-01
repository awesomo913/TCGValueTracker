package com.owner.assist.net

import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * Streamed text-in / PCM-out using Deepgram Aura-2 over WebSocket.
 *
 * Wire format (Aura-2 WS, encoding=linear16, sample_rate=16000):
 *   client -> server:  text frame `{"type":"Speak","text":"..."}`
 *                      text frame `{"type":"Flush"}` to force generation
 *                      text frame `{"type":"Close"}` to end stream
 *   server -> client:  binary frames carrying raw PCM
 *                      occasional text frames (Metadata, Flushed) — ignore
 */
class DeepgramTtsClient(
    private val apiKey: String,
    private val httpClient: OkHttpClient = SharedHttp.client,
) {
    init {
        require(apiKey.isNotBlank()) { "Deepgram API key is blank" }
    }


    sealed interface Event {
        data class Audio(val pcm: ByteArray) : Event {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is Audio) return false
                return pcm.contentEquals(other.pcm)
            }
            override fun hashCode(): Int = pcm.contentHashCode()
        }
        data object Flushed : Event
        data class Error(val cause: Throwable) : Event
        data object Closed : Event
    }

    interface Session {
        fun speak(text: String)
        fun flush()
        fun close()
    }

    fun open(
        model: String = DEFAULT_MODEL,
        onSession: (Session) -> Unit,
    ): Flow<Event> = callbackFlow {

        val url = buildString {
            append("wss://api.deepgram.com/v1/speak")
            append("?model=").append(model)
            append("&encoding=linear16")
            append("&sample_rate=16000")
        }
        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Token $apiKey")
            .build()

        val ws = httpClient.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "TTS WS open")
                onSession(object : Session {
                    override fun speak(text: String) {
                        val payload = """{"type":"Speak","text":${escape(text)}}"""
                        webSocket.send(payload)
                    }
                    override fun flush() {
                        webSocket.send("""{"type":"Flush"}""")
                    }
                    override fun close() {
                        webSocket.send("""{"type":"Close"}""")
                        webSocket.close(1000, "client done")
                    }
                })
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("\"Flushed\"")) trySend(Event.Flushed)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                trySend(Event.Audio(bytes.toByteArray()))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "TTS WS failure: ${t.message}")
                trySend(Event.Error(t))
                close(t)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "TTS WS closed code=$code reason=$reason")
                trySend(Event.Closed)
                close()
            }
        })

        awaitClose { ws.close(1000, "flow cancelled") }
    }

    private fun escape(s: String): String {
        // JSON string escape — Kotlin serialization would also work but keeps deps tiny here.
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    companion object {
        private const val TAG = "DeepgramTtsClient"
        private const val DEFAULT_MODEL = "aura-2-thalia-en"
    }
}
