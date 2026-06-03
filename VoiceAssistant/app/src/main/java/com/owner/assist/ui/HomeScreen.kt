package com.owner.assist.ui

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.owner.assist.audio.MicCapture
import com.owner.assist.data.AssistantMode
import com.owner.assist.data.QuestionerProfileStore
import com.owner.assist.data.SecureKeyStore
import com.owner.assist.service.AssistantService
import com.owner.assist.service.AssistantState
import com.owner.assist.service.AssistantStateBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpenSettings: () -> Unit, onOpenChatLog: () -> Unit, onOpenNotes: () -> Unit) {
    val ctx = LocalContext.current
    val store = remember { SecureKeyStore(ctx) }
    val state by AssistantStateBus.state.collectAsState()
    val audioLevel by AssistantStateBus.audioLevel.collectAsState()
    val events by AssistantStateBus.events.collectAsState()
    val isSessionRecording by AssistantStateBus.isSessionRecording.collectAsState()
    val isTuningQuestioner by AssistantStateBus.isTuningQuestioner.collectAsState()
    val isOff = state == AssistantState.OFF

    var calibratingIn by remember { mutableStateOf<Int?>(null) }
    var isPlayingCalibration by remember { mutableStateOf(false) }
    var tuningIn by remember { mutableStateOf<Int?>(null) }
    val playbackScope = rememberCoroutineScope()
    var calibrationPath by remember { mutableStateOf(store.voiceCalibrationPath) }
    var assistantMode by remember { mutableStateOf(store.assistantMode) }
    var questionerCount by remember { mutableStateOf(QuestionerProfileStore.list(ctx).size) }

    LaunchedEffect(events) {
        val newPath = store.voiceCalibrationPath
        if (newPath.isNotEmpty()) calibrationPath = newPath
        questionerCount = QuestionerProfileStore.list(ctx).size
    }

    // Voice calibration countdown
    LaunchedEffect(calibratingIn) {
        val c = calibratingIn ?: return@LaunchedEffect
        if (c > 0) {
            delay(1_000)
            calibratingIn = c - 1
        } else {
            ctx.startService(AssistantService.calibrateWindowIntent(ctx))
            calibratingIn = null
        }
    }

    // Questioner tune countdown
    LaunchedEffect(tuningIn) {
        val t = tuningIn ?: return@LaunchedEffect
        if (t > 0) {
            delay(1_000)
            tuningIn = t - 1
        } else {
            ctx.startService(AssistantService.tuneQuestionerIntent(ctx))
            tuningIn = null
        }
    }

    val requiredPerms = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.all { it }) {
            ContextCompat.startForegroundService(ctx, AssistantService.startIntent(ctx))
        }
    }

    val animatedLevel by animateFloatAsState(
        targetValue = if (isOff) 0f else audioLevel,
        animationSpec = tween(durationMillis = 100),
        label = "audioLevel",
    )
    val ringColor by animateColorAsState(
        targetValue = state.ringColor(),
        animationSpec = tween(durationMillis = 200),
        label = "ringColor",
    )

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Voice Assistant") },
            actions = {
                IconButton(onClick = onOpenNotes) {
                    Icon(Icons.Filled.Book, contentDescription = "Notebook")
                }
                IconButton(onClick = onOpenChatLog) {
                    Icon(Icons.Filled.FiberManualRecord, contentDescription = "Chat log",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings")
                }
            },
        )

        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {

            // ── EMERGENCY STOP ──────────────────────────────────────────────
            if (!isOff) {
                Button(
                    onClick = { ctx.startService(AssistantService.stopIntent(ctx)) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp))
                    Text("EMERGENCY STOP", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // ── Mode toggle: Others / Me ────────────────────────────────────
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                AssistantMode.entries.forEachIndexed { idx, mode ->
                    SegmentedButton(
                        selected = assistantMode == mode,
                        onClick = {
                            assistantMode = mode
                            store.assistantMode = mode
                        },
                        shape = SegmentedButtonDefaults.itemShape(idx, AssistantMode.entries.size),
                        label = { Text(mode.display) },
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            // ── Audio level ring ────────────────────────────────────────────
            Box(modifier = Modifier.size(120.dp), contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.size(120.dp)) {
                    val strokeWidth = 6.dp.toPx()
                    val inset = strokeWidth / 2
                    val sweep = animatedLevel * 360f
                    drawArc(
                        color = ringColor.copy(alpha = 0.25f),
                        startAngle = -90f, sweepAngle = 360f, useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = Size(size.width - strokeWidth, size.height - strokeWidth),
                        style = Stroke(strokeWidth),
                    )
                    if (sweep > 0f) {
                        drawArc(
                            color = ringColor,
                            startAngle = -90f, sweepAngle = sweep, useCenter = false,
                            topLeft = Offset(inset, inset),
                            size = Size(size.width - strokeWidth, size.height - strokeWidth),
                            style = Stroke(strokeWidth),
                        )
                    }
                }
                Box(
                    modifier = Modifier.size(96.dp).clip(CircleShape).background(state.uiColor()),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = state.label(), color = Color.White,
                        style = MaterialTheme.typography.titleMedium)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(text = state.hint(), style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(24.dp))

            if (!store.keysComplete()) {
                Text("API keys missing — open Settings.", color = MaterialTheme.colorScheme.error)
                Spacer(modifier = Modifier.height(12.dp))
            }

            if (!isOff) {
                // Restart
                OutlinedButton(
                    onClick = { ctx.startService(AssistantService.restartIntent(ctx)) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Restart") }
                Spacer(modifier = Modifier.height(8.dp))

                // Voice calibration
                OutlinedButton(
                    onClick = { if (calibratingIn == null) calibratingIn = 3 },
                    enabled = calibratingIn == null && tuningIn == null && !isTuningQuestioner,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(when {
                        calibratingIn != null -> "Speak now… $calibratingIn"
                        else -> "Calibrate My Voice"
                    })
                }

                if (calibrationPath.isNotEmpty() && File(calibrationPath).exists()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = {
                            if (!isPlayingCalibration) {
                                isPlayingCalibration = true
                                playbackScope.launch {
                                    playWavFile(calibrationPath)
                                    isPlayingCalibration = false
                                }
                            }
                        },
                        enabled = !isPlayingCalibration,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (isPlayingCalibration) "Playing…" else "Play Voice Sample") }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Tune Questioner button + clear
                val tuneLabel = when {
                    isTuningQuestioner -> "Recording questioner…"
                    tuningIn != null -> "Have them speak… $tuningIn"
                    else -> "Tune Questioner${if (questionerCount > 0) " ($questionerCount)" else ""}"
                }
                OutlinedButton(
                    onClick = { if (tuningIn == null && !isTuningQuestioner) tuningIn = 3 },
                    enabled = tuningIn == null && !isTuningQuestioner && calibratingIn == null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(tuneLabel) }

                if (questionerCount > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = {
                            ctx.startService(AssistantService.clearQuestionersIntent(ctx))
                            questionerCount = 0
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Clear Questioner Profiles") }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Session recording
                if (isSessionRecording) {
                    Button(
                        onClick = { ctx.startService(AssistantService.stopRecordIntent(ctx)) },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = null,
                            modifier = Modifier.padding(end = 6.dp))
                        Text("Stop Recording")
                    }
                } else {
                    OutlinedButton(
                        onClick = { ctx.startService(AssistantService.recordSessionIntent(ctx)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.FiberManualRecord, contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(end = 6.dp))
                        Text("Record This Session")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
            }

            // ── ON / OFF ────────────────────────────────────────────────────
            Button(
                onClick = {
                    if (isOff) {
                        val missing = requiredPerms.filter {
                            ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
                        }
                        if (missing.isNotEmpty()) permLauncher.launch(missing.toTypedArray())
                        else ContextCompat.startForegroundService(ctx, AssistantService.startIntent(ctx))
                    } else {
                        ctx.startService(AssistantService.stopIntent(ctx))
                    }
                },
                enabled = if (isOff) store.keysComplete() else true,
                colors = if (isOff) ButtonDefaults.buttonColors()
                else ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (isOff) "Turn ON" else "Turn OFF")
            }

            // Event log
            if (events.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Column(modifier = Modifier.fillMaxWidth()) {
                    events.forEach { event ->
                        Text(
                            text = event,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

// ── Suspend WAV playback ──────────────────────────────────────────────────────

private suspend fun playWavFile(path: String) = withContext(Dispatchers.IO) {
    val file = File(path)
    if (!file.exists() || file.length() <= 44) return@withContext
    val chunkSize = 16_000 * 2
    val bufferSize = (AudioTrack.getMinBufferSize(
        MicCapture.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
    ) * 4).coerceAtLeast(chunkSize)
    val track = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(MicCapture.SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
        )
        .setBufferSizeInBytes(bufferSize)
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()
    try {
        track.play()
        file.inputStream().use { stream ->
            stream.skip(44)
            val buf = ByteArray(chunkSize)
            while (true) {
                val read = stream.read(buf)
                if (read <= 0) break
                val written = track.write(buf, 0, read, AudioTrack.WRITE_BLOCKING)
                if (written < 0) {
                    android.util.Log.w("HomeScreen", "AudioTrack write error $written")
                    break
                }
            }
        }
        delay(bufferSize.toLong() * 1000L / MicCapture.SAMPLE_RATE / 2 + 300L)
    } finally {
        runCatching { track.stop(); track.release() }
    }
}

// ── Extensions ────────────────────────────────────────────────────────────────

private fun AssistantState.label(): String = when (this) {
    AssistantState.OFF       -> "Off"
    AssistantState.LISTENING -> "Listen"
    AssistantState.THINKING  -> "Think"
    AssistantState.SPEAKING  -> "Speak"
}

private fun AssistantState.hint(): String = when (this) {
    AssistantState.OFF       -> "Tap ON to start a session."
    AssistantState.LISTENING -> "Listening… audio ring shows mic level."
    AssistantState.THINKING  -> "Formulating response…"
    AssistantState.SPEAKING  -> "Speaking — mic muted to prevent feedback."
}

private fun AssistantState.uiColor(): Color = when (this) {
    AssistantState.OFF       -> Color(0xFF9E9E9E)
    AssistantState.LISTENING -> Color(0xFF2E7D32)
    AssistantState.THINKING  -> Color(0xFFE65100)
    AssistantState.SPEAKING  -> Color(0xFF1565C0)
}

private fun AssistantState.ringColor(): Color = when (this) {
    AssistantState.OFF       -> Color(0xFF9E9E9E)
    AssistantState.LISTENING -> Color(0xFF4CAF50)
    AssistantState.THINKING  -> Color(0xFFFF9800)
    AssistantState.SPEAKING  -> Color(0xFF2196F3)
}
