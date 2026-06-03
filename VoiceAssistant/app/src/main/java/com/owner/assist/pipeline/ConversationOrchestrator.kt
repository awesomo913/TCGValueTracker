package com.owner.assist.pipeline

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.owner.assist.audio.AudioPlayer
import com.owner.assist.audio.BluetoothScoManager
import com.owner.assist.audio.MicCapture
import com.owner.assist.audio.writeWavHeader
import com.owner.assist.data.AppLogger
import com.owner.assist.data.AssistantMode
import com.owner.assist.data.ChatLogger
import com.owner.assist.data.NoteLogger
import com.owner.assist.data.QuestionerProfile
import com.owner.assist.data.QuestionerProfileStore
import com.owner.assist.data.SecureKeyStore
import com.owner.assist.net.LlmProvider
import com.owner.assist.net.LlmProviderFactory
import com.owner.assist.net.DeepgramSttClient
import com.owner.assist.net.DeepgramTtsClient
import com.owner.assist.net.TriageClient
import com.owner.assist.service.AssistantState
import com.owner.assist.service.AssistantStateBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.math.log10
import kotlin.math.sqrt

class ConversationOrchestrator(
    private val ctx: Context,
    parentScope: CoroutineScope,
    private val keys: SecureKeyStore,
) {
    private val mic = MicCapture(ctx)
    private val sco = BluetoothScoManager(ctx)
    private val player = AudioPlayer()
    private val gate = BargeInGate()
    val speakerFilter = SpeakerFilter()
    private val utteranceCh = Channel<String>(capacity = Channel.BUFFERED)

    private val rootJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + rootJob)

    @Volatile private var llmProvider: LlmProvider? = null
    @Volatile private var providerCacheTag: String = ""
    @Volatile private var rollingRmsDb: Float = -60f
    @Volatile private var lastSpeakerId: Int = -1

    // Calibration recording
    @Volatile private var isCalibrationRecording = false
    private val calibrationBuffer = ByteArrayOutputStream()

    // Session recording
    @Volatile private var isSessionRecording = false
    @Volatile private var sessionFileStream: FileOutputStream? = null
    @Volatile private var sessionFilePath: String = ""
    private var sessionPcmBytes = 0L

    // Glasses button: set by AssistantService on FORCE_RESPOND action
    val forceRespondNext = java.util.concurrent.atomic.AtomicBoolean(false)

    // Questioner profile tuning
    @Volatile private var isQuestionerTuning = false
    private val questionerTuneSamples = mutableListOf<Pair<Int, Float>>()

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
                AppLogger.log("SCO", "route=$route")
                AssistantStateBus.set(AssistantState.LISTENING)

                val capture = launch { captureLoop() }
                val reply = launch { replyLoop() }
                capture.join()
                reply.cancelAndJoin()
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Log.e(TAG, "orchestrator failed: ${t.message}", t)
                AppLogger.log("CRASH", "orchestrator: ${t.javaClass.simpleName} ${t.message}")
            } finally {
                onCleanup()
            }
        }
    }

    fun stop() {
        rootJob.cancel()
        onCleanup()
    }

    fun calibrateSelfNow() {
        speakerFilter.calibrateSelf(lastSpeakerId, rollingRmsDb)
    }

    suspend fun startCalibrationWindow(durationMs: Long = 3_500L) {
        synchronized(calibrationBuffer) { calibrationBuffer.reset() }
        isCalibrationRecording = true
        speakerFilter.beginCalibration()

        val endTime = SystemClock.elapsedRealtime() + durationMs
        while (SystemClock.elapsedRealtime() < endTime) {
            speakerFilter.addCalibrationSample(lastSpeakerId, rollingRmsDb)
            delay(150L)
        }
        val pcm = synchronized(calibrationBuffer) {
            isCalibrationRecording = false
            calibrationBuffer.toByteArray()
        }
        val count = speakerFilter.commitCalibration()
        Log.i(TAG, "Calibration window done: $count samples (${pcm.size} PCM bytes)")
        if (pcm.isNotEmpty()) {
            try {
                val file = File(ctx.filesDir, "calibration_voice.wav")
                file.outputStream().use { out ->
                    writeWavHeader(out, MicCapture.SAMPLE_RATE, 1, 16, pcm.size)
                    out.write(pcm)
                }
                keys.voiceCalibrationPath = file.absolutePath
                AssistantStateBus.addEvent("Voice sample saved")
                Log.i(TAG, "Calibration WAV saved: ${file.absolutePath} (${pcm.size}B)")
            } catch (e: Exception) {
                Log.w(TAG, "Calibration WAV save failed: ${e.message}")
            }
        }
    }

    /**
     * Records the next speaker (non-self) to build a questioner profile.
     * Auto-names the profile "Questioner N" (N = current count + 1).
     * Works with 0 existing profiles — caller can always add more.
     */
    suspend fun startQuestionerTuneWindow(durationMs: Long = 5_000L) {
        val name = QuestionerProfileStore.nextName(ctx)
        synchronized(questionerTuneSamples) { questionerTuneSamples.clear() }
        isQuestionerTuning = true
        AssistantStateBus.setTuningQuestioner(true)
        AssistantStateBus.addEvent("Listening for $name… (${"%.0f".format(durationMs / 1000.0)}s)")

        delay(durationMs)

        val samples = synchronized(questionerTuneSamples) {
            isQuestionerTuning = false
            questionerTuneSamples.toList().also { questionerTuneSamples.clear() }
        }
        AssistantStateBus.setTuningQuestioner(false)

        if (samples.isEmpty()) {
            Log.w(TAG, "Questioner tune: no samples captured")
            AssistantStateBus.addEvent("No speech detected — try again")
            return
        }
        val modalId = samples.filter { it.first >= 0 }
            .groupingBy { it.first }.eachCount()
            .maxByOrNull { it.value }?.key ?: -1
        val avgRms = samples.map { it.second }.average().toFloat()
        QuestionerProfileStore.add(ctx, name, modalId, avgRms)
        AssistantStateBus.addEvent("$name saved (speaker $modalId)")
        Log.i(TAG, "Questioner saved: $name speaker=$modalId rms=${"%.1f".format(avgRms)}")
    }

    fun startSessionRecording(): String {
        val sessDir = ctx.getExternalFilesDir(null)?.let { File(it, "sessions") }
            ?: File(ctx.filesDir, "sessions")
        sessDir.mkdirs()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val sessFile = File(sessDir, "session_$ts.wav")
        return try {
            val fos = FileOutputStream(sessFile)
            writeWavHeader(fos, MicCapture.SAMPLE_RATE, 1, 16, 0)
            sessionFileStream = fos
            sessionFilePath = sessFile.absolutePath
            sessionPcmBytes = 0L
            isSessionRecording = true
            sco.release()
            Log.i(TAG, "Session recording started → ${sessFile.absolutePath}")
            sessFile.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "Session recording start failed: ${e.message}")
            ""
        }
    }

    fun stopSessionRecording(): String {
        isSessionRecording = false
        val fos = sessionFileStream ?: return ""
        sessionFileStream = null
        val path = sessionFilePath.also { sessionFilePath = "" }
        val bytesWritten = sessionPcmBytes
        return try {
            fos.flush()
            fos.close()
            if (path.isEmpty() || bytesWritten <= 0) return ""
            java.io.RandomAccessFile(path, "rw").use { raf ->
                raf.seek(4); raf.write(int32le((36 + bytesWritten).toInt()))
                raf.seek(40); raf.write(int32le(bytesWritten.toInt()))
            }
            Log.i(TAG, "WAV header patched → $path")
            path
        } catch (e: Exception) {
            Log.w(TAG, "Session stop failed: ${e.message}")
            path.ifEmpty { "" }
        }
    }

    private fun int32le(n: Int) = byteArrayOf(
        (n and 0xFF).toByte(), ((n shr 8) and 0xFF).toByte(),
        ((n shr 16) and 0xFF).toByte(), ((n shr 24) and 0xFF).toByte(),
    )

    // ── Capture ──────────────────────────────────────────────────────────────

    private suspend fun captureLoop() {
        if (!mic.hasPermission()) {
            Log.w(TAG, "RECORD_AUDIO not granted")
            return
        }
        val stt = DeepgramSttClient(keys.deepgramKey)
        try {
            while (coroutineContext.isActive) {
                var sttSession: DeepgramSttClient.Session? = null
                var finalAcc = StringBuilder()

                val micJob = scope.launch {
                    try {
                        mic.stream().collect { chunk ->
                            rollingRmsDb = calcRmsDb(chunk)
                            AssistantStateBus.setAudioLevel(((rollingRmsDb + 60f) / 60f).coerceIn(0f, 1f))
                            if (isCalibrationRecording) {
                                synchronized(calibrationBuffer) { calibrationBuffer.write(chunk) }
                            }
                            if (isSessionRecording) {
                                try {
                                    sessionFileStream?.write(chunk)
                                    sessionPcmBytes += chunk.size
                                } catch (e: Exception) {
                                    Log.w(TAG, "Session write failed: ${e.message}")
                                }
                            }
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
                                // Feed questioner tune window if active
                                if (isQuestionerTuning && ev.speakerId >= 0) {
                                    synchronized(questionerTuneSamples) {
                                        questionerTuneSamples.add(ev.speakerId to rollingRmsDb)
                                    }
                                }
                            }
                            DeepgramSttClient.Event.UtteranceEnd -> {
                                val text = finalAcc.toString().trim()
                                finalAcc = StringBuilder()
                                if (text.isBlank()) return@collect

                                val rms = rollingRmsDb
                                val spk = lastSpeakerId
                                val isSelf = speakerFilter.isSelf(spk, rms)
                                val mode = keys.assistantMode

                                val forced = forceRespondNext.getAndSet(false)

                                val shouldRespond = forced || when (mode) {
                                    AssistantMode.PASSIVE  -> !isSelf   // others in the room
                                    AssistantMode.PERSONAL -> isSelf    // the user themselves
                                }

                                if (!shouldRespond) {
                                    Log.d(TAG, "SKIP[mode=$mode spk=$spk rms=${"%.1f".format(rms)}dB]: ${text.take(40)}")
                                    return@collect
                                }

                                // Note trigger intercept — save and skip LLM
                                val noteContent = extractNote(text)
                                if (noteContent != null) {
                                    NoteLogger.append(ctx, noteContent)
                                    AssistantStateBus.addEvent("Noted: ${noteContent.take(50)}")
                                    Log.i(TAG, "Note saved: ${noteContent.take(80)}")
                                    return@collect
                                }

                                Log.i(TAG, "[mode=$mode spk=$spk rms=${"%.1f".format(rms)}dB]: ${text.take(60)}")
                                val questionerName = QuestionerProfileStore.matchName(
                                    QuestionerProfileStore.list(ctx), spk, rms
                                )
                                val tagged = if (questionerName != null) "__FROM__$questionerName|$text" else text
                                val toSend = if (forced) "__FORCE__|$tagged" else tagged
                                AssistantStateBus.addEvent("Heard: ${text.take(50)}")
                                utteranceCh.trySend(toSend)
                            }
                            is DeepgramSttClient.Event.Error -> {
                                Log.w(TAG, "STT error: ${ev.cause.message}")
                                AppLogger.log("STT", "error: ${ev.cause.javaClass.simpleName} ${ev.cause.message}")
                            }
                            DeepgramSttClient.Event.Closed ->
                                Log.i(TAG, "STT WS closed — reconnecting")
                            is DeepgramSttClient.Event.Partial -> Unit
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.w(TAG, "STT session failed: ${e.message}")
                    AppLogger.log("STT", "session failed: ${e.javaClass.simpleName} ${e.message}")
                } finally {
                    micJob.cancel()
                }

                if (coroutineContext.isActive) delay(RECONNECT_DELAY_MS)
            }
        } finally {
            utteranceCh.close()
        }
    }

    // ── Reply ─────────────────────────────────────────────────────────────────

    private suspend fun replyLoop() {
        for (raw in utteranceCh) {
            if (!coroutineContext.isActive) break

            // Strip glasses-button force flag if present
            val forced = raw.startsWith("__FORCE__|")
            val heardText = if (forced) raw.removePrefix("__FORCE__|") else raw

            // Unpack optional questioner tag
            val (questionerName, utterance) = if (heardText.startsWith("__FROM__")) {
                val idx = heardText.indexOf('|')
                if (idx > 0) heardText.substring(8, idx) to heardText.substring(idx + 1)
                else null to heardText
            } else null to heardText

            val respond = if (forced || isTechnicalFastTrack(utterance)) {
                if (forced) {
                    Log.i(TAG, "FORCE: ${utterance.take(60)}")
                    AssistantStateBus.addEvent("Force: respond")
                } else {
                    Log.i(TAG, "FAST-TRACK: ${utterance.take(60)}")
                    AssistantStateBus.addEvent("Fast: respond")
                }
                true
            } else {
                val r = TriageClient.shouldRespond(utterance, keys.groqKey)
                if (!r) {
                    Log.d(TAG, "TRIAGE SKIP: ${utterance.take(40)}")
                    AssistantStateBus.addEvent("Skip")
                } else {
                    AssistantStateBus.addEvent("Responding…")
                }
                r
            }
            if (!respond) continue

            AssistantStateBus.set(AssistantState.THINKING)
            val t0 = SystemClock.elapsedRealtime()
            try {
                handleTurn(utterance, questionerName, t0)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "turn error: ${e.message}")
            } finally {
                AssistantStateBus.set(AssistantState.LISTENING)
            }
        }
    }

    private suspend fun handleTurn(heardText: String, questionerName: String?, t0: Long) {
        val provider = resolveProvider()
        val tts = DeepgramTtsClient(keys.deepgramKey)
        val splitter = SentenceSplitter()
        val sessionReady = CompletableDeferred<DeepgramTtsClient.Session>()
        val flushedCh = Channel<Unit>(Channel.UNLIMITED)
        val sentenceCh = Channel<String>(Channel.BUFFERED)
        val batchSize = (keys.responsivenessLevel / 10) + 1
        val maxThinkMs = keys.maxThinkTimeSec * 1_000L
        var firstAudio = true
        var firstToken = true
        val responseBuilder = StringBuilder()

        gate.openMic()
        AssistantStateBus.set(AssistantState.SPEAKING)
        player.start()

        val ttsJob = scope.launch {
            tts.open(model = TTS_MODEL) { session ->
                sessionReady.complete(session)
            }.collect { ev ->
                when (ev) {
                    is DeepgramTtsClient.Event.Audio -> {
                        if (firstAudio) {
                            Log.i(TAG, "Latency tts_first_byte=${SystemClock.elapsedRealtime() - t0}ms")
                            firstAudio = false
                        }
                        player.writeAsync(ev.pcm)
                    }
                    DeepgramTtsClient.Event.Flushed -> {
                        flushedCh.trySend(Unit)
                    }
                    is DeepgramTtsClient.Event.Error -> {
                        Log.w(TAG, "TTS error: ${ev.cause.message}")
                        AppLogger.log("TTS", "error: ${ev.cause.javaClass.simpleName} ${ev.cause.message}")
                        sessionReady.completeExceptionally(ev.cause)
                    }
                    else -> Unit
                }
            }
        }

        val relayJob = scope.launch {
            val session = try {
                withTimeout(TTS_CONNECT_TIMEOUT_MS) { sessionReady.await() }
            } catch (e: Exception) {
                Log.w(TAG, "TTS never opened: ${e.message}")
                return@launch
            }
            val batch = mutableListOf<String>()
            for (sentence in sentenceCh) {
                batch.add(sentence)
                if (batch.size >= batchSize) {
                    val text = batch.joinToString(" ")
                    batch.clear()
                    session.runCatching { speak(text) }.onFailure { Log.w(TAG, "TTS speak err: ${it.message}") }
                    session.runCatching { flush() }.onFailure { Log.w(TAG, "TTS flush err: ${it.message}") }
                    runCatching { withTimeout(8_000L) { flushedCh.receive() } }
                        .onFailure { Log.w(TAG, "TTS flush timeout") }
                }
            }
            if (batch.isNotEmpty()) {
                val text = batch.joinToString(" ")
                session.runCatching { speak(text) }.onFailure { Log.w(TAG, "TTS speak err: ${it.message}") }
                session.runCatching { flush() }.onFailure { Log.w(TAG, "TTS flush err: ${it.message}") }
                runCatching { withTimeout(15_000L) { flushedCh.receive() } }
                    .onFailure { Log.w(TAG, "TTS flush timeout final") }
            }
            session.runCatching { close() }.onFailure { Log.w(TAG, "TTS close err: ${it.message}") }
        }

        try {
            withTimeoutOrNull(maxThinkMs) {
                provider.stream(buildSystemPrompt(heardText, questionerName), heardText).collect { delta ->
                    if (firstToken) {
                        Log.i(TAG, "Latency llm_ttft=${SystemClock.elapsedRealtime() - t0}ms")
                        firstToken = false
                    }
                    responseBuilder.append(delta)
                    splitter.feed(delta).forEach { sentence -> sentenceCh.send(sentence) }
                }
            } ?: run {
                Log.w(TAG, "LLM hit maxThinkTimeSec=${keys.maxThinkTimeSec}s — truncating")
                AppLogger.log("LLM", "timeout hit maxThinkSec=${keys.maxThinkTimeSec}")
            }
            val tail = splitter.drain()
            if (tail.isNotEmpty()) {
                responseBuilder.append(tail)
                sentenceCh.send(tail)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "LLM stream error: ${e.message}")
            AppLogger.log("LLM", "stream error: ${e.javaClass.simpleName} ${e.message}")
        } finally {
            sentenceCh.close()
        }

        relayJob.join()
        ttsJob.join()
        val drainMs = player.remainingMs() + 200L
        if (drainMs > 50) delay(drainMs)
        player.stop()
        gate.closeMic()

        val responseText = responseBuilder.toString().trim()
        if (responseText.isNotEmpty()) {
            ChatLogger.log(ctx, heardText, responseText)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun resolveProvider(): LlmProvider {
        val tag = keys.llmProvider.id + "|" + when (keys.llmProvider) {
            com.owner.assist.data.LlmChoice.GROQ -> keys.groqKey
            com.owner.assist.data.LlmChoice.DEEPSEEK -> keys.deepseekKey
        }.hashCode().toString()
        val cached = llmProvider
        if (cached != null && tag == providerCacheTag) return cached
        val fresh = LlmProviderFactory.fromSettingsWithFallback(keys) { msg ->
            AssistantStateBus.addEvent(msg)
        }
        llmProvider = fresh
        providerCacheTag = tag
        return fresh
    }

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

    private fun isTechnicalFastTrack(text: String): Boolean {
        val words = text.trim().split("\\s+".toRegex())
        if (words.size < 4) return false
        val lower = text.lowercase()
        if (lower.contains('?')) return true
        val starters = listOf(
            "explain ", "describe ", "tell me ", "walk me through ",
            "how do you ", "what is ", "what are ", "what does ",
            "why does ", "why is ", "why are ",
            "define ", "compare ", "difference between ",
        )
        if (starters.any { lower.startsWith(it) }) return true
        return VEHICLE_DOMAIN_WORDS.any { lower.contains(it) }
    }

    /**
     * Detects a "let's remember …" style trigger. Returns the content to save, or null.
     * Works in both PASSIVE and PERSONAL mode since the trigger can come from any speaker.
     */
    private fun extractNote(text: String): String? {
        val lower = text.lowercase().trim()
        val triggers = listOf(
            "let's remember ", "lets remember ",
            "let me remember ",
            "remember this: ", "remember this ",
            "make a note ", "make note ",
            "add a note ", "add note ",
            "note that ", "jot down ",
        )
        for (trigger in triggers) {
            if (lower.startsWith(trigger)) {
                return text.drop(trigger.length).trimStart(':', '-', ' ').ifBlank { null }
            }
        }
        return null
    }

    private fun buildSystemPrompt(heardText: String, questionerName: String?): String {
        val lower = heardText.lowercase()
        val sb = StringBuilder()
        if (ENGINE_CYCLE_WORDS.any { lower.contains(it) }) {
            sb.append("Engine cycle order is intake → compression → power → exhaust. ")
            sb.append("Lead with stroke and cycle information when relevant. ")
        }
        if (questionerName != null) {
            sb.append("The person asking is $questionerName. ")
        }
        val custom = keys.contextBlurb.trim()
        sb.append(if (custom.isNotBlank()) custom else DEFAULT_SYSTEM_PROMPT)
        return sb.toString()
    }

    private fun onCleanup() {
        utteranceCh.close()
        player.stop()
        sco.release()
        AssistantStateBus.set(AssistantState.OFF)
        AssistantStateBus.setTuningQuestioner(false)
    }

    companion object {
        private const val TAG = "Orchestrator"
        private const val RECONNECT_DELAY_MS = 2_000L
        private const val TTS_CONNECT_TIMEOUT_MS = 4_000L
        private const val TTS_MODEL = "aura-2-luna-en"

        private const val DEFAULT_SYSTEM_PROMPT =
            "You are a hands-free earpiece assistant for a forklift, lift truck, and vehicle repair technician in a shop or warehouse. " +
            "Someone nearby just said this — give the technician a concise, accurate answer they can immediately use or act on. " +
            "Forklift, lift truck, propane, LPG, hydraulic, mast, cylinder, carriage, cherry picker, boom lift, electrical, or mechanical questions: " +
            "answer in 2-3 sentences — accuracy and practicality over brevity, real-world troubleshooting over theory. " +
            "Non-technical: 1 sentence. " +
            "No preamble. No 'I think'. No 'Great question'. No markdown. Plain spoken English only."

        private val VEHICLE_DOMAIN_WORDS = listOf(
            "forklift", "lift truck", "pallet jack", "cherry picker", "boom lift", "scissor lift",
            "mast", "carriage", "fork", "tine", "hydraulic", "cylinder", "pump", "hose", "fitting",
            "propane", "lpg", "regulator", "vaporizer",
            "counterweight", "load center", "overhead guard",
            "drive axle", "steer axle", "differential", "torque converter",
            "inching pedal", "service brake", "parking brake",
            "solid pneumatic", "cushion tire",
            "solenoid", "contactor", "battery charger",
            "error code", "fault code",
        )

        private val ENGINE_CYCLE_WORDS = listOf(
            "stroke", "cycle", "piston", "compression", "intake", "exhaust", "power stroke",
            "tdc", "bdc", "top dead center", "bottom dead center",
            "firing order", "spark plug", "carburetor", "ignition timing",
        )
    }
}
