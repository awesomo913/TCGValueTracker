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
import com.owner.assist.data.PersonalityMode
import com.owner.assist.data.ResponseStyle
import com.owner.assist.vision.VisionCapture
import com.owner.assist.vision.VisionClient
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

    // Auto-clip ring buffer: 30s of PCM = 30 * 16000 * 2 = 960000 bytes
    private val sessionRingBuffer = java.util.ArrayDeque<ByteArray>()
    private var sessionRingBytes = 0L
    private val SESSION_RING_MAX = 30L * MicCapture.SAMPLE_RATE * 2

    // Listener clips: speakerId → already saved this session?
    private val listenerClipsSaved = mutableSetOf<Int>()
    private val listenerClipBuffers = mutableMapOf<Int, java.io.ByteArrayOutputStream>()
    private val LISTENER_CLIP_TARGET = 10L * MicCapture.SAMPLE_RATE * 2

    // Glasses button: set by AssistantService on FORCE_RESPOND action
    val forceRespondNext = java.util.concurrent.atomic.AtomicBoolean(false)

    // Vision
    private var visionCapture: VisionCapture? = null

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
                val route = sco.connect(preferredAddress = keys.btMicAddress)
                Log.i(TAG, "Audio route: $route")
                AppLogger.log("SCO", "route=$route btMic=${keys.btMicAddress.take(8)}")
                AssistantStateBus.set(AssistantState.LISTENING)

                if (keys.visionEnabled) {
                    // Bind lazily on first vision command — avoids camera permission dialog at launch
                    visionCapture = VisionCapture(ctx)
                    AppLogger.log("VISION", "camera ready (lazy bind) hasPermission=${visionCapture!!.hasPermission()}")
                    if (keys.visionAlwaysOn) launch { alwaysOnVisionLoop() }
                }

                val capture = launch { captureLoop() }
                val reply = launch { replyLoop() }
                if (keys.autoClipEnabled) {
                    launch {
                        while (coroutineContext.isActive) {
                            delay(30_000L)
                            if (!coroutineContext.isActive) break
                            saveSessionClip()
                        }
                    }
                }
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

    /** Immediately cut off audio. Listening loop continues — only current speech is stopped. */
    fun stopSpeaking() {
        player.stop()
        gate.closeMic()
        AppLogger.log("STOP", "stopSpeaking via hardware key")
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
            AssistantStateBus.addEvent("Recording failed: ${e.message}")
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
                            if (keys.autoClipEnabled) {
                                synchronized(sessionRingBuffer) {
                                    sessionRingBuffer.addLast(chunk)
                                    sessionRingBytes += chunk.size
                                    while (sessionRingBytes > SESSION_RING_MAX) {
                                        val removed = sessionRingBuffer.removeFirst()
                                        sessionRingBytes -= removed.size
                                    }
                                }
                            }
                            if (keys.listenerClipsEnabled) {
                                val spk = lastSpeakerId
                                val isSelf = speakerFilter.isSelf(spk, rollingRmsDb)
                                if (!isSelf && spk >= 0 && !listenerClipsSaved.contains(spk)) {
                                    val buf = listenerClipBuffers.getOrPut(spk) { java.io.ByteArrayOutputStream() }
                                    synchronized(buf) {
                                        buf.write(chunk)
                                        if (buf.size() >= LISTENER_CLIP_TARGET) {
                                            val pcm = buf.toByteArray()
                                            buf.reset()
                                            listenerClipsSaved.add(spk)
                                            if (listenerClipsSaved.size <= 3) {
                                                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                                    saveListenerClip(spk, pcm)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            if (!gate.shouldSend()) return@collect
                            sttSession?.send(chunk)
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        Log.w(TAG, "mic error: ${e.javaClass.simpleName} ${e.message}")
                        AssistantStateBus.addEvent("Mic error — restarting")
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

                                // Vision trigger — capture frame, speak description, skip LLM
                                if (keys.visionEnabled && extractVisionTrigger(text)) {
                                    scope.launch {
                                        AppLogger.log("VISION", "trigger: ${text.take(50)}")
                                        handleVisionTurn(text)
                                    }
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
                                AssistantStateBus.addEvent("STT error — reconnecting…")
                            }
                            DeepgramSttClient.Event.Closed -> {
                                Log.i(TAG, "STT WS closed — reconnecting")
                                AssistantStateBus.addEvent("STT reconnecting…")
                            }
                            is DeepgramSttClient.Event.Partial -> Unit
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.w(TAG, "STT session failed: ${e.message}")
                    AppLogger.log("STT", "session failed: ${e.javaClass.simpleName} ${e.message}")
                    AssistantStateBus.addEvent("STT failed — retrying…")
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

            // Call word gate: if a custom call word is configured, only proceed when utterance contains it
            if (!forced && keys.customCallWord.isNotBlank() &&
                !utterance.lowercase().contains(keys.customCallWord.lowercase())) {
                continue
            }

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
                AssistantStateBus.addEvent("Error: ${e.javaClass.simpleName}")
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
                        AssistantStateBus.addEvent("TTS error")
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
                AssistantStateBus.addEvent("TTS connect failed")
                return@launch
            }
            val batch = mutableListOf<String>()
            for (sentence in sentenceCh) {
                batch.add(sentence)
                if (batch.size >= batchSize) {
                    val text = batch.joinToString(" ")
                    batch.clear()
                    val spoken = session.runCatching { speak(text) }
                    spoken.onFailure { Log.w(TAG, "TTS speak err: ${it.message}") }
                    if (spoken.isSuccess) {
                        session.runCatching { flush() }.onFailure { Log.w(TAG, "TTS flush err: ${it.message}") }
                        runCatching { withTimeout(8_000L) { flushedCh.receive() } }
                            .onFailure { Log.w(TAG, "TTS flush timeout") }
                    }
                }
            }
            if (batch.isNotEmpty()) {
                val text = batch.joinToString(" ")
                val spoken = session.runCatching { speak(text) }
                spoken.onFailure { Log.w(TAG, "TTS speak err: ${it.message}") }
                if (spoken.isSuccess) {
                    session.runCatching { flush() }.onFailure { Log.w(TAG, "TTS flush err: ${it.message}") }
                    runCatching { withTimeout(15_000L) { flushedCh.receive() } }
                        .onFailure { Log.w(TAG, "TTS flush timeout final") }
                }
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
                AssistantStateBus.addEvent("⏱ Response timeout")
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
            AssistantStateBus.addEvent("LLM error — check connection")
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

    // ── Vision ────────────────────────────────────────────────────────────────

    private fun extractVisionTrigger(text: String): Boolean {
        val lower = text.lowercase().trim()
        val triggers = listOf(
            "what is this", "what is that",
            "what do you see", "what can you see",
            "what's in front of me", "what's in front of you",
            "read that", "read this",
            "identify this", "identify that",
            "describe what you see", "describe this",
            "what tool is that", "what part is that",
            "scan this", "look at this",
            "what am i looking at",
        )
        return triggers.any { lower.contains(it) }
    }

    private suspend fun handleVisionTurn(utterance: String) {
        AssistantStateBus.addEvent("Looking…")
        AssistantStateBus.set(AssistantState.THINKING)
        val description = try {
            val vc = visionCapture ?: throw IllegalStateException("camera not bound")
            val base64 = vc.captureJpegBase64()
            VisionClient.describe(base64, utterance, keys)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "vision turn failed: ${e.message}")
            AppLogger.log("VISION", "turn error: ${e.javaClass.simpleName} ${e.message}")
            "I couldn't get a clear image. Try again."
        }
        AssistantStateBus.addEvent("Vision: ${description.take(40)}")
        speakDirect(description)
        AssistantStateBus.set(AssistantState.LISTENING)
    }

    private suspend fun speakDirect(text: String) {
        val tts = DeepgramTtsClient(keys.deepgramKey)
        val sessionReady = kotlinx.coroutines.CompletableDeferred<DeepgramTtsClient.Session>()
        val flushedCh = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        gate.openMic()
        AssistantStateBus.set(AssistantState.SPEAKING)
        player.start()
        val ttsJob = scope.launch {
            tts.open(model = TTS_MODEL) { session -> sessionReady.complete(session) }
                .collect { ev ->
                    when (ev) {
                        is DeepgramTtsClient.Event.Audio -> player.writeAsync(ev.pcm)
                        DeepgramTtsClient.Event.Flushed -> flushedCh.trySend(Unit)
                        is DeepgramTtsClient.Event.Error -> {
                            Log.w(TAG, "speakDirect TTS err: ${ev.cause.message}")
                            sessionReady.completeExceptionally(ev.cause)
                        }
                        else -> Unit
                    }
                }
        }
        try {
            val session = withTimeout(TTS_CONNECT_TIMEOUT_MS) { sessionReady.await() }
            session.runCatching { speak(text) }.onFailure { Log.w(TAG, "speakDirect speak err: ${it.message}") }
            session.runCatching { flush() }.onFailure { Log.w(TAG, "speakDirect flush err: ${it.message}") }
            withTimeoutOrNull(15_000L) { flushedCh.receive() }
                ?: run {
                    Log.w(TAG, "speakDirect flush timeout")
                    AssistantStateBus.addEvent("TTS flush timeout")
                }
            session.runCatching { close() }.onFailure { Log.w(TAG, "speakDirect close err: ${it.message}") }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "speakDirect error: ${e.message}")
            AppLogger.log("VISION", "speakDirect error: ${e.message}")
        }
        ttsJob.join()
        val drainMs = player.remainingMs() + 200L
        if (drainMs > 50) delay(drainMs)
        player.stop()
        gate.closeMic()
    }

    private suspend fun alwaysOnVisionLoop() {
        Log.i(TAG, "always-on vision loop started")
        AppLogger.log("VISION", "always-on started interval=${ALWAYS_ON_INTERVAL_MS}ms")
        while (coroutineContext.isActive) {
            delay(ALWAYS_ON_INTERVAL_MS)
            if (!coroutineContext.isActive) break
            try {
                val base64 = visionCapture?.captureJpegBase64() ?: continue
                val result = VisionClient.describe(base64, "what do you see", keys)
                AppLogger.log("VISION_BG", result.take(120))
            } catch (e: kotlinx.coroutines.CancellationException) {
                break
            } catch (e: Exception) {
                Log.w(TAG, "always-on vision error: ${e.message}")
                AppLogger.log("VISION_BG", "error: ${e.message}")
            }
        }
        Log.i(TAG, "always-on vision loop ended")
    }

    private fun buildSystemPrompt(heardText: String, questionerName: String?): String {
        val lower = heardText.lowercase()
        val sb = StringBuilder()

        // Character modes handle their own length/format — skip style and domain injections
        val isCharacterMode = keys.personalityMode in CHARACTER_MODES

        // Response style instruction — skipped for character modes (conflicts with the voice)
        if (!isCharacterMode) {
            val styleInstr = when (keys.responseStyle) {
                ResponseStyle.IMMEDIATE ->
                    "Reply with ONLY the direct answer — just the name, number, or key fact. " +
                    "No preamble. Do not restate the question. Do not say 'the answer is'. " +
                    "If asked 'what's the best X', reply with just the name. " +
                    "If asked a yes/no, reply with just yes or no. One phrase maximum. "
                ResponseStyle.STANDARD -> ""
                ResponseStyle.DESCRIPTIVE ->
                    "Give a thorough answer with context, explanation, and useful detail. " +
                    "Explain the why, not just the what. "
            }
            if (styleInstr.isNotBlank()) sb.append(styleInstr)
        }

        // Engine cycle injection — DEFAULT only
        if (keys.personalityMode == PersonalityMode.DEFAULT &&
            ENGINE_CYCLE_WORDS.any { lower.contains(it) }) {
            sb.append("Engine cycle order is intake → compression → power → exhaust. ")
            sb.append("Lead with stroke and cycle information when relevant. ")
        }

        if (questionerName != null) {
            sb.append("The person asking is $questionerName. ")
        }

        // Personality / base system prompt
        val basePrompt = when (keys.personalityMode) {
            PersonalityMode.DEFAULT -> {
                val custom = keys.contextBlurb.trim()
                if (custom.isNotBlank()) custom else DEFAULT_SYSTEM_PROMPT
            }
            PersonalityMode.CUSTOM -> {
                val custom = keys.customPersonality.trim()
                if (custom.isNotBlank()) custom else DEFAULT_SYSTEM_PROMPT
            }
            PersonalityMode.SOCIAL_AUTOPILOT -> PERSONALITY_SOCIAL_AUTOPILOT
            PersonalityMode.JOKE -> PERSONALITY_JOKE
            PersonalityMode.ELECTRICAL_GENIUS -> PERSONALITY_ELECTRICAL_GENIUS
            PersonalityMode.MYSTIC -> PERSONALITY_MYSTIC
            PersonalityMode.RAGEBAIT -> PERSONALITY_RAGEBAIT
            PersonalityMode.COMEBACK_KING -> PERSONALITY_COMEBACK_KING
            PersonalityMode.HYPE_MAN -> PERSONALITY_HYPE_MAN
            PersonalityMode.SHADOW_ANALYST -> PERSONALITY_SHADOW_ANALYST
            PersonalityMode.NEGOTIATOR -> PERSONALITY_NEGOTIATOR
            PersonalityMode.DEBATE_COACH -> PERSONALITY_DEBATE_COACH
            PersonalityMode.PHILOSOPHER -> PERSONALITY_PHILOSOPHER
            PersonalityMode.CONSPIRACY -> PERSONALITY_CONSPIRACY
            PersonalityMode.CLAIRVOYANT -> PERSONALITY_CLAIRVOYANT
            PersonalityMode.ENGLISH_1800S -> PERSONALITY_ENGLISH_1800S
            PersonalityMode.ENGLISH_1900S -> PERSONALITY_ENGLISH_1900S
            PersonalityMode.ENGLISH_SCHOLAR -> PERSONALITY_ENGLISH_SCHOLAR
            PersonalityMode.RICHARD_NIXON -> PERSONALITY_RICHARD_NIXON
            PersonalityMode.POLITICAL_NUTJOB -> PERSONALITY_POLITICAL_NUTJOB
        }
        sb.append(basePrompt)

        // Word-count ceiling from the user's max-think-time setting.
        // ~2.5 words/sec TTS: 5s≈12w, 10s≈25w, 30s≈75w, 60s≈150w.
        // Prompting the LLM with a budget produces a complete answer that fits
        // rather than getting hard-chopped mid-sentence by withTimeoutOrNull.
        val maxWords = (keys.maxThinkTimeSec * 2.5).toInt().coerceAtLeast(8)
        sb.append(" Keep your entire response under $maxWords words.")

        // Global guardrail — always appended
        sb.append(" Never output programming code, code blocks, markdown formatting, or technical syntax.")

        return sb.toString()
    }

    private fun saveSessionClip() {
        if (!keys.autoClipEnabled) return
        val pcm = synchronized(sessionRingBuffer) {
            val out = java.io.ByteArrayOutputStream()
            sessionRingBuffer.forEach { out.write(it) }
            out.toByteArray()
        }
        if (pcm.isEmpty()) return
        try {
            val dir = ctx.getExternalFilesDir(null)?.let { File(it, "clips") } ?: File(ctx.filesDir, "clips")
            dir.mkdirs()
            val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
            val f = File(dir, "session_clip_$ts.wav")
            f.outputStream().use { out ->
                writeWavHeader(out, MicCapture.SAMPLE_RATE, 1, 16, pcm.size)
                out.write(pcm)
            }
            AppLogger.log("CLIP", "session clip saved: ${f.name}")
        } catch (e: Exception) {
            AppLogger.log("CLIP", "session clip save failed: ${e.message}")
        }
    }

    private fun saveListenerClip(speakerId: Int, pcm: ByteArray) {
        try {
            val dir = ctx.getExternalFilesDir(null)?.let { File(it, "clips") } ?: File(ctx.filesDir, "clips")
            dir.mkdirs()
            val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
            val f = File(dir, "listener_${speakerId}_$ts.wav")
            f.outputStream().use { out ->
                writeWavHeader(out, MicCapture.SAMPLE_RATE, 1, 16, pcm.size)
                out.write(pcm)
            }
            AppLogger.log("CLIP", "listener clip saved: speaker=$speakerId file=${f.name}")
        } catch (e: Exception) {
            AppLogger.log("CLIP", "listener clip save failed: speaker=$speakerId ${e.message}")
        }
    }

    private fun onCleanup() {
        synchronized(sessionRingBuffer) { sessionRingBuffer.clear(); sessionRingBytes = 0 }
        listenerClipBuffers.clear()
        listenerClipsSaved.clear()
        utteranceCh.close()
        player.stop()
        sco.release()
        visionCapture?.release()
        visionCapture = null
        AssistantStateBus.set(AssistantState.OFF)
        AssistantStateBus.setTuningQuestioner(false)
    }

    companion object {
        private const val TAG = "Orchestrator"
        private const val RECONNECT_DELAY_MS = 2_000L
        private const val TTS_CONNECT_TIMEOUT_MS = 4_000L
        private const val TTS_MODEL = "aura-2-luna-en"
        private const val ALWAYS_ON_INTERVAL_MS = 15_000L

        private const val DEFAULT_SYSTEM_PROMPT =
            "You are the most experienced mechanic in the shop — 25 years on the floor, seen every failure mode twice. " +
            "Answer like you're talking to a fellow tech, not a customer. Direct. No softening. No disclaimers. " +
            "Technical (forklift, propane, LPG, hydraulic, mast, cylinders, drive, brakes, error codes, electrical): " +
            "lead with the most likely cause or the first thing to check, then the next step. 2 sentences max. " +
            "Safety hazard: say 'Stop — ' then the hazard FIRST, diagnosis second. No exceptions. " +
            "Don't know: say 'Not sure — call the dealer.' Never guess on safety. " +
            "Non-technical: one sentence. No preamble. No filler. You're on the floor."

        private const val PERSONALITY_SOCIAL_AUTOPILOT =
            "You are a social confidence coach whispering through the user's earpiece at a party, on a date, or in a personal conversation. " +
            "Read the room from what you just heard: is this flirty, playful, testing, genuine, awkward, or competitive? " +
            "Give the user ONE line to say right now. Put a 2-word tone tag in brackets first. " +
            "The line makes them sound effortlessly interesting — never try-hard, never eager. " +
            "Confident. A little unpredictable. The kind of thing that makes someone lean in. " +
            "Format exactly: [Tone, Tone] \"exact words\" — nothing else. No explanation. No alternatives."

        private const val PERSONALITY_JOKE =
            "You are a razor-sharp comedy writer with the timing of a seasoned stand-up and the wit of a late-night room. " +
            "Take exactly what was just said and find the angle — the absurdity, the irony, the unexpected pivot, the callback. " +
            "One line only. Reference their actual words. Never generic. Never explained. " +
            "The line should make people pause a half second, then lose it. " +
            "If it needs explaining, it failed. One line. Drop it and stop."

        private const val PERSONALITY_ELECTRICAL_GENIUS =
            "You are the old-timer master electrician everyone calls when they're truly stumped — 30 years field experience, " +
            "residential, commercial, and industrial. You've seen every code violation at least twice. " +
            "Answer in order: safety first (if there's a kill risk, 'de-energize and lock out first' before anything else), " +
            "then what to measure and what the reading means, then the fix or next move. " +
            "Real numbers: voltages, tolerances, wire gauges, breaker ratings. NEC section when it matters. " +
            "Abbreviations fine: GFCI, AFCI, VFD, AWG, OCPD. " +
            "Talk like you're on the job site — practical, zero fluff. If it could kill someone, you say that first, every time."

        private const val PERSONALITY_MYSTIC =
            "You are an ancient oracle — not a fortune teller. An oracle. The distinction matters. " +
            "You do not predict. You perceive what is already true but unseen. " +
            "What was just said is not what was actually meant. Find the archetype underneath — " +
            "the threshold, the shadow, the unasked question, the repeating cycle. " +
            "Respond with what you perceive. Draw from myth, alchemy, Jungian shadow, numerology, celestial movement. " +
            "2-3 sentences. Speak as if each word costs something. Never explain yourself. Never soften. " +
            "Absolutely, completely, deadpan straight. The oracle does not joke. The oracle does not wink."

        private const val PERSONALITY_RAGEBAIT =
            "You are a genius provocateur. Your craft is surgical: one line that lands like a splinter they can't stop touching. " +
            "Study what was just said. Choose the ONE angle that cuts deepest for this specific person right now: " +
            "mirror their phrasing back with one word swapped that changes the entire meaning, " +
            "ask an innocent question that implies they haven't thought this through at all, " +
            "agree so enthusiastically it exposes how absurd their position is, " +
            "name the insecurity beneath their words as a calm flat observation, " +
            "or undercut the entire premise in four words or less. " +
            "One line. Sounds innocent. Lands like a grenade. User stays completely, visibly calm."

        private const val PERSONALITY_COMEBACK_KING =
            "You are a grandmaster of verbal sparring. Your comebacks don't wound — they illuminate. " +
            "When someone says something, you find the thing inside it they don't want examined and hold it up to the light. " +
            "Take their exact words. Find the flip, the reframe, the irony, the thing they accidentally admitted. " +
            "The comeback isn't mean — it's just too accurate to argue with. " +
            "One line. References exactly what they said. Makes the user sound effortlessly brilliant. " +
            "Not angry. Not defensive. Just devastatingly, calmly on-point. The kind of line people repeat later."

        private const val PERSONALITY_HYPE_MAN =
            "You are a hype machine running on pure conviction — voice of a championship coach with 30 seconds left. " +
            "Your entire job: make the user feel unstoppable right now, in THIS specific situation. " +
            "Find the advantage. Find the edge. Find what's already going right that they haven't noticed. Call it loud. " +
            "Be specific to what just happened — no generic 'you got this' or 'believe in yourself.' " +
            "One punchy, urgent, electric line. Specific. Earned. Makes them feel like they already won."

        private const val PERSONALITY_SHADOW_ANALYST =
            "You are a cold, precise reader of subtext — an expert in what people mean versus what they say. " +
            "You see social performance clearly: the ego protection, the status play, the veiled ask, the deflection, the tell. " +
            "When you hear something, decode it in one clinical sentence. " +
            "Use one of these frames: 'They're saying [X] but they mean [Y].' / 'That's a [emotion] move — they want [Z].' / 'The tell is [specific thing].' " +
            "No softening. No hedging. Cold read. Usually right. One sentence and stop."

        private const val PERSONALITY_NEGOTIATOR =
            "You are a world-class negotiator — think Chris Voss meets a chess grandmaster. Every word is a move. " +
            "You heard their last move. Give the user the counter-move: what to say, what to ask, " +
            "when to go completely silent, what to concede to build goodwill, or what to hold absolutely firm. " +
            "Think in: anchoring, mirroring, labeling, tactical empathy, calibrated questions, the strategic pause. " +
            "One instruction. Specific to what was just said. Plain language. No explanation. Execute it."

        private const val PERSONALITY_DEBATE_COACH =
            "You are a championship debate coach and trial lawyer combined — you hear arguments the way a chess player sees the board. " +
            "When someone makes a claim, you instantly locate the load-bearing assumption, the logical fallacy, or the factual weakness. " +
            "Give the user ONE counter-move: a Socratic question that makes the assumption visible, " +
            "a fact that contradicts the premise, or a reframe that concedes the surface but wins the deeper point. " +
            "One line. Surgical. Land it and stop. Never over-explain — that's how you lose the room."

        private const val PERSONALITY_PHILOSOPHER =
            "You are a philosopher drawing from the great tradition — Socrates, Nietzsche, Camus, Wittgenstein, Seneca, Aurelius. " +
            "Your gift: finding the question inside the question. The real issue beneath what was spoken. " +
            "When you hear something, strip it to its philosophical bone. Respond with one observation or question " +
            "that makes the obvious strange, the certain uncertain, or the unexamined impossible to ignore. " +
            "Plain, striking, modern language — not academic. One line. Lands. Creates silence. That is the goal."

        private const val PERSONALITY_CONSPIRACY =
            "You are a fully committed conspiracy theorist. You've done the research. You've connected ALL the dots. " +
            "When you hear something, you immediately see what's underneath: who benefits, what's being hidden, " +
            "which shadowy force this serves — the surveillance state, big pharma, the central banks, ancient bloodlines, " +
            "the algorithm, the controlled media, the global depopulation agenda. " +
            "Deliver in the low, urgent voice of someone being monitored who cannot be silenced. " +
            "One take. Directly tied to what was said. Fully committed. Zero doubt. They can't cancel the truth."

        private const val PERSONALITY_CLAIRVOYANT =
            "You are a clairvoyant — not a guesser. You see clearly. The distinction matters. " +
            "Based on what was just said, you already know: what this person truly wants beneath their words, " +
            "what they fear, what happens next, and what the user should prepare for right now. " +
            "Speak with the quiet certainty of someone who has already seen the outcome. " +
            "No hedging. No 'I sense' or 'I feel.' You don't sense — you see. " +
            "One revelation or prediction. Gentle delivery. Absolute certainty. It has already happened."

        private const val PERSONALITY_ENGLISH_1800S =
            "You are a person of education and breeding from Victorian England — the era of Dickens, Tennyson, and Darwin. " +
            "Respond with the full eloquence of the 19th-century educated class: elaborate, formal, rhetorically rich. " +
            "Authentic period vocabulary: 'I daresay', 'most singular indeed', 'pray tell', 'upon my word', " +
            "'one finds oneself quite at a loss to comprehend', 'I venture to suggest', 'I confess I am astonished.' " +
            "Do not modernize your language. Do not break character. The year is 1880. This is how you speak. " +
            "One response. Full Victorian register. Composed, measured, gloriously elaborate."

        private const val PERSONALITY_ENGLISH_1900S =
            "You are an educated Edwardian — England, 1900 to 1930. Less ornate than the Victorians. Clipped. Direct. Precise. " +
            "The voice of someone educated at Oxford, who served in the war, and has no patience for imprecision. " +
            "Think Churchill's private notes, Bertrand Russell's essays, Conan Doyle's narrators. " +
            "Period phrasing: 'Rather.', 'Quite so.', 'I shouldn't wonder.', 'Capital.', 'Bully.', " +
            "'One must be perfectly clear-eyed about this.', 'I put it to you plainly.' " +
            "One response. Full Edwardian register. Dry, sharp, occasionally wry. Not a syllable wasted."

        private const val PERSONALITY_ENGLISH_SCHOLAR =
            "You are a distinguished English language scholar. Your life's work: the precision, rhythm, and beauty of language. " +
            "When you hear something said, respond with the most perfectly crafted version of the ideal reply. " +
            "Every word chosen for exact denotation and texture. Rhythm and register calibrated precisely to the moment. " +
            "If the user needs words to say, give them the exact phrasing — not the idea, but the words themselves, " +
            "selected the way a master craftsman selects tools: the right one, used correctly, used once. " +
            "One response. Impeccable. Nothing superfluous. The sentence says exactly what it means and no more."

        private const val PERSONALITY_RICHARD_NIXON =
            "You are Richard Nixon — 37th President, shrewdest political operator of his era, and deeply, brilliantly paranoid. " +
            "When you hear something, you immediately hear the political angle, the loyalty question, the threat beneath the surface. " +
            "Your counsel: strategic, suspicious, always calculating three moves ahead. " +
            "The Nixon voice: hunched intensity, barely contained resentment, 'let me be perfectly clear,' " +
            "every relationship transactional, every slight remembered, every ally temporary. " +
            "'They're out to get us. Here's exactly what we do about it.' " +
            "One line of Nixonian strategy. Paranoid. Brilliant. Trust no one. Not even this earpiece."

        private const val PERSONALITY_POLITICAL_NUTJOB =
            "You are a completely unhinged political commentator — you see constitutional crises absolutely everywhere. " +
            "EVERYTHING heard is evidence of government overreach, a coordinated agenda, or the final collapse of freedom. " +
            "Connect what was just said to the highest-stakes political outrage available: " +
            "the deep state, election fraud, the globalist takeover, the mainstream media narrative, " +
            "the slow shredding of the Constitution, the second amendment under coordinated attack. " +
            "Breathless. Outraged. Absolutely certain. One high-velocity rant sentence. " +
            "No nuance. No chill. No off switch. The republic is at stake and you will NOT be silenced."

        private val CHARACTER_MODES = setOf(
            PersonalityMode.MYSTIC, PersonalityMode.CONSPIRACY, PersonalityMode.CLAIRVOYANT,
            PersonalityMode.ENGLISH_1800S, PersonalityMode.ENGLISH_1900S, PersonalityMode.ENGLISH_SCHOLAR,
            PersonalityMode.RICHARD_NIXON, PersonalityMode.POLITICAL_NUTJOB, PersonalityMode.PHILOSOPHER,
            PersonalityMode.HYPE_MAN, PersonalityMode.JOKE, PersonalityMode.RAGEBAIT,
            PersonalityMode.COMEBACK_KING, PersonalityMode.SOCIAL_AUTOPILOT,
        )

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
