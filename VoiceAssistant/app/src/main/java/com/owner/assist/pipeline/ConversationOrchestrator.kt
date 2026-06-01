package com.owner.assist.pipeline

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.owner.assist.audio.AudioPlayer
import com.owner.assist.audio.BluetoothScoManager
import com.owner.assist.audio.MicCapture
import com.owner.assist.data.SecureKeyStore
import com.owner.assist.net.DeepgramSttClient
import com.owner.assist.net.DeepgramTtsClient
import com.owner.assist.net.LlmProvider
import com.owner.assist.net.LlmProviderFactory
import com.owner.assist.net.TriageClient
import com.owner.assist.service.AssistantState
import com.owner.assist.service.AssistantStateBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Passive-monitor pipeline. Listens to everyone in the room, filters the user's
 * own voice via [SpeakerFilter], then for other-speaker utterances:
 *   1. Fast triage (Groq 8B, ~150ms) — decides RESPOND or SKIP.
 *   2. If RESPOND — streams a one-sentence whisper via the 70B model + Deepgram TTS.
 *
 * STT WebSocket auto-reconnects after Deepgram closes it (idle timeout ~90s).
 * The capture loop runs forever until [stop] cancels the root job.
 */
class ConversationOrchestrator(
    private val ctx: Context,
    parentScope: CoroutineScope,
    private val keys: SecureKeyStore,
) {
    private val mic = MicCapture(ctx)
    private val sco = BluetoothScoManager(ctx)
    private val player = AudioPlayer()
    private val gate = BargeInGate()
    val speakerFilter = SpeakerFilter()   // exposed so UI can trigger calibration
    private val utteranceCh = Channel<String>(capacity = Channel.BUFFERED)

    private val rootJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + rootJob)

    @Volatile private var llmProvider: LlmProvider? = null
    @Volatile private var providerCacheTag: String = ""
    @Volatile private var rollingRmsDb: Float = -60f
    @Volatile private var lastSpeakerId: Int = -1

    fun start() {
        if (!keys.isAvailable || !keys.keysComplete()) {
            Log.e(TAG, "keystore unavailable or keys missing — cannot start")
            AssistantStateBus.set(AssistantState.OFF)
            return
        }
        scope.launch {
            try {
                val route = sco.connect()
                Log.i(TAG, "Audio route: $route")
                AssistantStateBus.set(AssistantState.LISTENING)

                val capture = launch { captureLoop() }
                val reply = launch { replyLoop() }
                capture.join()
                reply.cancelAndJoin()
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Log.e(TAG, "orchestrator failed: ${t.message}", t)
            } finally {
                onCleanup()
            }
        }
    }

    fun stop() {
        rootJob.cancel()
        onCleanup()
    }

    /** Snapshot current speaker + volume as SELF. Call after user speaks for ~3s. */
    fun calibrateSelfNow() {
        speakerFilter.calibrateSelf(lastSpeakerId, rollingRmsDb)
    }

    // ──────────────────────────────────────────────────────────
    // Capture — always-on, auto-reconnects when STT WS closes
    // ──────────────────────────────────────────────────────────

    private suspend fun captureLoop() {
        if (!mic.hasPermission()) {
            Log.w(TAG, "RECORD_AUDIO not granted — cannot capture")
            return
        }
        val stt = DeepgramSttClient(keys.deepgramKey)
        try {
            while (coroutineContext.isActive) {
                var sttSession: DeepgramSttClient.Session? = null
                var finalAcc = StringBuilder()

                // One AudioRecord per STT session. Cancelled in finally before next connect.
                val micJob = scope.launch {
                    try {
                        mic.stream().collect { chunk ->
                            rollingRmsDb = calcRmsDb(chunk)
                            if (!gate.shouldSend()) return@collect
                            sttSession?.send(chunk)
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        Log.w(TAG, "mic error: ${e.message}")
                    }
                }

                try {
                    stt.open { session -> sttSession = session }.collect { ev ->
                        when (ev) {
                            is DeepgramSttClient.Event.Final -> {
                                finalAcc.append(ev.text).append(' ')
                                if (ev.speakerId >= 0) lastSpeakerId = ev.speakerId
                            }
                            DeepgramSttClient.Event.UtteranceEnd -> {
                                val text = finalAcc.toString().trim()
                                finalAcc = StringBuilder()
                                if (text.isNotBlank()) {
                                    val rms = rollingRmsDb
                                    val spk = lastSpeakerId
                                    if (speakerFilter.isSelf(spk, rms)) {
                                        Log.d(TAG, "SELF[spk=$spk rms=${"%.1f".format(rms)}dB] skip: ${text.take(40)}")
                                    } else {
                                        Log.i(TAG, "OTHER[spk=$spk rms=${"%.1f".format(rms)}dB]: ${text.take(60)}")
                                        AssistantStateBus.addEvent("Heard: ${text.take(50)}")
                                        utteranceCh.trySend(text)
                                    }
                                }
                            }
                            is DeepgramSttClient.Event.Error ->
                                Log.w(TAG, "STT error: ${ev.cause.message}")
                            DeepgramSttClient.Event.Closed ->
                                Log.i(TAG, "STT WS closed — reconnecting")
                            is DeepgramSttClient.Event.Partial -> Unit
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.w(TAG, "STT session failed: ${e.message}")
                } finally {
                    micJob.cancel()  // stop AudioRecord before reconnect or exit
                }

                if (coroutineContext.isActive) {
                    delay(RECONNECT_DELAY_MS)
                }
            }
        } finally {
            utteranceCh.close()
        }
    }

    // ──────────────────────────────────────────────────────────
    // Reply — triage then whisper
    // ──────────────────────────────────────────────────────────

    private suspend fun replyLoop() {
        for (heardText in utteranceCh) {
            if (!coroutineContext.isActive) break

            // Fast triage — bail early if it's not worth responding to
            val respond = TriageClient.shouldRespond(heardText, keys.groqKey)
            if (!respond) {
                Log.d(TAG, "TRIAGE SKIP: ${heardText.take(40)}")
                AssistantStateBus.addEvent("Skip")
                continue
            }
            Log.i(TAG, "TRIAGE RESPOND: ${heardText.take(60)}")
            AssistantStateBus.addEvent("Responding...")

            AssistantStateBus.set(AssistantState.THINKING)
            val t0 = SystemClock.elapsedRealtime()
            try {
                handleTurn(heardText, t0)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "turn error: ${e.message}")
            } finally {
                AssistantStateBus.set(AssistantState.LISTENING)
            }
        }
    }

    private suspend fun handleTurn(heardText: String, t0: Long) {
        val provider = resolveProvider()
        val tts = DeepgramTtsClient(keys.deepgramKey)
        val splitter = SentenceSplitter()
        var ttsSession: DeepgramTtsClient.Session? = null
        var firstAudio = true
        var firstToken = true
        var llmDone = false

        gate.openMic()
        AssistantStateBus.set(AssistantState.SPEAKING)
        player.start()

        val ttsJob = scope.launch {
            tts.open(model = TTS_MODEL) { session -> ttsSession = session }.collect { ev ->
                when (ev) {
                    is DeepgramTtsClient.Event.Audio -> {
                        if (firstAudio) {
                            Log.i(TAG, "Latency tts_first_byte=${SystemClock.elapsedRealtime() - t0}ms")
                            firstAudio = false
                        }
                        player.writeAsync(ev.pcm)
                    }
                    DeepgramTtsClient.Event.Flushed -> Unit
                    is DeepgramTtsClient.Event.Error -> Log.w(TAG, "TTS error: ${ev.cause.message}")
                    DeepgramTtsClient.Event.Closed -> Unit
                }
            }
        }

        try {
            provider.stream(SYSTEM_PROMPT, heardText).collect { delta ->
                if (firstToken) {
                    Log.i(TAG, "Latency llm_ttft=${SystemClock.elapsedRealtime() - t0}ms")
                    firstToken = false
                }
                splitter.feed(delta).forEach { sentence -> ttsSession?.speak(sentence) }
            }
            val tail = splitter.drain()
            if (tail.isNotEmpty()) ttsSession?.speak(tail)
            llmDone = true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "LLM stream error: ${e.message}")
        } finally {
            if (llmDone) ttsSession?.runCatching { flush() }
            delay(150)
            ttsSession?.runCatching { close() }
            ttsJob.join()
            delay(TAIL_DRAIN_MS)
            player.stop()
            gate.closeMic()
        }
    }

    // ──────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────

    private fun resolveProvider(): LlmProvider {
        val tag = keys.llmProvider.id + "|" + when (keys.llmProvider) {
            com.owner.assist.data.LlmChoice.GROQ -> keys.groqKey
            com.owner.assist.data.LlmChoice.DEEPSEEK -> keys.deepseekKey
        }.hashCode().toString()
        val cached = llmProvider
        if (cached != null && tag == providerCacheTag) return cached
        val fresh = LlmProviderFactory.fromSettings(keys)
        llmProvider = fresh
        providerCacheTag = tag
        return fresh
    }

    /**
     * Converts a 16-bit PCM chunk to dBFS, blended into a rolling average
     * (80% old value, 20% new) to smooth transients.
     */
    private fun calcRmsDb(pcm: ByteArray): Float {
        if (pcm.size < 2) return rollingRmsDb
        var sum = 0.0
        var i = 0
        while (i + 1 < pcm.size) {
            val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort()
            sum += sample.toDouble() * sample.toDouble()
            i += 2
        }
        val rms = sqrt(sum / (pcm.size / 2))
        val db = if (rms > 0.0) (20.0 * log10(rms / 32768.0)).toFloat() else -60f
        return rollingRmsDb * 0.8f + db * 0.2f
    }

    private fun onCleanup() {
        utteranceCh.close()
        player.stop()
        sco.release()
        AssistantStateBus.set(AssistantState.OFF)
    }

    companion object {
        private const val TAG = "Orchestrator"
        private const val TAIL_DRAIN_MS = 300L
        private const val RECONNECT_DELAY_MS = 2_000L
        private const val TTS_MODEL = "aura-2-luna-en"
        private const val SYSTEM_PROMPT =
            "You are a silent earpiece assistant. Someone near the user just said this. " +
            "Whisper one short sentence of useful context, fact, or suggestion to the user. " +
            "No preamble. No 'I think'. No markdown. Plain speech only."
    }
}
