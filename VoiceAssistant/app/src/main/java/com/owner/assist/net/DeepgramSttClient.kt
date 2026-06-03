package com.owner.assist.net

import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * Streams 16kHz PCM to Deepgram and emits transcript + utterance-end events.
 *
 * Endpoint params chosen for the latency budget:
 *   - model=nova-3-general: best accuracy/latency mix today
 *   - encoding=linear16, sample_rate=16000: matches MicCapture, no transcoding
 *   - interim_results=true: get partial transcripts as the user speaks
 *   - endpointing=300: 300ms of silence ends an utterance (final transcript fires)
 *   - utterance_end_ms=1000: emits a separate UtteranceEnd event after 1s gap
 *   - vad_events=true: speech-start markers if we want them later
 *
 * Send raw PCM as binary frames. Send `{"type":"CloseStream"}` text frame to flush.
 */
class DeepgramSttClient(
    private val apiKey: String,
    private val httpClient: OkHttpClient = SharedHttp.client,
) {
    init {
        require(apiKey.isNotBlank()) { "Deepgram API key is blank" }
    }


    sealed interface Event {
        data class Partial(val text: String) : Event
        data class Final(val text: String, val speakerId: Int = -1) : Event
        data object UtteranceEnd : Event
        data class Error(val cause: Throwable) : Event
        data object Closed : Event
    }

    interface Session {
        fun send(pcm: ByteArray)
        fun finish()
        fun close()
    }

    /**
     * Opens a Deepgram WS session. The returned Flow emits transcription events.
     * Caller writes PCM to the session via [open]'s callback.
     */
    fun open(onSession: (Session) -> Unit): Flow<Event> = callbackFlow {
        val url = buildString {
            append("wss://api.deepgram.com/v1/listen")
            append("?model=nova-3-general")
            append("&encoding=linear16")
            append("&sample_rate=16000")
            append("&channels=1")
            append("&interim_results=true")
            append("&endpointing=500")
            append("&utterance_end_ms=2000")
            append("&diarize=true")
            append("&vad_events=true")
            append("&smart_format=true")
        }
        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Token $apiKey")
            .build()

        val ws = httpClient.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "STT WS open")
                onSession(object : Session {
                    override fun send(pcm: ByteArray) {
                        webSocket.send(pcm.toByteString())
                    }
                    override fun finish() {
                        webSocket.send("""{"type":"CloseStream"}""")
                    }
                    override fun close() {
                        webSocket.close(1000, "client done")
                    }
                })
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val ev = parse(text) ?: return
                    trySend(ev)
                }.onFailure {
                    Log.w(TAG, "parse error: ${it.message}")
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Deepgram listen API only sends JSON text frames; ignore binary.
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "STT WS failure: ${t.message}")
                trySend(Event.Error(t))
                close()  // error already delivered as Event.Error; closing with cause would crash KeepAlive coroutine
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "STT WS closed code=$code reason=$reason")
                trySend(Event.Closed)
                close()
            }
        })

        // Deepgram closes idle connections after ~10s with no bytes received.
        // Send KeepAlive every 8s so the connection survives silence gaps.
        launch {
            while (isActive) {
                delay(8_000)
                ws.send("""{"type":"KeepAlive"}""")
            }
        }

        awaitClose {
            ws.cancel()  // force-close immediately; graceful close leaves reader alive long enough to crash on EOFException
        }
    }

    private fun parse(text: String): Event? {
        val json = Json.parseToJsonElement(text).jsonObject
        return when (json["type"]?.jsonPrimitive?.content) {
            "Results" -> parseResults(json)
            "UtteranceEnd" -> Event.UtteranceEnd
            "SpeechStarted" -> null
            "Metadata" -> null
            else -> null
        }
    }

    private fun parseResults(obj: Map<String, JsonElement>): Event? {
        val alternatives = obj["channel"]?.jsonObject?.get("alternatives")?.jsonArray ?: return null
        val firstAlt = alternatives.firstOrNull()?.jsonObject ?: return null
        val transcript = firstAlt["transcript"]?.jsonPrimitive?.content ?: return null
        if (transcript.isBlank()) return null
        val isFinal = obj["is_final"]?.jsonPrimitive?.content == "true"
        return if (isFinal) {
            Event.Final(transcript, speakerId = parseDominantSpeaker(firstAlt))
        } else {
            Event.Partial(transcript)
        }
    }

    /** Finds the speaker label that appears most often in the word-level diarization array. */
    private fun parseDominantSpeaker(alt: Map<String, JsonElement>): Int {
        val words = alt["words"]?.jsonArray ?: return -1
        val counts = mutableMapOf<Int, Int>()
        for (w in words) {
            val spk = w.jsonObject["speaker"]?.jsonPrimitive?.content?.toIntOrNull() ?: continue
            counts[spk] = (counts[spk] ?: 0) + 1
        }
        return counts.maxByOrNull { it.value }?.key ?: -1
    }

    companion object {
        private const val TAG = "DeepgramSttClient"
    }
}
