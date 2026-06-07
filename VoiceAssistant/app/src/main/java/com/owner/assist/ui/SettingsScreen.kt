package com.owner.assist.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.owner.assist.audio.BluetoothScoManager
import com.owner.assist.data.LlmChoice
import com.owner.assist.data.OemAutostart
import com.owner.assist.data.PersonalityMode
import com.owner.assist.data.ResponseStyle
import com.owner.assist.data.SecureKeyStore
import com.owner.assist.data.StopKey
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val store = remember { SecureKeyStore(ctx) }

    var deepgram by remember { mutableStateOf(store.deepgramKey) }
    var groq by remember { mutableStateOf(store.groqKey) }
    var deepseek by remember { mutableStateOf(store.deepseekKey) }
    var provider by remember { mutableStateOf(store.llmProvider) }
    var contextBlurb by remember { mutableStateOf(store.contextBlurb) }
    var responsivenessLevel by remember { mutableIntStateOf(store.responsivenessLevel) }
    var maxThinkTimeSec by remember { mutableIntStateOf(store.maxThinkTimeSec) }
    var glassesButtons by remember { mutableStateOf(store.glassesButtonsEnabled) }
    var visionEnabled by remember { mutableStateOf(store.visionEnabled) }
    var visionAlwaysOn by remember { mutableStateOf(store.visionAlwaysOn) }
    var responseStyle by remember { mutableStateOf(store.responseStyle) }
    var personalityMode by remember { mutableStateOf(store.personalityMode) }
    var customPersonality by remember { mutableStateOf(store.customPersonality) }
    var stopKey by remember { mutableStateOf(store.stopKey) }
    var btMicAddress by remember { mutableStateOf(store.btMicAddress) }
    var autoClipEnabled by remember { mutableStateOf(store.autoClipEnabled) }
    var listenerClipsEnabled by remember { mutableStateOf(store.listenerClipsEnabled) }
    val pairedHeadsets = remember { BluetoothScoManager.listPairedHeadsets(ctx) }
    var customCallWord by remember { mutableStateOf(store.customCallWord) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {

            if (!store.isAvailable) {
                Text(
                    "Secure storage unavailable — keystore may be corrupted. Reinstall the app.",
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // ── API keys ──────────────────────────────────────────────────────
            SectionLabel("API keys")
            Text(
                "Deepgram key: deepgram.com/console  •  Groq key: console.groq.com  •  DeepSeek: platform.deepseek.com",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SecretField("Deepgram (STT + TTS)", deepgram) { deepgram = it }
            SecretField("Groq", groq) { groq = it }
            SecretField("DeepSeek (fallback)", deepseek) { deepseek = it }

            // ── LLM provider ──────────────────────────────────────────────────
            SectionLabel("LLM provider")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LlmChoice.entries.forEach { choice ->
                    FilterChip(
                        selected = provider == choice,
                        onClick = { provider = choice },
                        label = { Text(choice.display, maxLines = 1) },
                    )
                }
            }
            Text(
                "Groq runs Llama 3.3 70B — fast, generous free tier. DeepSeek is the backup model. Each uses its own key from the API keys section above.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ── Response style ────────────────────────────────────────────────
            SectionLabel("Response style")
            Text(
                "Immediate: one-word / one-phrase answers only. " +
                "Standard: 1-3 sentences. " +
                "Descriptive: full explanation with context.",
                style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ResponseStyle.entries.forEach { style ->
                    FilterChip(
                        selected = responseStyle == style,
                        onClick = { responseStyle = style },
                        label = { Text(style.display) },
                    )
                }
            }

            // ── Personality mode ──────────────────────────────────────────────
            SectionLabel("Personality mode")
            Text(
                "20 modes — from Technician and Hype Man to Richard Nixon and Victorian English. " +
                "Character modes ignore response-style injection so the voice stays in character. Custom = type your own.",
                style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PersonalityMode.entries.forEach { mode ->
                    FilterChip(
                        selected = personalityMode == mode,
                        onClick = { personalityMode = mode },
                        label = { Text(mode.display) },
                    )
                }
            }
            if (personalityMode == PersonalityMode.CUSTOM) {
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = customPersonality,
                    onValueChange = { customPersonality = it },
                    label = { Text("Custom personality") },
                    placeholder = { Text("e.g. You are a sarcastic pirate who answers every question in rhyme…") },
                    minLines = 4,
                    maxLines = 12,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "This becomes the AI's full instruction set. The API may reject extreme content — " +
                    "it will fall back to the default if that happens.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (personalityMode == PersonalityMode.DEFAULT) {
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = contextBlurb,
                    onValueChange = { contextBlurb = it },
                    label = { Text("Domain context (optional)") },
                    placeholder = { Text("e.g. You are an assistant for an HVAC technician…") },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Leave blank to keep the built-in forklift / technician context.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // ── Stop button ───────────────────────────────────────────────────
            SectionLabel("Stop button")
            Text(
                "Assign a hardware key to immediately stop the assistant from speaking. " +
                "Useful when you need to cut it off fast.",
                style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StopKey.entries.forEach { key ->
                    FilterChip(
                        selected = stopKey == key,
                        onClick = { stopKey = key },
                        label = { Text(key.display) },
                    )
                }
            }

            // ── Bluetooth mic ─────────────────────────────────────────────────
            SectionLabel("Bluetooth mic")
            if (pairedHeadsets.isEmpty()) {
                Text(
                    "No paired BT headsets found. Pair a headset in Android Bluetooth settings first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "Choose a preferred mic. The app will try this device first when connecting. " +
                    "Leave unselected to use any available BT device.",
                    style = MaterialTheme.typography.bodySmall,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = btMicAddress.isBlank(),
                        onClick = { btMicAddress = "" },
                        label = { Text("Any") },
                    )
                    pairedHeadsets.forEach { device ->
                        FilterChip(
                            selected = btMicAddress == device.address,
                            onClick = { btMicAddress = device.address },
                            label = { Text(device.name) },
                        )
                    }
                }
            }

            // ── Voice clips ───────────────────────────────────────────────────
            SectionLabel("Voice clips")
            Text(
                "Clips are saved to the app's external files directory (accessible via Files app).",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text("30-second session clips")
                    Text(
                        "Saves last 30s of audio every 30 seconds while running.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = autoClipEnabled, onCheckedChange = { autoClipEnabled = it })
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text("Listener clips (up to 3 voices)")
                    Text(
                        "Saves a 10-second clip for each detected non-self speaker.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = listenerClipsEnabled, onCheckedChange = { listenerClipsEnabled = it })
            }

            // ── Call word ──────────────────────────────────────────────────────────
            SectionLabel("Call word")
            Text(
                "If set, the assistant only responds when this word or phrase appears in speech. " +
                "Leave blank to respond to everything.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = customCallWord,
                onValueChange = { customCallWord = it },
                label = { Text("Call word (optional)") },
                placeholder = { Text("e.g. hey j, assistant, alex…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // ── Background reliability ─────────────────────────────────────────
            SectionLabel("Background reliability")
            Text(
                "Android will kill long-running background services unless you exempt this app.",
                style = MaterialTheme.typography.bodySmall,
            )
            val batteryOk = !OemAutostart.isBatteryOptimized(ctx)
            Text(
                "Battery optimization: " + if (batteryOk) "allowed" else "RESTRICTED",
                color = if (batteryOk) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error,
            )
            OutlinedButton(
                onClick = { OemAutostart.openBatteryOptimizationSettings(ctx) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Open battery exemption") }

            OemAutostart.oemHint()?.let { hint ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(hint, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(
                    onClick = { OemAutostart.openOemAutostartSettings(ctx) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Open autostart settings") }
            }

            // ── Response speed ────────────────────────────────────────────────
            SectionLabel("Response speed")
            val speedLabel = when {
                responsivenessLevel <= 20  -> "Very responsive — fastest start, noticeable gaps between sentences"
                responsivenessLevel <= 45  -> "Responsive — quick start with small gaps"
                responsivenessLevel <= 65  -> "Balanced — moderate start time, minimal gaps"
                responsivenessLevel <= 85  -> "Smooth — slower start, seamless audio"
                else                       -> "Very smooth — waits for full reply, no gaps at all"
            }
            Text(speedLabel, style = MaterialTheme.typography.bodySmall)
            Slider(
                value = responsivenessLevel.toFloat(),
                onValueChange = { responsivenessLevel = it.roundToInt() },
                valueRange = 0f..100f,
                steps = 9,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Fast", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Smooth", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // ── Max formulation time ──────────────────────────────────────────
            SectionLabel("Max formulation time")
            Text(
                "How long the AI can think before being cut off. Currently: ${maxThinkTimeSec}s. " +
                "If responses run on too long, lower this.",
                style = MaterialTheme.typography.bodySmall,
            )
            Slider(
                value = maxThinkTimeSec.toFloat(),
                onValueChange = { maxThinkTimeSec = it.roundToInt() },
                valueRange = 5f..60f,
                steps = 10,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("5s", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("60s", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // ── Vision / Camera ───────────────────────────────────────────────
            SectionLabel("Vision / Camera")
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Enable vision commands")
                Switch(checked = visionEnabled, onCheckedChange = { visionEnabled = it })
            }
            Text(
                "Say \"what is this\", \"read that\", \"identify this\" to capture and describe what the camera sees.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (visionEnabled) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Always-on scanning (Pi only)")
                    Switch(checked = visionAlwaysOn, onCheckedChange = { visionAlwaysOn = it })
                }
                Text(
                    "Captures a frame every 15s and logs detections — no speech. Requires Pi on same WiFi.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // ── Glasses button ────────────────────────────────────────────────
            SectionLabel("Glasses button control (in dev)")
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Intercept Meta Ray-Ban taps (in dev)")
                Switch(checked = false, onCheckedChange = {}, enabled = false)
            }
            Text(
                "Not yet active — needs live testing with glasses. " +
                "Requires Meta View disabled or uninstalled.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ── Save ──────────────────────────────────────────────────────────
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = {
                    store.deepgramKey = deepgram.trim()
                    store.groqKey = groq.trim()
                    store.deepseekKey = deepseek.trim()
                    store.llmProvider = provider
                    store.contextBlurb = contextBlurb.trim()
                    store.responsivenessLevel = responsivenessLevel
                    store.maxThinkTimeSec = maxThinkTimeSec
                    store.glassesButtonsEnabled = glassesButtons
                    store.visionEnabled = visionEnabled
                    store.visionAlwaysOn = visionAlwaysOn
                    store.customCallWord = customCallWord.trim()
                    store.responseStyle = responseStyle
                    store.personalityMode = personalityMode
                    store.customPersonality = customPersonality.trim()
                    store.stopKey = stopKey
                    store.btMicAddress = btMicAddress.trim()
                    store.autoClipEnabled = autoClipEnabled
                    store.listenerClipsEnabled = listenerClipsEnabled
                    onBack()
                },
                enabled = store.isAvailable,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun SecretField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (visible) "Hide" else "Show",
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}
